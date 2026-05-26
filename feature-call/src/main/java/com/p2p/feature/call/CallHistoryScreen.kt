package com.p2p.feature.call

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.p2p.core.storage.CallLogDao
import com.p2p.core.storage.CallLogEntity
import com.p2p.core.storage.CallType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val callLogDao: CallLogDao,
) : ViewModel() {

    val logs: StateFlow<List<CallLogEntity>> = callLogDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(log: CallLogEntity) {
        viewModelScope.launch { callLogDao.delete(log) }
    }

    fun clearAll() {
        viewModelScope.launch { callLogDao.deleteAll() }
    }
}

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun CallHistorySection(
    onCallPeer: (address: String) -> Unit,
    viewModel: CallHistoryViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var isExpanded by remember { mutableStateOf(false) }
    var showConfirmClear by remember { mutableStateOf(false) }

    Column {
        // ── Section header ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
            ) {
                // Gradient accent line
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF4D94FF), Color(0xFFC580FF))
                            ),
                            RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Call History",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5F5FA),
                )
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF4D94FF).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = logs.size.toString(),
                            color = Color(0xFF4D94FF),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Row {
                if (logs.isNotEmpty() && isExpanded) {
                    IconButton(
                        onClick = { showConfirmClear = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear all history",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                TextButton(onClick = { isExpanded = !isExpanded }) {
                    Text(
                        text = if (isExpanded) "Hide" else "Show",
                        color = Color(0xFF4D94FF),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // ── Content ──────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (logs.isEmpty()) {
                    CallHistoryEmpty()
                } else {
                    logs.take(25).forEach { log ->
                        CallHistoryRow(
                            log     = log,
                            onCall  = { onCallPeer(log.peerAddress) },
                            onDelete = { viewModel.delete(log) },
                        )
                    }
                }
            }
        }
    }

    // Clear all confirmation
    if (showConfirmClear) {
        AlertDialog(
            onDismissRequest = { showConfirmClear = false },
            containerColor   = Color(0xFF1A1A26),
            title = { Text("Clear History", color = Color.White) },
            text  = { Text("Delete all call logs?", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showConfirmClear = false
                }) {
                    Text("Clear", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = false }) {
                    Text("Cancel", color = Color(0xFF4D94FF))
                }
            }
        )
    }
}

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun CallHistoryEmpty() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CallMissed,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No calls yet",
                color = Color.White.copy(alpha = 0.3f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Row item ──────────────────────────────────────────────────────────────────

private val dateFormatter = SimpleDateFormat("MMM d", Locale.getDefault())
private val timeFormatter2 = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun CallHistoryRow(
    log: CallLogEntity,
    onCall: () -> Unit,
    onDelete: () -> Unit,
) {
    val (typeIcon, typeColor, typeLabel) = when (log.callType) {
        CallType.OUTGOING -> Triple(Icons.Default.CallMade,     Color(0xFF4D94FF), "Outgoing")
        CallType.INCOMING -> Triple(Icons.Default.CallReceived, Color(0xFF00E676), "Incoming")
        CallType.MISSED   -> Triple(Icons.Default.CallMissed,   Color(0xFFFF5252), "Missed")
    }

    val now   = System.currentTimeMillis()
    val delta = now - log.startedAt
    val timeLabel = when {
        delta < 60_000L          -> "Just now"
        delta < 3_600_000L       -> "${delta / 60_000L}m ago"
        delta < 86_400_000L      -> "${delta / 3_600_000L}h ago"
        delta < 7 * 86_400_000L  -> "${delta / 86_400_000L}d ago"
        else                     -> dateFormatter.format(Date(log.startedAt))
    }

    val durationLabel = if (log.durationSeconds > 0L) {
        val m = log.durationSeconds / 60
        val s = log.durationSeconds % 60
        if (m > 0) "${m}m ${s}s" else "${s}s"
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF101018)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Type icon circle
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(typeColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = typeLabel,
                    tint = typeColor,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Text column
            Column(Modifier.weight(1f)) {
                Text(
                    text = log.peerLabel.ifBlank { log.peerAddress },
                    color = Color(0xFFF5F5FA),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeLabel,
                        color = typeColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (durationLabel.isNotEmpty()) {
                        Text(
                            text = " · $durationLabel",
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = " · $timeLabel",
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Call back button
            IconButton(
                onClick = onCall,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Call back",
                    tint = Color(0xFF4D94FF),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
