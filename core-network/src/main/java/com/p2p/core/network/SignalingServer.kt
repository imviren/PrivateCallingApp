package com.p2p.core.network

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalingServer @Inject constructor() {

    companion object {
        const val PORT = 55500
        private const val TAG = "SignalingServer"
    }

    private var serverEngine: NettyApplicationEngine? = null
    private val _events = MutableSharedFlow<SignalingEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private var activeSession: DefaultWebSocketSession? = null

    var remoteHost: String = ""
        private set

    var pendingOffer: SignalingMessage.Offer? = null
        private set

    private val pendingIceCandidates = mutableListOf<SignalingMessage.IceCandidate>()

    fun getAndClearPendingIceCandidates(): List<SignalingMessage.IceCandidate> {
        return synchronized(pendingIceCandidates) {
            val list = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
            list
        }
    }

    fun clearPendingCall() {
        pendingOffer = null
        remoteHost = ""
        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun start() {
        if (serverEngine != null) {
            Timber.tag(TAG).d("Server is already running.")
            return
        }

        Timber.tag(TAG).i("Starting signaling server on port $PORT...")
        serverEngine = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(json)
                pingPeriodMillis = 15000L
                timeoutMillis = 30000L
            }
            routing {
                webSocket("/signal") {
                    val host = call.request.origin.remoteHost
                    Timber.tag(TAG).i("Client connected from $host")
                    remoteHost = host
                    activeSession = this
                    _events.tryEmit(SignalingEvent.Connected)

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Timber.tag(TAG).v("Received raw message: %s", text)
                                try {
                                    val message = json.decodeFromString<SignalingMessage>(text)
                                    val event = when (message) {
                                        is SignalingMessage.Offer -> {
                                            pendingOffer = message
                                            SignalingEvent.IncomingOffer(message)
                                        }
                                        is SignalingMessage.Answer -> SignalingEvent.IncomingAnswer(message)
                                        is SignalingMessage.IceCandidate -> {
                                            synchronized(pendingIceCandidates) {
                                                pendingIceCandidates.add(message)
                                            }
                                            SignalingEvent.IncomingIce(message)
                                        }
                                        is SignalingMessage.Bye -> SignalingEvent.IncomingBye
                                        is SignalingMessage.RenegotiateOffer ->
                                            SignalingEvent.IncomingRenegotiateOffer(message)
                                        is SignalingMessage.RenegotiateAnswer ->
                                            SignalingEvent.IncomingRenegotiateAnswer(message)
                                        is SignalingMessage.TextMessage ->
                                            SignalingEvent.IncomingTextMessage(message)
                                    }
                                    _events.tryEmit(event)
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Error parsing signaling message")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "WebSocket session exception")
                    } finally {
                        Timber.tag(TAG).i("Client disconnected")
                        activeSession = null
                        _events.tryEmit(SignalingEvent.Disconnected)
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        Timber.tag(TAG).i("Stopping signaling server...")
        serverEngine?.stop(1000, 2000)
        serverEngine = null
        activeSession = null
    }

    suspend fun send(message: SignalingMessage) {
        val session = activeSession
        if (session != null) {
            try {
                val text = json.encodeToString(message)
                Timber.tag(TAG).v("Sending message: %s", text)
                session.send(text)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error sending signaling message")
            }
        } else {
            Timber.tag(TAG).w("No active session to send message: $message")
        }
    }
}
