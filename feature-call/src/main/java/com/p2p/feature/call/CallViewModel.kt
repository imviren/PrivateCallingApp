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
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

// ── UI State ──────────────────────────────────────────────────────────────────

enum class CallState {
    IDLE,          // No active call
    OUTGOING,      // We initiated; waiting for answer
    INCOMING,      // Remote offer received; waiting for user to accept (Phase 2+)
    CONNECTING,    // ICE negotiation in progress
    CONNECTED,     // Media flowing
    ENDED,         // Call finished or failed
}

data class CallUiState(
    val callState: CallState          = CallState.IDLE,
    val peerAddress: String           = "",
    val localAddressSummary: String   = "",
    val remoteVideoTrack: VideoTrack? = null,
    val errorMessage: String?         = null,
    val isMicMuted: Boolean           = false,
    val isCameraOff: Boolean          = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class CallViewModel @Inject constructor(
    private val webRtcManager: WebRtcManager,
    private val signalingServer: SignalingServer,
    private val signalingClient: SignalingClient,
    private val tailscaleDetector: TailscaleDetector,
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
            _uiState.value = _uiState.value.copy(
                localAddressSummary = addrs.summary()
            )
        }

        // Initialise WebRTC factory once
        webRtcManager.initFactory()

        // Observe SignalingServer (callee-role events)
        viewModelScope.launch {
            signalingServer.events.collect { event ->
                Timber.tag(TAG).d("Server event: $event")
                handleSignalingEvent(event, fromServer = true)
            }
        }

        // Observe SignalingClient (caller-role events)
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                Timber.tag(TAG).d("Client event: $event")
                handleSignalingEvent(event, fromServer = false)
            }
        }

        // Observe WebRTC events
        viewModelScope.launch {
            webRtcManager.events.collect { event -> handleWebRtcEvent(event) }
        }
    }

    // ── Outgoing call ─────────────────────────────────────────────────────────

    /**
     * Initiate a call to [peerAddress].
     * Connects the signaling client, creates a WebRTC offer, and transmits it.
     */
    fun startCall(peerAddress: String) {
        if (_uiState.value.callState != CallState.IDLE) return

        callRole      = CallRole.CALLER
        currentCallId = UUID.randomUUID().toString()

        _uiState.value = _uiState.value.copy(
            callState   = CallState.OUTGOING,
            peerAddress = peerAddress,
        )

        val wsUrl = "ws://$peerAddress:${SignalingServer.PORT}/signal"
        Timber.tag(TAG).i("Dialing $wsUrl  callId=$currentCallId")

        viewModelScope.launch {
            // Prepare media + connection
            webRtcManager.initLocalMedia()
            webRtcManager.initPeerConnection()

            // Open signaling socket
            signalingClient.connect(wsUrl)

            // Wait for Connected event before creating offer
            // (handled via signalingClient.events → handleSignalingEvent → CALLER path)
        }
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
        _uiState.value = _uiState.value.copy(isMicMuted = muted)
        // TODO: call webRtcManager.setAudioEnabled(!muted) in Phase 2
    }

    fun toggleCamera() {
        val off = !_uiState.value.isCameraOff
        _uiState.value = _uiState.value.copy(isCameraOff = off)
        // TODO: call webRtcManager.setCameraEnabled(!off) in Phase 2
    }

    // ── Signaling event dispatch ──────────────────────────────────────────────

    private suspend fun handleSignalingEvent(event: SignalingEvent, fromServer: Boolean) {
        when (event) {

            // ── Channel opened (caller only: server emits Connected for incoming connect) ──
            SignalingEvent.Connected -> {
                if (callRole == CallRole.CALLER) {
                    // Socket is ready → create and send offer
                    Timber.tag(TAG).i("Client connected → creating offer")
                    webRtcManager.createOffer { sdp ->
                        viewModelScope.launch {
                            signalingClient.send(
                                SignalingMessage.Offer(
                                    callId = currentCallId,
                                    sdp    = sdp.description,
                                )
                            )
                            Timber.tag(TAG).i("Offer sent")
                        }
                    }
                }
            }

            // ── Offer received (we are the callee) ────────────────────────────
            is SignalingEvent.IncomingOffer -> {
                callRole      = CallRole.CALLEE
                currentCallId = event.message.callId

                _uiState.value = _uiState.value.copy(callState = CallState.INCOMING)
                Timber.tag(TAG).i("Incoming offer  callId=$currentCallId")

                // For Phase 0/1 auto-accept; add a UI prompt in Phase 2.
                acceptIncomingCall(event.message)
            }

            // ── Answer received (we are the caller) ───────────────────────────
            is SignalingEvent.IncomingAnswer -> {
                if (callRole != CallRole.CALLER) return
                Timber.tag(TAG).i("Incoming answer")
                webRtcManager.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, event.message.sdp)
                )
                _uiState.value = _uiState.value.copy(callState = CallState.CONNECTING)
            }

            // ── Trickle ICE ───────────────────────────────────────────────────
            is SignalingEvent.IncomingIce -> {
                webRtcManager.addIceCandidate(
                    IceCandidate(
                        event.message.sdpMid,
                        event.message.sdpMLineIndex,
                        event.message.sdp,
                    )
                )
            }

            // ── Bye ───────────────────────────────────────────────────────────
            SignalingEvent.IncomingBye -> {
                Timber.tag(TAG).i("Remote sent BYE")
                cleanup()
            }

            SignalingEvent.Disconnected -> {
                if (_uiState.value.callState != CallState.ENDED) {
                    Timber.tag(TAG).w("Signaling disconnected unexpectedly")
                    _uiState.value = _uiState.value.copy(
                        callState    = CallState.ENDED,
                        errorMessage = "Connection lost",
                    )
                    webRtcManager.dispose()
                }
            }
        }
    }

    // ── Callee auto-accept logic ──────────────────────────────────────────────

    private fun acceptIncomingCall(offer: SignalingMessage.Offer) {
        viewModelScope.launch {
            webRtcManager.initLocalMedia()
            webRtcManager.initPeerConnection()

            webRtcManager.setRemoteDescription(
                SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
            )
            webRtcManager.createAnswer { sdp ->
                viewModelScope.launch {
                    signalingServer.send(
                        SignalingMessage.Answer(
                            callId = currentCallId,
                            sdp    = sdp.description,
                        )
                    )
                    Timber.tag(TAG).i("Answer sent")
                    _uiState.value = _uiState.value.copy(callState = CallState.CONNECTING)
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
                        callId         = currentCallId,
                        sdp            = event.candidate.sdp,
                        sdpMid         = event.candidate.sdpMid,
                        sdpMLineIndex  = event.candidate.sdpMLineIndex,
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
                _uiState.value = _uiState.value.copy(callState = newState)
            }

            is WebRtcEvent.RemoteTrackAdded -> {
                if (event.track is VideoTrack) {
                    _uiState.value = _uiState.value.copy(
                        remoteVideoTrack = event.track as VideoTrack
                    )
                }
            }

            is WebRtcEvent.Error -> {
                _uiState.value = _uiState.value.copy(
                    callState    = CallState.ENDED,
                    errorMessage = event.message,
                )
            }

            else -> Unit
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

    private suspend fun cleanup() {
        Timber.tag(TAG).i("cleanup()")
        _uiState.value = _uiState.value.copy(
            callState        = CallState.ENDED,
            remoteVideoTrack = null,
        )
        webRtcManager.dispose()
        signalingClient.disconnect()
        callRole      = CallRole.NONE
        currentCallId = ""
    }

    override fun onCleared() {
        super.onCleared()
        webRtcManager.dispose()
    }
}
