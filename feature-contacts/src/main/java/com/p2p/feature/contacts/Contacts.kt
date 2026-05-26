package com.p2p.feature.contacts

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.p2p.core.network.TailscaleDetector
import com.p2p.core.network.TailscaleScanner
import com.p2p.core.network.DiscoveredDevice
import com.p2p.core.storage.PeerDao
import com.p2p.core.storage.PeerEntity
import com.p2p.feature.call.CallHistorySection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── Colours (local aliases) ───────────────────────────────────────────────────

private val NeonBlue   = Color(0xFF4D94FF)
private val NeonPurple = Color(0xFFC580FF)
private val AccentGreen = Color(0xFF00E676)
private val ErrorRed   = Color(0xFFFF5252)
private val BgDeep     = Color(0xFF07070A)
private val SurfaceCard = Color(0xFF101018)
private val SurfaceVariant = Color(0xFF1A1A26)

private val GradientPrimary = Brush.horizontalGradient(listOf(NeonBlue, NeonPurple))
private val GradientHeader  = Brush.verticalGradient(
    listOf(Color(0xFF0D1B3E), Color(0xFF07070A))
)

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

    init { startTailscaleScan() }

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
    onTextPeer: (address: String, label: String) -> Unit,
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

    var showAddDialog  by remember { mutableStateOf(false) }
    var showRoomDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { !listState.isScrollInProgress } }
    val clipboard = LocalClipboardManager.current

    Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        LazyColumn(
            state   = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ── Gradient header ───────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GradientHeader)
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "SecureComm",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    "End-to-end encrypted P2P",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                )
                            }
                            // Status dot
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(AccentGreen, CircleShape)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // My IP card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0A1729))
                                .border(
                                    width = 1.dp,
                                    brush = Brush.horizontalGradient(
                                        listOf(NeonBlue.copy(alpha = 0.4f), NeonPurple.copy(alpha = 0.4f))
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.WifiTethering,
                                    contentDescription = null,
                                    tint = NeonBlue,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = myAddrs.ifBlank { "Not detected – is Tailscale running?" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF90CAF9),
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { clipboard.setText(AnnotatedString(myAddrs)) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy IP",
                                        tint = NeonBlue.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Tailscale section header ──────────────────────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionLabel("Auto-Discovered Devices")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Room: $activeRoomName",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { showRoomDialog = true },
                                modifier = Modifier.size(16.dp),
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Room",
                                    tint = NeonBlue,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Deep",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 2.dp),
                        )
                        Switch(
                            checked = deepScanEnabled,
                            onCheckedChange = { viewModel.setDeepScanEnabled(it) },
                            modifier = Modifier.scale(0.7f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonBlue,
                                checkedTrackColor = NeonBlue.copy(alpha = 0.3f),
                            )
                        )
                        IconButton(onClick = { viewModel.startTailscaleScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = NeonBlue)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Tailscale body ────────────────────────────────────────────────
            if (isScanning) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceCard)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = NeonBlue,
                                strokeWidth = 2.dp
                            )
                            Text(
                                "Scanning Tailscale network…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            } else if (!scanError.isNullOrBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ErrorRed.copy(alpha = 0.1f))
                            .border(1.dp, ErrorRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Text(
                            "Scan Failed",
                            style = MaterialTheme.typography.titleSmall,
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = scanError ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.startTailscaleScan() },
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text("Retry", fontSize = 13.sp) }
                    }
                }
            } else if (discoveredDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceCard)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No Active Devices Found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Ensure both devices are on Tailscale and using the same room",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.25f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                items(discoveredDevices, key = { it.ip }) { device ->
                    TailscaleDeviceCard(
                        device  = device,
                        onDial  = { onDialPeer(device.ip) },
                        onText  = { onTextPeer(device.ip, device.name) },
                        onSave  = { viewModel.saveDiscoveredDevice(device) },
                    )
                }
            }

            // ── Saved peers header ────────────────────────────────────────────
            item {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Saved Contacts")
                    if (peers.isNotEmpty()) {
                        Text(
                            "${peers.size} contact${if (peers.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.4f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Saved peers body ──────────────────────────────────────────────
            if (peers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceCard)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = NeonBlue.copy(alpha = 0.3f),
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No saved contacts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap + to add an IP contact manually",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.25f),
                            )
                        }
                    }
                }
            } else {
                items(peers, key = { it.id }) { peer ->
                    PeerCard(
                        peer     = peer,
                        onDial   = { onDialPeer(peer.address) },
                        onText   = { onTextPeer(peer.address, peer.label) },
                        onDelete = { viewModel.deletePeer(peer) },
                    )
                }
            }

            // ── Call History section ───────────────────────────────────────────
            item {
                Spacer(Modifier.height(20.dp))
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    CallHistorySection(onCallPeer = { address -> onDialPeer(address) })
                }
            }

            item { Spacer(Modifier.height(100.dp)) }
        }

        // ── Extended FAB ──────────────────────────────────────────────────────
        ExtendedFloatingActionButton(
            onClick = { showAddDialog = true },
            expanded = fabExpanded,
            icon = {
                Icon(Icons.Default.Add, contentDescription = "Add contact")
            },
            text = { Text("Add Contact") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .navigationBarsPadding(),
            containerColor = NeonBlue,
            contentColor   = Color.White,
        )
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showAddDialog) {
        AddPeerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, address ->
                viewModel.addPeer(label, address)
                showAddDialog = false
            }
        )
    }

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

// ── Section label ─────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .background(GradientPrimary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── Tailscale discovered device card ─────────────────────────────────────────

@Composable
private fun TailscaleDeviceCard(
    device: DiscoveredDevice,
    onDial: () -> Unit,
    onText: () -> Unit,
    onSave: () -> Unit,
) {
    var isSaved by remember { mutableStateOf(false) }

    ContactCard(
        label           = device.name,
        address         = device.ip,
        isVerified      = false,
        onDial          = onDial,
        onText          = onText,
        trailingContent = {
            IconButton(
                onClick  = { onSave(); isSaved = true },
                enabled  = !isSaved,
                modifier = Modifier.size(36.dp),
            ) {
                AnimatedContent(
                    targetState = isSaved,
                    transitionSpec = { scaleIn() togetherWith scaleOut() }
                ) { saved ->
                    Icon(
                        imageVector        = if (saved) Icons.Default.Check else Icons.Default.BookmarkAdd,
                        contentDescription = if (saved) "Saved" else "Save contact",
                        tint               = if (saved) AccentGreen else NeonBlue,
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }
        }
    )
}

// ── Saved peer card ───────────────────────────────────────────────────────────

@Composable
private fun PeerCard(
    peer: PeerEntity,
    onDial: () -> Unit,
    onText: () -> Unit,
    onDelete: () -> Unit,
) {
    ContactCard(
        label           = peer.label,
        address         = peer.address,
        isVerified      = peer.publicKeyBase64 != null,
        onDial          = onDial,
        onText          = onText,
        trailingContent = {
            IconButton(
                onClick  = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint    = ErrorRed.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    )
}

// ── Shared contact card ───────────────────────────────────────────────────────

@Composable
private fun ContactCard(
    label: String,
    address: String,
    isVerified: Boolean,
    onDial: () -> Unit,
    onText: () -> Unit,
    trailingContent: @Composable () -> Unit,
) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border    = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.06f),
        ),
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(NeonBlue.copy(alpha = 0.3f), NeonPurple.copy(alpha = 0.15f))
                        ),
                        CircleShape,
                    )
                    .border(1.dp, NeonBlue.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = if (label.isNotBlank()) label.first().uppercaseChar().toString() else "?",
                    color = NeonBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text  = address,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
                if (isVerified) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = "Verified",
                            tint = AccentGreen,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "Verified",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // ── Action buttons: Call + Text ───────────────────────────────────

            // Text / message
            IconButton(
                onClick  = onText,
                modifier = Modifier
                    .size(36.dp)
                    .background(NeonPurple.copy(alpha = 0.1f), CircleShape),
            ) {
                Icon(
                    Icons.Default.Message,
                    contentDescription = "Text",
                    tint = NeonPurple,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            // Call
            IconButton(
                onClick  = onDial,
                modifier = Modifier
                    .size(36.dp)
                    .background(NeonBlue.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Call",
                    tint = NeonBlue,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.width(4.dp))

            trailingContent()
        }
    }
}

// ── Add peer dialog ───────────────────────────────────────────────────────────

@Composable
private fun AddPeerDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, address: String) -> Unit,
) {
    var label   by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A26),
        title   = {
            Text(
                "Add Contact",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Display Name") },
                    placeholder   = { Text("e.g. Alice") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = dialogTextFieldColors(),
                )
                OutlinedTextField(
                    value         = address,
                    onValueChange = { address = it },
                    label         = { Text("IP / Tailscale Hostname") },
                    placeholder   = { Text("100.64.x.x or alice.tail…") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = dialogTextFieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onConfirm(label, address) },
                enabled  = label.isNotBlank() && address.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                shape    = RoundedCornerShape(8.dp),
            ) { Text("Add", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.5f))
            }
        },
    )
}

// ── Room config dialog ────────────────────────────────────────────────────────

@Composable
private fun RoomConfigDialog(
    currentRoom: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var room by remember { mutableStateOf(currentRoom ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A26),
        title = {
            Text(
                "Configure Room",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text  = "Enter a shared room name on both devices to pair over Tailscale when auto-detection fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
                OutlinedTextField(
                    value         = room,
                    onValueChange = { room = it },
                    label         = { Text("Room Name / Pairing Code") },
                    placeholder   = { Text("e.g. family") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = dialogTextFieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(room.trim().ifEmpty { null }) },
                colors  = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                shape   = RoundedCornerShape(8.dp),
            ) { Text("Save", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(null) }) {
                Text("Use Auto", color = Color.White.copy(alpha = 0.5f))
            }
        }
    )
}

// ── Dialog text field colours helper ─────────────────────────────────────────

@Composable
private fun dialogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor    = Color.White,
    unfocusedTextColor  = Color.White,
    focusedBorderColor  = NeonBlue,
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    cursorColor         = NeonBlue,
    focusedLabelColor   = NeonBlue,
    unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
)
