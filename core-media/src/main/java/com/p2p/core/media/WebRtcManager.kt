package com.p2p.core.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.webrtc.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebRtcManager"
    }

    private val _events = MutableSharedFlow<WebRtcEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<WebRtcEvent> = _events.asSharedFlow()

    private val eglBase: EglBase = EglBase.create()
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: VideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var cachedFrontCameraName: String? = null

    fun initFactory() {
        if (peerConnectionFactory != null) return

        try {
            Timber.tag(TAG).i("Initializing PeerConnectionFactory...")
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val options = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize PeerConnectionFactory, cleaning up EGL Context")
            try {
                eglBase.release()
            } catch (ex: Exception) {
                Timber.tag(TAG).e(ex, "Failed to release EGL base")
            }
            throw e
        }
    }

    fun initLocalMedia() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("Factory not initialized")
        if (localVideoTrack != null) return

        Timber.tag(TAG).i("Initializing local media tracks...")
        // Audio Track
        val audioConstraints = MediaConstraints()
        val isLowEnd = isLowEndDevice()
        
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        if (!isLowEnd) {
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        } else {
            Timber.tag(TAG).i("Low-end device detected: disabling Auto Gain Control to conserve CPU")
        }
        
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)

        // Video Track
        val enumerator = Camera2Enumerator(context)
        var selectedDevice = cachedFrontCameraName
        if (selectedDevice == null) {
            val deviceNames = enumerator.deviceNames
            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    selectedDevice = deviceName
                    break
                }
            }
            if (selectedDevice == null && deviceNames.isNotEmpty()) {
                selectedDevice = deviceNames[0]
            }
            cachedFrontCameraName = selectedDevice
        }

        if (selectedDevice != null) {
            videoCapturer = enumerator.createCapturer(selectedDevice, null)
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer!!.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
            // Resolution: 640x480 at 24 fps (reduces CPU & battery drain)
            videoCapturer!!.startCapture(640, 480, 24)

            localVideoTrack = factory.createVideoTrack("ARDAMSv0", localVideoSource)
            localVideoTrack?.setEnabled(true)
        } else {
            Timber.tag(TAG).w("No camera device found.")
        }
    }

    fun initPeerConnection() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("Factory not initialized")
        if (peerConnection != null) return

        Timber.tag(TAG).i("Creating PeerConnection...")
        // Using Cloudflare TURN or basic config
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            // In Phase 5 we can add cloudflare TURN credential here
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Timber.tag(TAG).i("ICE connection state changed to: $state")
                _events.tryEmit(WebRtcEvent.IceConnectionStateChanged(state))
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Timber.tag(TAG).i("ICE gathering state changed to: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Timber.tag(TAG).d("New local ICE candidate: %s", candidate)
                _events.tryEmit(WebRtcEvent.LocalIceCandidate(candidate))
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                Timber.tag(TAG).i("Track added: %s", track?.kind())
                if (track != null) {
                    if (track is VideoTrack) {
                        replaceRemoteVideoTrack(track)
                    }
                    _events.tryEmit(WebRtcEvent.RemoteTrackAdded(track))
                }
            }
        }

        peerConnection = factory.createPeerConnection(rtcConfig, observer)

        // Add local tracks to Connection
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }
        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        callback(desc)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {
                        Timber.tag(TAG).e("SetLocalDescription failed: $p0")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                Timber.tag(TAG).e("CreateOffer failed: $p0")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        callback(desc)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {
                        Timber.tag(TAG).e("SetLocalDescription failed: $p0")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                Timber.tag(TAG).e("CreateAnswer failed: $p0")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Timber.tag(TAG).i("Remote description set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Timber.tag(TAG).e("SetRemoteDescription failed: $p0")
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(true)
        localVideoTrack?.addSink(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderer.setEnableHardwareScaler(true)
        remoteVideoTrack?.addSink(renderer)
    }

    fun releaseRenderer(renderer: SurfaceViewRenderer) {
        try {
            localVideoTrack?.removeSink(renderer)
            remoteVideoTrack?.removeSink(renderer)
            renderer.release()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error releasing renderer")
        }
    }

    fun dispose() {
        Timber.tag(TAG).i("Disposing media assets and PeerConnection...")
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            // Ignore
        }
        videoCapturer = null

        localVideoSource?.dispose()
        localVideoSource = null

        localAudioSource?.dispose()
        localAudioSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnection?.close()
        peerConnection = null

        localVideoTrack = null
        localAudioTrack = null
        remoteVideoTrack = null
    }

    private fun replaceRemoteVideoTrack(newTrack: VideoTrack) {
        val oldTrack = remoteVideoTrack
        if (oldTrack != null && oldTrack != newTrack) {
            try {
                oldTrack.dispose()
                Timber.tag(TAG).i("Old remote video track disposed")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to dispose old track, may leak GPU resources")
            }
        }
        remoteVideoTrack = newTrack
    }

    private fun isLowEndDevice(): Boolean {
        val runtime = Runtime.getRuntime()
        val availableHeap = runtime.maxMemory() / (1024 * 1024)
        
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamGB = memInfo.totalMem / (1024 * 1024 * 1024)
        
        val cpuCores = Runtime.getRuntime().availableProcessors()
        
        Timber.tag(TAG).i("Device Profiling - Available Heap: %dMB, Total RAM: %dGB, CPU Cores: %d", availableHeap, totalRamGB, cpuCores)
        return availableHeap < 256 || totalRamGB < 2 || cpuCores < 4
    }
}
