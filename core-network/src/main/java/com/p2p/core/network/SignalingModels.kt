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

    /** Mid-call renegotiation offer (e.g. when video is escalated). */
    @Serializable
    @SerialName("renegotiate_offer")
    data class RenegotiateOffer(
        val callId: String,
        val sdp: String
    ) : SignalingMessage()

    /** Mid-call renegotiation answer. */
    @Serializable
    @SerialName("renegotiate_answer")
    data class RenegotiateAnswer(
        val callId: String,
        val sdp: String
    ) : SignalingMessage()

    /** In-call text chat message. */
    @Serializable
    @SerialName("text")
    data class TextMessage(
        val callId: String,
        val text: String,
        val senderLabel: String = ""
    ) : SignalingMessage()

    /** Standalone peer-to-peer text session message (no active call required). */
    @Serializable
    @SerialName("text_session")
    data class TextSessionMessage(
        val text: String,
        val senderLabel: String = ""
    ) : SignalingMessage()
}

sealed interface SignalingEvent {
    object Connected : SignalingEvent
    object Disconnected : SignalingEvent
    data class IncomingOffer(val message: SignalingMessage.Offer) : SignalingEvent
    data class IncomingAnswer(val message: SignalingMessage.Answer) : SignalingEvent
    data class IncomingIce(val message: SignalingMessage.IceCandidate) : SignalingEvent
    object IncomingBye : SignalingEvent
    data class IncomingRenegotiateOffer(val message: SignalingMessage.RenegotiateOffer) : SignalingEvent
    data class IncomingRenegotiateAnswer(val message: SignalingMessage.RenegotiateAnswer) : SignalingEvent
    data class IncomingTextMessage(val message: SignalingMessage.TextMessage) : SignalingEvent
    data class IncomingTextSessionMessage(val message: SignalingMessage.TextSessionMessage) : SignalingEvent
}
