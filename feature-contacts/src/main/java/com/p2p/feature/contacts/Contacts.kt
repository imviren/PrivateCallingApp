package com.p2p.feature.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.p2p.core.network.TailscaleDetector
import com.p2p.core.storage.PeerDao
import com.p2p.core.storage.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val tailscaleDetector: TailscaleDetector,
) : ViewModel() {

    val peers: StateFlow<List<PeerEntity>> = peerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val localAddressSummary: StateFlow<String> = flow {
        emit(tailscaleDetector.getAddresses().summary())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Detecting address…")

    fun addPeer(label: String, address: String) {
        if (label.isBlank() || address.isBlank()) return
        viewModelScope.launch {
            peerDao.upsert(PeerEntity(label = label.trim(), address = address.trim()))
        }
    }

    fun deletePeer(peer: PeerEntity) {
        viewModelScope.launch { peerDao.delete(peer) }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ContactsScreen(
    onDialPeer: (address: String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val peers   by viewModel.peers.collectAsStateWithLifecycle()
    val myAddrs by viewModel.localAddressSummary.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add peer")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ── Local address card ────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text(
                text  = "Your addresses",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text     = myAddrs.ifBlank { "Not detected – is Tailscale running?" },
                    modifier = Modifier.padding(12.dp),
                    style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text  = "Peers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))

            if (peers.isEmpty()) {
                Text(
                    "No peers yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(peers, key = { it.id }) { peer ->
                        PeerRow(
                            peer     = peer,
                            onDial   = { onDialPeer(peer.address) },
                            onDelete = { viewModel.deletePeer(peer) },
                        )
                    }
                }
            }
        }
    }

    // ── Add peer dialog ───────────────────────────────────────────────────────
    if (showAddDialog) {
        AddPeerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, address ->
                viewModel.addPeer(label, address)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun PeerRow(
    peer: com.p2p.core.storage.PeerEntity,
    onDial: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape    = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(peer.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    peer.address,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (peer.publicKeyBase64 != null) {
                    Text(
                        "✓ Verified",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDial) {
                Icon(Icons.Default.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddPeerDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, address: String) -> Unit,
) {
    var label   by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Add peer") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Label (e.g. Alice)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value         = address,
                    onValueChange = { address = it },
                    label         = { Text("IP or Tailscale hostname") },
                    placeholder   = { Text("100.64.x.x or alice.tail-…ts.net") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label, address) },
                enabled = label.isNotBlank() && address.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
