package com.p2p.core.media

import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection

sealed interface WebRtcEvent {
    data class LocalIceCandidate(val candidate: IceCandidate) : WebRtcEvent
    data class IceConnectionStateChanged(val state: PeerConnection.IceConnectionState) : WebRtcEvent
    data class RemoteTrackAdded(val track: MediaStreamTrack) : WebRtcEvent
    data class Error(val message: String) : WebRtcEvent
}
