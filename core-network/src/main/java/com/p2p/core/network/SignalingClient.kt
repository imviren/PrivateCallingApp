package com.p2p.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalingClient @Inject constructor() {

    companion object {
        private const val TAG = "SignalingClient"
    }

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private var session: DefaultWebSocketSession? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun connect(wsUrl: String) {
        if (connectionJob != null) {
            Timber.tag(TAG).d("Already connecting or connected to $wsUrl")
            return
        }

        Timber.tag(TAG).i("Connecting to signaling server at $wsUrl...")
        connectionJob = scope.launch {
            try {
                session = client.webSocketSession(wsUrl)
                Timber.tag(TAG).i("Connected successfully to $wsUrl")
                _events.emit(SignalingEvent.Connected)

                val currentSession = session ?: return@launch
                for (frame in currentSession.incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        Timber.tag(TAG).d("Received raw message: $text")
                        try {
                            val message = json.decodeFromString<SignalingMessage>(text)
                            val event = when (message) {
                                is SignalingMessage.Offer -> SignalingEvent.IncomingOffer(message)
                                is SignalingMessage.Answer -> SignalingEvent.IncomingAnswer(message)
                                is SignalingMessage.IceCandidate -> SignalingEvent.IncomingIce(message)
                                is SignalingMessage.Bye -> SignalingEvent.IncomingBye
                            }
                            _events.emit(event)
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error parsing signaling message")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Signaling connection error")
            } finally {
                Timber.tag(TAG).i("Connection closed")
                session = null
                connectionJob = null
                _events.emit(SignalingEvent.Disconnected)
            }
        }
    }

    suspend fun disconnect() {
        Timber.tag(TAG).i("Disconnecting from server...")
        try {
            send(SignalingMessage.Bye)
        } catch (e: Exception) {
            // Ignore
        }
        connectionJob?.cancel()
        connectionJob = null
        session = null
    }

    suspend fun send(message: SignalingMessage) {
        val currentSession = session
        if (currentSession != null) {
            try {
                val text = json.encodeToString(message)
                Timber.tag(TAG).d("Sending message: $text")
                currentSession.send(text)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error sending signaling message")
            }
        } else {
            Timber.tag(TAG).w("No active session to send message: $message")
        }
    }
}
