package com.p2p.feature.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.p2p.core.network.TailscaleDetector
import com.p2p.core.network.TailscaleScanner
import com.p2p.core.network.DiscoveredDevice
import com.p2p.core.storage.PeerDao
import com.p2p.core.storage.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val tailscaleDetector: TailscaleDetector,
    private val tailscaleScanner: TailscaleScanner,
) : ViewModel() {

    val peers: StateFlow<List<PeerEntity>> = peerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val localAddressSummary: StateFlow<String> = flow {
        while (true) {
            emit(tailscaleDetector.getAddresses().summary())
            kotlinx.coroutines.delay(5000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Detecting address…")

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val _deepScanEnabled = MutableStateFlow(false)
    val deepScanEnabled: StateFlow<Boolean> = _deepScanEnabled.asStateFlow()

    private val _customRoomName = MutableStateFlow(tailscaleScanner.customRoomName)
    val customRoomName: StateFlow<String?> = _customRoomName.asStateFlow()

    val activeRoomName: StateFlow<String> = flow {
        while (true) {
            val room = tailscaleScanner.getActiveRoom() ?: "No room (check connection)"
            val isCustom = !tailscaleScanner.customRoomName.isNullOrBlank()
            emit(if (isCustom) "$room (Custom)" else room)
            kotlinx.coroutines.delay(5000L)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Detecting room…")

    init {
        startTailscaleScan()
    }

    fun setDeepScanEnabled(enabled: Boolean) {
        _deepScanEnabled.value = enabled
        startTailscaleScan()
    }

    fun updateCustomRoomName(name: String?) {
        val trimmed = name?.trim()
        tailscaleScanner.customRoomName = trimmed
        _customRoomName.value = trimmed
        startTailscaleScan()
    }

    fun startTailscaleScan() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanError.value = null
            Timber.tag("ContactsViewModel").d("startTailscaleScan() triggered. deepScan=%b", _deepScanEnabled.value)
            try {
                tailscaleScanner.scan(_deepScanEnabled.value).collect { devices ->
                    Timber.tag("ContactsViewModel").d("startTailscaleScan() got %d devices from scanner", devices.size)
                    _discoveredDevices.value = devices
                }
            } catch (e: Exception) {
                Timber.tag("ContactsViewModel").e(e, "Failed scanning Tailscale network")
                _scanError.value = e.message ?: "Failed scanning Tailscale network"
                _discoveredDevices.value = emptyList()
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun saveDiscoveredDevice(device: DiscoveredDevice) {
        viewModelScope.launch {
            peerDao.upsert(PeerEntity(label = device.name, address = device.ip))
        }
    }

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
    val peers             by viewModel.peers.collectAsStateWithLifecycle()
    val myAddrs           by viewModel.localAddressSummary.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val isScanning       by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanError         by viewModel.scanError.collectAsStateWithLifecycle()
    val deepScanEnabled   by viewModel.deepScanEnabled.collectAsStateWithLifecycle()
    val customRoomName    by viewModel.customRoomName.collectAsStateWithLifecycle()
    val activeRoomName    by viewModel.activeRoomName.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showRoomDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add peer")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Local address card ────────────────────────────────────────────
            item {
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
            }

            // ── Tailscale Section Header ──────────────────────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = "Tailscale Devices (Auto-Discovered)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Active Room: $activeRoomName",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { showRoomDialog = true },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Room",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Deep Scan",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = deepScanEnabled,
                            onCheckedChange = { viewModel.setDeepScanEnabled(it) },
                            thumbContent = null,
                            modifier = Modifier.scale(0.8f)
                        )
                        IconButton(onClick = { viewModel.startTailscaleScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Tailscale devices")
                        }
                    }
                }
            }

            // ── Tailscale Section Body ────────────────────────────────────────
            if (isScanning) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = "Scanning Tailscale network...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!scanError.isNullOrBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Scan Failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = scanError ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.startTailscaleScan() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            } else if (discoveredDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No Active Devices Found",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Make sure both devices are connected to Tailscale, have this app open, and are using the same room.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(discoveredDevices, key = { it.ip }) { device ->
                    TailscaleDiscoveredDeviceRow(
                        device = device,
                        onDial = { onDialPeer(device.ip) },
                        onSave = { viewModel.saveDiscoveredDevice(device) }
                    )
                }
            }

            // ── Peers Header ──────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text  = "Saved Peers",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }

            // ── Peers Body ────────────────────────────────────────────────────
            if (peers.isEmpty()) {
                item {
                    Text(
                        "No peers yet. Tap + to add one manually.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(peers, key = { it.id }) { peer ->
                    PeerRow(
                        peer     = peer,
                        onDial   = { onDialPeer(peer.address) },
                        onDelete = { viewModel.deletePeer(peer) },
                    )
                }
            }
            
            item {
                Spacer(Modifier.height(80.dp)) // Avoid content covered by FAB
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

    // ── Room Config dialog ──────────────────────────────────────────────────
    if (showRoomDialog) {
        RoomConfigDialog(
            currentRoom = customRoomName,
            onDismiss = { showRoomDialog = false },
            onConfirm = { name ->
                viewModel.updateCustomRoomName(name)
                showRoomDialog = false
            }
        )
    }
}

@Composable
private fun TailscaleDiscoveredDeviceRow(
    device: DiscoveredDevice,
    onDial: () -> Unit,
    onSave: () -> Unit,
) {
    var isSaved by remember { mutableStateOf(false) }

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
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.ip,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDial) {
                Icon(Icons.Default.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(
                onClick = {
                    onSave()
                    isSaved = true
                },
                enabled = !isSaved
            ) {
                Icon(
                    imageVector = if (isSaved) Icons.Default.Check else Icons.Default.Save,
                    contentDescription = "Save contact",
                    tint = if (isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
            }
        }
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
        title   = { Text("Add peer manually") },
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

@Composable
private fun RoomConfigDialog(
    currentRoom: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var room by remember { mutableStateOf(currentRoom ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Room Name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "If automatic pairing fails or devices are on different subnets, enter a shared room name (e.g. 'home') on both devices to pair them securely over the VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Room Name / Pairing Code") },
                    placeholder = { Text("e.g. family") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(room.trim().ifEmpty { null })
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onConfirm(null)
                }
            ) { Text("Use Auto") }
        }
    )
}
