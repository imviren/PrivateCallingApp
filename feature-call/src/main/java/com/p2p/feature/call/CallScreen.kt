package com.p2p.feature.call

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2p.core.media.WebRtcManager
import dagger.hilt.android.EntryPointAccessors
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
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Start outgoing call once on entry if an address was passed
    LaunchedEffect(peerAddress) {
        if (peerAddress.isNotBlank()) {
            viewModel.startCall(peerAddress)
        }
    }

    // Navigate back when call ends
    LaunchedEffect(uiState.callState) {
        if (uiState.callState == CallState.ENDED) {
            onCallEnded()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        // ── Remote video (full screen background) ─────────────────────────────
        RemoteVideoView(
            uiState  = uiState,
            modifier = Modifier.fillMaxSize()
        )

        // ── Local video (PiP overlay, bottom-right) ───────────────────────────
        LocalVideoView(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 100.dp, end = 16.dp)
                .size(width = 110.dp, height = 160.dp)
        )

        // ── Status banner ─────────────────────────────────────────────────────
        CallStatusBanner(
            state       = uiState.callState,
            peerAddress = uiState.peerAddress,
            modifier    = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
        )

        // ── Control bar ───────────────────────────────────────────────────────
        CallControlBar(
            isMicMuted  = uiState.isMicMuted,
            isCameraOff = uiState.isCameraOff,
            onToggleMic    = viewModel::toggleMic,
            onToggleCamera = viewModel::toggleCamera,
            onHangUp       = viewModel::hangUp,
            modifier       = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        )

        // ── Error snackbar ────────────────────────────────────────────────────
        uiState.errorMessage?.let { err ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

// ── Remote video ──────────────────────────────────────────────────────────────

@Composable
private fun RemoteVideoView(
    uiState: CallUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember { context.webRtcManager() }

    val track = uiState.remoteVideoTrack
    if (track != null) {
        val renderer = remember(track) {
            SurfaceViewRenderer(context).apply {
                manager.attachRemoteRenderer(this)
            }
        }
        DisposableEffect(track) {
            onDispose { manager.releaseRenderer(renderer) }
        }
        AndroidView(factory = { renderer }, modifier = modifier)
    } else {
        // Placeholder while no remote video
        Box(
            modifier  = modifier.background(Color(0xFF1A1A2E)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = when (uiState.callState) {
                        CallState.OUTGOING    -> "Ringing…"
                        CallState.CONNECTING  -> "Connecting…"
                        CallState.INCOMING    -> "Incoming call"
                        else                  -> ""
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = uiState.peerAddress,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── Local video ───────────────────────────────────────────────────────────────

@Composable
private fun LocalVideoView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { context.webRtcManager() }

    val renderer = remember {
        SurfaceViewRenderer(context).also { manager.attachLocalRenderer(it) }
    }
    DisposableEffect(Unit) {
        onDispose { manager.releaseRenderer(renderer) }
    }

    AndroidView(
        factory  = { renderer },
        modifier = modifier
    )
}

// ── Status banner ─────────────────────────────────────────────────────────────

@Composable
private fun CallStatusBanner(
    state: CallState,
    peerAddress: String,
    modifier: Modifier = Modifier,
) {
    val label = when (state) {
        CallState.OUTGOING   -> "Calling $peerAddress…"
        CallState.INCOMING   -> "Incoming from $peerAddress"
        CallState.CONNECTING -> "ICE negotiation…"
        CallState.CONNECTED  -> "● Connected"
        CallState.ENDED      -> "Call ended"
        else                 -> ""
    }
    if (label.isNotEmpty()) {
        Text(
            text     = label,
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 6.dp),
            color    = if (state == CallState.CONNECTED) Color(0xFF81C784) else Color.White,
            style    = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Control bar ───────────────────────────────────────────────────────────────

@Composable
private fun CallControlBar(
    isMicMuted: Boolean,
    isCameraOff: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mic toggle
        ControlButton(
            label      = if (isMicMuted) "Unmute" else "Mute",
            tint       = if (isMicMuted) Color(0xFFEF9A9A) else Color.White,
            onClick    = onToggleMic,
        )

        // Hang up
        FloatingActionButton(
            onClick            = onHangUp,
            containerColor     = Color(0xFFD32F2F),
            contentColor       = Color.White,
            shape              = CircleShape,
            modifier           = Modifier.size(68.dp),
        ) {
            Text("End", style = MaterialTheme.typography.labelLarge)
        }

        // Camera toggle
        ControlButton(
            label      = if (isCameraOff) "Cam On" else "Cam Off",
            tint       = if (isCameraOff) Color(0xFFEF9A9A) else Color.White,
            onClick    = onToggleCamera,
        )
    }
}

@Composable
private fun ControlButton(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        border  = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(tint)
        ),
    ) {
        Text(label, color = tint)
    }
}
