package com.p2p.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SignalingMessage {

    @Serializable
    @SerialName("offer")
    data class Offer(
        val callId: String,
        val sdp: String
    ) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(
        val callId: String,
        val sdp: String
    ) : SignalingMessage()

    @Serializable
    @SerialName("ice")
    data class IceCandidate(
        val callId: String,
        val sdp: String,
        val sdpMid: String,
        val sdpMLineIndex: Int
    ) : SignalingMessage()

    @Serializable
    @SerialName("bye")
    object Bye : SignalingMessage()
}

sealed interface SignalingEvent {
    object Connected : SignalingEvent
    object Disconnected : SignalingEvent
    data class IncomingOffer(val message: SignalingMessage.Offer) : SignalingEvent
    data class IncomingAnswer(val message: SignalingMessage.Answer) : SignalingEvent
    data class IncomingIce(val message: SignalingMessage.IceCandidate) : SignalingEvent
    object IncomingBye : SignalingEvent
}
