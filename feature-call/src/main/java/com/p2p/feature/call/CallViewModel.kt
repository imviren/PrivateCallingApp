package com.p2p.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.core.media.WebRtcEvent
import com.p2p.core.media.WebRtcManager
import com.p2p.core.network.SignalingClient
import com.p2p.core.network.SignalingEvent
import com.p2p.core.network.SignalingMessage
import com.p2p.core.network.SignalingServer
import com.p2p.core.network.TailscaleDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import timber.log.Timber
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

// ── UI State ──────────────────────────────────────────────────────────────────

enum class CallState {
    IDLE,          // No active call
    OUTGOING,      // We initiated; waiting for answer
    INCOMING,      // Remote offer received; waiting for user to accept
    CONNECTING,    // ICE negotiation in progress
    CONNECTED,     // Media flowing
    ENDED,         // Call finished or failed
}

/**
 * A single chat message within a call.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isMine: Boolean,
    val senderLabel: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

data class CallUiState(
    val callState: CallState          = CallState.IDLE,
    val peerAddress: String           = "",
    val localAddressSummary: String   = "",
    val remoteVideoTrack: VideoTrack? = null,
    val errorMessage: String?         = null,
    val isMicMuted: Boolean           = false,
    val isCameraOff: Boolean          = false,
    val isVideoActive: Boolean        = false,  // true once camera has been escalated
    val isChatOpen: Boolean           = false,
    val messages: List<ChatMessage>   = emptyList(),
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class CallViewModel @Inject constructor(
    private val webRtcManager: WebRtcManager,
    private val signalingServer: SignalingServer,
    private val signalingClient: SignalingClient,
    private val tailscaleDetector: TailscaleDetector,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "CallViewModel"
    }

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    // CALLER or CALLEE – determines which channel to send ICE candidates on
    private enum class CallRole { NONE, CALLER, CALLEE }
    private var callRole = CallRole.NONE
    private var currentCallId: String = ""

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // Resolve local addresses for display
        viewModelScope.launch {
            val addrs = tailscaleDetector.getAddresses()
            _uiState.update { it.copy(localAddressSummary = addrs.summary()) }
        }

        // Initialise WebRTC factory once
        webRtcManager.initFactory()

        // Observe SignalingServer (callee-role events)
        viewModelScope.launch {
            signalingServer.events.collect { event ->
                Timber.tag(TAG).d("Server event: %s", event)
                handleSignalingEvent(event, fromServer = true)
            }
        }

        // Observe SignalingClient (caller-role events)
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                Timber.tag(TAG).d("Client event: %s", event)
                handleSignalingEvent(event, fromServer = false)
            }
        }

        // Observe WebRTC events
        viewModelScope.launch {
            webRtcManager.events.collect { event -> handleWebRtcEvent(event) }
        }
    }

    // ── Outgoing call ─────────────────────────────────────────────────────────

    fun startCall(peerAddress: String) {
        if (_uiState.value.callState != CallState.IDLE) return

        callRole      = CallRole.CALLER
        currentCallId = UUID.randomUUID().toString()

        _uiState.update { it.copy(callState = CallState.OUTGOING, peerAddress = peerAddress) }

        val wsUrl = "ws://$peerAddress:${SignalingServer.PORT}/signal"
        Timber.tag(TAG).i("Dialing $wsUrl  callId=$currentCallId")

        viewModelScope.launch {
            updateSignalingServiceCallState(true)
            // Audio only for now; video track is added on demand
            webRtcManager.initLocalMedia()
            webRtcManager.initPeerConnection()
            signalingClient.connect(wsUrl)
        }
    }

    fun setIncomingCall(peerAddress: String) {
        if (_uiState.value.callState != CallState.IDLE) return
        callRole = CallRole.CALLEE
        _uiState.update { it.copy(callState = CallState.INCOMING, peerAddress = peerAddress) }
        Timber.tag(TAG).i("setIncomingCall: waiting for accept from $peerAddress")
        startRingtone()
    }

    fun acceptCall() {
        val offer = signalingServer.pendingOffer
        if (offer == null) {
            Timber.tag(TAG).e("acceptCall failed: no pending offer found in SignalingServer")
            return
        }
        currentCallId = offer.callId

        // Apply any early ICE candidates received before accepting
        val earlyIce = signalingServer.getAndClearPendingIceCandidates()
        for (candidate in earlyIce) {
            webRtcManager.addIceCandidate(
                IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            )
        }
        acceptIncomingCall(offer)
    }

    fun declineCall() {
        hangUp()
    }

    // ── Video escalation ──────────────────────────────────────────────────────

    /**
     * Escalate to video call: starts camera and triggers an SDP renegotiation.
     * The [WebRtcEvent.RenegotiationNeeded] event will be emitted by [WebRtcManager]
     * once the video track is added to the PeerConnection, and handled here.
     */
    fun startVideo() {
        if (_uiState.value.isVideoActive) return
        Timber.tag(TAG).i("startVideo() — escalating to video")
        _uiState.update { it.copy(isVideoActive = true) }
        webRtcManager.initLocalVideo()
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    fun toggleChat() {
        _uiState.update { it.copy(isChatOpen = !it.isChatOpen) }
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val msg = ChatMessage(text = text.trim(), isMine = true)
        _uiState.update { it.copy(messages = it.messages + msg) }
        sendViaActiveChannelSync(
            SignalingMessage.TextMessage(
                callId      = currentCallId,
                text        = text.trim(),
                senderLabel = "me",
            )
        )
    }

    // ── Hangup ────────────────────────────────────────────────────────────────

    fun hangUp() {
        Timber.tag(TAG).i("hangUp()")
        viewModelScope.launch {
            sendViaActiveChannel(SignalingMessage.Bye)
            cleanup()
        }
    }

    // ── Toggle controls ───────────────────────────────────────────────────────

    fun toggleMic() {
        val muted = !_uiState.value.isMicMuted
        _uiState.update { it.copy(isMicMuted = muted) }
        webRtcManager.setAudioEnabled(!muted)
    }

    fun toggleCamera() {
        val off = !_uiState.value.isCameraOff
        _uiState.update { it.copy(isCameraOff = off) }
        webRtcManager.setCameraEnabled(!off)
    }

    // ── Signaling event dispatch ──────────────────────────────────────────────

    private suspend fun handleSignalingEvent(event: SignalingEvent, fromServer: Boolean) {
        when (event) {

            // ── Channel opened (caller only) ────────────────────────────────
            SignalingEvent.Connected -> {
                if (callRole == CallRole.CALLER) {
                    Timber.tag(TAG).i("Client connected → creating offer")
                    webRtcManager.createOffer { sdp ->
                        viewModelScope.launch {
                            signalingClient.send(
                                SignalingMessage.Offer(callId = currentCallId, sdp = sdp.description)
                            )
                            Timber.tag(TAG).i("Offer sent")
                        }
                    }
                }
            }

            // ── Offer received (we are the callee) ─────────────────────────
            is SignalingEvent.IncomingOffer -> {
                callRole      = CallRole.CALLEE
                currentCallId = event.message.callId
                _uiState.update { it.copy(callState = CallState.INCOMING, peerAddress = signalingServer.remoteHost) }
                Timber.tag(TAG).i("Incoming offer  callId=$currentCallId from ${signalingServer.remoteHost}")
                startRingtone()
            }

            // ── Answer received (we are the caller) ────────────────────────
            is SignalingEvent.IncomingAnswer -> {
                if (callRole != CallRole.CALLER) return
                Timber.tag(TAG).i("Incoming answer")
                webRtcManager.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, event.message.sdp)
                )
                _uiState.update { it.copy(callState = CallState.CONNECTING) }
            }

            // ── Trickle ICE ─────────────────────────────────────────────────
            is SignalingEvent.IncomingIce -> {
                webRtcManager.addIceCandidate(
                    IceCandidate(event.message.sdpMid, event.message.sdpMLineIndex, event.message.sdp)
                )
            }

            // ── Mid-call renegotiation — new offer from peer ────────────────
            is SignalingEvent.IncomingRenegotiateOffer -> {
                Timber.tag(TAG).i("Incoming renegotiate offer")
                webRtcManager.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.OFFER, event.message.sdp)
                ) {
                    webRtcManager.createAnswer { sdp ->
                        viewModelScope.launch {
                            sendViaActiveChannel(
                                SignalingMessage.RenegotiateAnswer(
                                    callId = currentCallId,
                                    sdp    = sdp.description,
                                )
                            )
                            Timber.tag(TAG).i("Renegotiate answer sent")
                        }
                    }
                }
            }

            // ── Mid-call renegotiation — answer to our new offer ────────────
            is SignalingEvent.IncomingRenegotiateAnswer -> {
                Timber.tag(TAG).i("Incoming renegotiate answer")
                webRtcManager.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, event.message.sdp)
                )
            }

            // ── In-call text message ────────────────────────────────────────
            is SignalingEvent.IncomingTextMessage -> {
                val incoming = ChatMessage(
                    text        = event.message.text,
                    isMine      = false,
                    senderLabel = event.message.senderLabel,
                )
                _uiState.update { it.copy(messages = it.messages + incoming) }
            }

            // ── Bye ─────────────────────────────────────────────────────────
            SignalingEvent.IncomingBye -> {
                Timber.tag(TAG).i("Remote sent BYE")
                cleanup()
            }

            SignalingEvent.Disconnected -> {
                if (_uiState.value.callState != CallState.ENDED) {
                    Timber.tag(TAG).w("Signaling disconnected unexpectedly")
                    _uiState.update { it.copy(callState = CallState.ENDED, errorMessage = "Connection lost") }
                    webRtcManager.dispose()
                    updateSignalingServiceCallState(false)
                }
            }
        }
    }

    // ── Callee accept logic ───────────────────────────────────────────────────

    private fun acceptIncomingCall(offer: SignalingMessage.Offer) {
        stopRingtone()
        viewModelScope.launch {
            updateSignalingServiceCallState(true)
            // Audio only; video will be escalated on demand
            webRtcManager.initLocalMedia()
            webRtcManager.initPeerConnection()

            webRtcManager.setRemoteDescription(
                SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
            ) {
                webRtcManager.createAnswer { sdp ->
                    viewModelScope.launch {
                        signalingServer.send(
                            SignalingMessage.Answer(callId = currentCallId, sdp = sdp.description)
                        )
                        Timber.tag(TAG).i("Answer sent")
                        _uiState.update { it.copy(callState = CallState.CONNECTING) }
                    }
                }
            }
        }
    }

    // ── WebRTC event dispatch ─────────────────────────────────────────────────

    private fun handleWebRtcEvent(event: WebRtcEvent) {
        when (event) {

            is WebRtcEvent.LocalIceCandidate -> {
                sendViaActiveChannelSync(
                    SignalingMessage.IceCandidate(
                        callId        = currentCallId,
                        sdp           = event.candidate.sdp,
                        sdpMid        = event.candidate.sdpMid,
                        sdpMLineIndex = event.candidate.sdpMLineIndex,
                    )
                )
            }

            is WebRtcEvent.IceConnectionStateChanged -> {
                val newState = when (event.state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> CallState.CONNECTED
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        viewModelScope.launch { cleanup() }
                        CallState.ENDED
                    }
                    else -> return
                }
                _uiState.update { it.copy(callState = newState) }
            }

            is WebRtcEvent.RemoteTrackAdded -> {
                if (event.track is VideoTrack) {
                    _uiState.update { it.copy(remoteVideoTrack = event.track as VideoTrack) }
                }
            }

            is WebRtcEvent.Error -> {
                _uiState.update { it.copy(callState = CallState.ENDED, errorMessage = event.message) }
            }

            // ── Renegotiation needed (e.g. video track added) ─────────────
            WebRtcEvent.RenegotiationNeeded -> {
                val state = _uiState.value.callState
                if (state == CallState.CONNECTED || state == CallState.CONNECTING) {
                    Timber.tag(TAG).i("RenegotiationNeeded — creating renegotiate offer")
                    webRtcManager.createOffer { sdp ->
                        viewModelScope.launch {
                            sendViaActiveChannel(
                                SignalingMessage.RenegotiateOffer(
                                    callId = currentCallId,
                                    sdp    = sdp.description,
                                )
                            )
                            Timber.tag(TAG).i("Renegotiate offer sent")
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun sendViaActiveChannel(message: SignalingMessage) {
        when (callRole) {
            CallRole.CALLER -> signalingClient.send(message)
            CallRole.CALLEE -> signalingServer.send(message)
            CallRole.NONE   -> Timber.tag(TAG).w("sendViaActiveChannel: no role")
        }
    }

    private fun sendViaActiveChannelSync(message: SignalingMessage) {
        viewModelScope.launch { sendViaActiveChannel(message) }
    }

    private var ringtone: android.media.Ringtone? = null

    private fun startRingtone() {
        if (ringtone != null && ringtone?.isPlaying == true) return
        try {
            val notificationUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            ringtone = android.media.RingtoneManager.getRingtone(context, notificationUri)?.apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    isLooping = true
                }
                audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            Timber.tag(TAG).i("Ringtone started playing")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to play ringtone")
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.let { if (it.isPlaying) { it.stop(); Timber.tag(TAG).i("Ringtone stopped") } }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stop ringtone")
        } finally {
            ringtone = null
        }
    }

    private suspend fun cleanup() {
        Timber.tag(TAG).i("cleanup()")
        stopRingtone()
        _uiState.update { it.copy(callState = CallState.ENDED, remoteVideoTrack = null, isVideoActive = false, isChatOpen = false) }
        webRtcManager.dispose()
        signalingClient.disconnect()
        updateSignalingServiceCallState(false)
        signalingServer.clearPendingCall()
        callRole      = CallRole.NONE
        currentCallId = ""
    }

    private fun updateSignalingServiceCallState(inCall: Boolean) {
        val intent = Intent(context, com.p2p.core.network.SignalingService::class.java).apply {
            action = if (inCall) com.p2p.core.network.SignalingService.ACTION_START_CALL
                     else        com.p2p.core.network.SignalingService.ACTION_END_CALL
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to update SignalingService call state")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRingtone()
        webRtcManager.dispose()
        signalingClient.shutdown()
        updateSignalingServiceCallState(false)
        signalingServer.clearPendingCall()
    }
}
