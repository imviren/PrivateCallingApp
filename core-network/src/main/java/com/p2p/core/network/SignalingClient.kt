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
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

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

    @Synchronized
    fun connect(wsUrl: String) {
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            Timber.tag(TAG).e("Invalid WebSocket URL: %s", wsUrl)
            _events.tryEmit(SignalingEvent.Disconnected)
            return
        }

        val currentJob = connectionJob
        if (currentJob != null && !currentJob.isCompleted) {
            Timber.tag(TAG).d("Already connecting or connected to %s", wsUrl)
            return
        }

        Timber.tag(TAG).i("Connecting to signaling server at %s...", wsUrl)
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
                        when (e) {
                            is kotlinx.coroutines.CancellationException -> {
                                Timber.tag(TAG).i("Connection cancelled by caller")
                                throw e
                            }
                            else -> {
                                attempt++
                                Timber.tag(TAG).w(e, "Signaling connection attempt %d/%d failed", attempt, maxRetries)
                                if (attempt < maxRetries) {
                                    val backoffMs = (1000L shl (attempt - 1)).coerceAtMost(30000L)
                                    Timber.tag(TAG).i("Retrying in %dms...", backoffMs)
                                    delay(backoffMs)
                                }
                            }
                        }
                    }
                }
            } finally {
                Timber.tag(TAG).i("Connection closed")
                session = null
                connectionJob = null
                try {
                    _events.tryEmit(SignalingEvent.Disconnected)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error emitting Disconnected event")
                }
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
        Timber.tag(TAG).i("Shutting down SignalingClient connections...")
        try {
            connectionJob?.cancel()
            connectionJob = null
            session = null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during shutdown")
        }
    }
}
