package com.p2p.feature.call

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.p2p.core.network.SignalingClient
import com.p2p.core.network.SignalingEvent
import com.p2p.core.network.SignalingMessage
import com.p2p.core.network.SignalingServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ── UI State ──────────────────────────────────────────────────────────────────

enum class TextSessionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

data class TextSessionUiState(
    val state: TextSessionState        = TextSessionState.CONNECTING,
    val messages: List<ChatMessage>    = emptyList(),
    val errorMessage: String?          = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class TextChatViewModel @Inject constructor(
    private val signalingClient: SignalingClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object { private const val TAG = "TextChatViewModel" }

    private val _uiState = MutableStateFlow(TextSessionUiState())
    val uiState: StateFlow<TextSessionUiState> = _uiState.asStateFlow()

    fun connect(peerAddress: String) {
        val wsUrl = "ws://$peerAddress:${SignalingServer.PORT}/signal"
        Timber.tag(TAG).i("Connecting text session to $wsUrl")
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connected -> {
                        _uiState.update { it.copy(state = TextSessionState.CONNECTED) }
                    }
                    is SignalingEvent.IncomingTextSessionMessage -> {
                        val msg = ChatMessage(
                            text        = event.message.text,
                            isMine      = false,
                            senderLabel = event.message.senderLabel,
                        )
                        _uiState.update { it.copy(messages = it.messages + msg) }
                    }
                    is SignalingEvent.Disconnected -> {
                        _uiState.update { it.copy(state = TextSessionState.DISCONNECTED) }
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            signalingClient.connect(wsUrl)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val msg = ChatMessage(text = text.trim(), isMine = true)
        _uiState.update { it.copy(messages = it.messages + msg) }
        viewModelScope.launch {
            signalingClient.send(
                SignalingMessage.TextSessionMessage(
                    text        = text.trim(),
                    senderLabel = "me",
                )
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch { signalingClient.disconnect() }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { signalingClient.disconnect() }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private val GradientHeader = Brush.horizontalGradient(
    colors = listOf(Color(0xFF1A1A3E), Color(0xFF0D2157))
)

private val BubbleGradientMine = Brush.horizontalGradient(
    colors = listOf(Color(0xFF1565C0), Color(0xFF4D94FF))
)

@Composable
fun TextChatScreen(
    peerAddress: String,
    peerLabel: String,
    onClose: () -> Unit,
    onCallPeer: ((address: String) -> Unit)? = null,
    viewModel: TextChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerAddress) {
        viewModel.connect(peerAddress)
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(uiState.messages.lastIndex) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070A))
    ) {
        // ── Gradient Header ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GradientHeader)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    viewModel.disconnect()
                    onClose()
                }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                // Avatar chip
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF4D94FF).copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (peerLabel.isNotBlank()) peerLabel.first().uppercaseChar().toString() else "?",
                        color = Color(0xFF4D94FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = peerLabel.ifBlank { peerAddress },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    when (uiState.state) {
                                        TextSessionState.CONNECTED    -> Color(0xFF00E676)
                                        TextSessionState.CONNECTING   -> Color(0xFFFFCC02)
                                        else                          -> Color(0xFFFF5252)
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = when (uiState.state) {
                                TextSessionState.CONNECTED    -> "Online"
                                TextSessionState.CONNECTING   -> "Connecting…"
                                TextSessionState.DISCONNECTED -> "Offline"
                                TextSessionState.ERROR        -> "Error"
                            },
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // Call button
                if (onCallPeer != null) {
                    IconButton(onClick = { onCallPeer(peerAddress) }) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Call",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // ── Message list ─────────────────────────────────────────────────────
        if (uiState.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No messages yet",
                        color = Color.White.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Send the first message to ${peerLabel.ifBlank { peerAddress }}",
                        color = Color.White.copy(alpha = 0.2f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(uiState.messages) { msg -> TextSessionBubble(msg) }
            }
        }

        // ── Input bar ─────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF101018),
            tonalElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Type a message…", fontSize = 14.sp, color = Color.White.copy(alpha = 0.35f)) },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color(0xFF1A1A26),
                        unfocusedContainerColor = Color(0xFF1A1A26),
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = Color(0xFF4D94FF),
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                AnimatedVisibility(visible = draft.isNotBlank()) {
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(draft)
                            draft = ""
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF4D94FF), Color(0xFF1565C0))
                                ),
                                CircleShape
                            ),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }
    }
}

// ── Chat bubble with timestamp ────────────────────────────────────────────────

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun TextSessionBubble(message: ChatMessage) {
    val align = if (message.isMine) Alignment.End else Alignment.Start
    val timeLabel = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        if (message.isMine) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart    = 18.dp,
                            topEnd      = 18.dp,
                            bottomStart = 18.dp,
                            bottomEnd   = 4.dp,
                        )
                    )
                    .background(BubbleGradientMine)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(message.text, color = Color.White, fontSize = 15.sp)
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart    = 18.dp,
                            topEnd      = 18.dp,
                            bottomStart = 4.dp,
                            bottomEnd   = 18.dp,
                        )
                    )
                    .background(Color(0xFF1A1A2E))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(message.text, color = Color(0xFFECECF4), fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text  = timeLabel,
            color = Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
