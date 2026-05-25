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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + scopeJob)

    private val _events = MutableSharedFlow<SignalingEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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
            var attempt = 0
            val maxRetries = 3
            var connected = false
            try {
                while (attempt < maxRetries && !connected) {
                    try {
                        session = kotlinx.coroutines.withTimeout(10000L) {
                            client.webSocketSession(wsUrl)
                        }
                        Timber.tag(TAG).i("Connected successfully to $wsUrl")
                        _events.emit(SignalingEvent.Connected)
                        connected = true

                        val currentSession = session ?: break
                        for (frame in currentSession.incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Timber.tag(TAG).v("Received raw message: %s", text)
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        attempt++
                        Timber.tag(TAG).w(e, "Signaling connection attempt $attempt failed")
                        if (attempt < maxRetries) {
                            val backoffMs = 1000L * (1 shl (attempt - 1))
                            Timber.tag(TAG).i("Retrying in ${backoffMs}ms...")
                            delay(backoffMs)
                        }
                    }
                }
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
                Timber.tag(TAG).v("Sending message: %s", text)
                currentSession.send(text)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error sending signaling message")
            }
        } else {
            Timber.tag(TAG).w("No active session to send message: $message")
        }
    }
    fun shutdown() {
        Timber.tag(TAG).i("Shutting down SignalingClient scope...")
        connectionJob?.cancel()
        connectionJob = null
        session = null
        scopeJob.cancel()
        scopeJob = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + scopeJob)
    }
}
