package com.p2p.feature.call

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2p.core.media.WebRtcManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

// ── Hilt entry point to access WebRtcManager from a Composable ───────────────
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface WebRtcManagerEntryPoint {
    fun webRtcManager(): WebRtcManager
}

private fun Context.webRtcManager(): WebRtcManager =
    EntryPointAccessors.fromApplication(applicationContext, WebRtcManagerEntryPoint::class.java)
        .webRtcManager()

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun CallScreen(
    peerAddress: String,
    isIncoming: Boolean,
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Start outgoing / incoming handling once on entry
    LaunchedEffect(peerAddress, isIncoming) {
        if (peerAddress.isNotBlank()) {
            if (isIncoming) viewModel.setIncomingCall(peerAddress)
            else            viewModel.startCall(peerAddress)
        }
    }

    // Navigate back when call ends
    LaunchedEffect(uiState.callState) {
        if (uiState.callState == CallState.ENDED) onCallEnded()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        // ── Remote video (full screen background) ────────────────────────────
        RemoteVideoView(uiState = uiState, modifier = Modifier.fillMaxSize())

        // ── Local video PiP (only when video is active) ──────────────────────
        if (uiState.isVideoActive &&
            uiState.callState != CallState.INCOMING &&
            uiState.callState != CallState.IDLE
        ) {
            LocalVideoView(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 120.dp, end = 16.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }

        // ── Status banner ────────────────────────────────────────────────────
        CallStatusBanner(
            state       = uiState.callState,
            peerAddress = uiState.peerAddress,
            modifier    = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
        )

        // ── Chat panel (slide up from bottom) ─────────────────────────────
        AnimatedVisibility(
            visible = uiState.isChatOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit    = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            ChatPanel(
                messages      = uiState.messages,
                onSend        = viewModel::sendTextMessage,
                onClose       = viewModel::toggleChat,
                modifier      = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f),
            )
        }

        // ── Control bar ──────────────────────────────────────────────────────
        if (!uiState.isChatOpen) {
            CallControlBar(
                callState      = uiState.callState,
                isMicMuted     = uiState.isMicMuted,
                isCameraOff    = uiState.isCameraOff,
                isVideoActive  = uiState.isVideoActive,
                onToggleMic    = viewModel::toggleMic,
                onToggleCamera = viewModel::toggleCamera,
                onStartVideo   = viewModel::startVideo,
                onToggleChat   = viewModel::toggleChat,
                onAccept       = viewModel::acceptCall,
                onHangUp       = viewModel::hangUp,
                modifier       = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            )
        }

        // ── Error snackbar ───────────────────────────────────────────────────
        uiState.errorMessage?.let { err ->
            Snackbar(
                modifier       = Modifier.align(Alignment.TopCenter).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

// ── Remote video ──────────────────────────────────────────────────────────────

@Composable
private fun RemoteVideoView(uiState: CallUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { context.webRtcManager() }
    val track   = uiState.remoteVideoTrack

    if (track != null) {
        val renderer = remember(track) {
            SurfaceViewRenderer(context).apply { manager.attachRemoteRenderer(this) }
        }
        DisposableEffect(track) { onDispose { manager.releaseRenderer(renderer) } }
        AndroidView(factory = { renderer }, modifier = modifier)
    } else {
        Box(
            modifier         = modifier.background(Color(0xFF1A1A2E)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Large avatar / icon
                Icon(
                    imageVector        = Icons.Default.Person,
                    contentDescription = "Peer avatar",
                    tint               = Color.White.copy(alpha = 0.4f),
                    modifier           = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text  = uiState.peerAddress,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = when (uiState.callState) {
                        CallState.OUTGOING   -> "Ringing…"
                        CallState.CONNECTING -> "Connecting…"
                        CallState.INCOMING   -> "Incoming call"
                        CallState.CONNECTED  -> "Audio call"
                        else                 -> ""
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── Local video PiP ──────────────────────────────────────────────────────────

@Composable
private fun LocalVideoView(modifier: Modifier = Modifier) {
    val context  = LocalContext.current
    val manager  = remember { context.webRtcManager() }
    val renderer = remember {
        SurfaceViewRenderer(context).also { manager.attachLocalRenderer(it) }
    }
    DisposableEffect(Unit) { onDispose { manager.releaseRenderer(renderer) } }
    AndroidView(factory = { renderer }, modifier = modifier)
}

// ── Status banner ─────────────────────────────────────────────────────────────

@Composable
private fun CallStatusBanner(state: CallState, peerAddress: String, modifier: Modifier = Modifier) {
    val label = when (state) {
        CallState.OUTGOING   -> "Calling $peerAddress…"
        CallState.INCOMING   -> "Incoming from $peerAddress"
        CallState.CONNECTING -> "Connecting…"
        CallState.CONNECTED  -> "● Connected"
        CallState.ENDED      -> "Call ended"
        else                 -> ""
    }
    if (label.isNotEmpty()) {
        Text(
            text      = label,
            modifier  = modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 6.dp),
            color     = if (state == CallState.CONNECTED) Color(0xFF81C784) else Color.White,
            style     = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Control bar ───────────────────────────────────────────────────────────────

@Composable
private fun CallControlBar(
    callState: CallState,
    isMicMuted: Boolean,
    isCameraOff: Boolean,
    isVideoActive: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onStartVideo: () -> Unit,
    onToggleChat: () -> Unit,
    onAccept: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier              = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        if (callState == CallState.INCOMING) {
            // ── Incoming call: Decline / Accept ──────────────────────────────
            CallFab(
                icon           = Icons.Default.CallEnd,
                contentDesc    = "Decline",
                containerColor = Color(0xFFD32F2F),
                size           = 68.dp,
                onClick        = onHangUp,
            )
            CallFab(
                icon           = Icons.Default.Call,
                contentDesc    = "Accept",
                containerColor = Color(0xFF2E7D32),
                size           = 68.dp,
                onClick        = onAccept,
            )
        } else {
            // ── In-call controls ─────────────────────────────────────────────
            // Mic mute
            CallIconButton(
                icon        = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDesc = if (isMicMuted) "Unmute" else "Mute",
                active      = isMicMuted,
                onClick     = onToggleMic,
            )

            // Video escalation — hidden once video is already active
            if (!isVideoActive && (callState == CallState.CONNECTED || callState == CallState.CONNECTING)) {
                CallIconButton(
                    icon        = Icons.Default.Videocam,
                    contentDesc = "Start video",
                    active      = false,
                    tint        = Color(0xFF64B5F6),
                    onClick     = onStartVideo,
                )
            } else if (isVideoActive) {
                // Camera on/off toggle once escalated
                CallIconButton(
                    icon        = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDesc = if (isCameraOff) "Camera off" else "Camera on",
                    active      = isCameraOff,
                    onClick     = onToggleCamera,
                )
            }

            // Text chat
            CallIconButton(
                icon        = Icons.Default.Chat,
                contentDesc = "Open chat",
                active      = false,
                tint        = Color(0xFFFFCC02),
                onClick     = onToggleChat,
            )

            // Hang up
            CallFab(
                icon           = Icons.Default.CallEnd,
                contentDesc    = "End call",
                containerColor = Color(0xFFD32F2F),
                size           = 62.dp,
                onClick        = onHangUp,
            )
        }
    }
}

// ── Reusable icon button ──────────────────────────────────────────────────────

@Composable
private fun CallIconButton(
    icon: ImageVector,
    contentDesc: String,
    active: Boolean,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val bg = if (active) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f)
    IconButton(
        onClick  = onClick,
        modifier = Modifier
            .size(52.dp)
            .background(bg, CircleShape),
    ) {
        Icon(imageVector = icon, contentDescription = contentDesc, tint = tint, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun CallFab(
    icon: ImageVector,
    contentDesc: String,
    containerColor: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    FloatingActionButton(
        onClick        = onClick,
        containerColor = containerColor,
        contentColor   = Color.White,
        shape          = CircleShape,
        modifier       = Modifier.size(size),
    ) {
        Icon(imageVector = icon, contentDescription = contentDesc, modifier = Modifier.size(28.dp))
    }
}

// ── Chat panel ────────────────────────────────────────────────────────────────

@Composable
private fun ChatPanel(
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color(0xFF1C1C2E))
            .padding(bottom = 8.dp),
    ) {
        // Header
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chat", color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close chat", tint = Color.White)
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Message list
        LazyColumn(
            state            = listState,
            modifier         = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding   = PaddingValues(vertical = 8.dp),
        ) {
            items(messages) { msg -> ChatBubble(msg) }
        }

        // Input row
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value          = draft,
                onValueChange  = { draft = it },
                placeholder    = { Text("Type a message…", fontSize = 14.sp) },
                singleLine     = true,
                modifier       = Modifier.weight(1f),
                colors         = TextFieldDefaults.colors(
                    focusedContainerColor   = Color(0xFF2A2A3E),
                    unfocusedContainerColor = Color(0xFF2A2A3E),
                    focusedTextColor        = Color.White,
                    unfocusedTextColor      = Color.White,
                    cursorColor             = Color(0xFF64B5F6),
                    focusedIndicatorColor   = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(20.dp),
            )
            IconButton(
                onClick  = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
                modifier = Modifier
                    .size(46.dp)
                    .background(Color(0xFF1565C0), CircleShape),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val align = if (message.isMine) Alignment.End else Alignment.Start
    val bg    = if (message.isMine) Color(0xFF1565C0) else Color(0xFF2D2D3E)

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart    = 16.dp,
                        topEnd      = 16.dp,
                        bottomStart = if (message.isMine) 16.dp else 4.dp,
                        bottomEnd   = if (message.isMine) 4.dp else 16.dp,
                    )
                )
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(message.text, color = Color.White, fontSize = 15.sp)
        }
    }
}
