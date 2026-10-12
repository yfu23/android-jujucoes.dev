package com.qrcodejuju.app.ui

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qrcodejuju.app.data.AppDatabase
import com.qrcodejuju.app.data.ConflictChoice
import com.qrcodejuju.app.data.SavedCode
import com.qrcodejuju.app.data.SyncCrypto
import com.qrcodejuju.app.data.SyncManager
import com.qrcodejuju.app.data.SyncOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** How long to wait after a local edit before pushing it to Drive, so rapid edits coalesce into one upload. */
private const val PUSH_DEBOUNCE_MS = 3000L

class CodesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val sync = SyncManager(app, dao)
    val codes = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Soft-deleted rows (tombstones) exist so a delete can reach other synced devices;
        // once they're old enough to have surely synced everywhere, there's no reason to keep them.
        viewModelScope.launch { dao.purgeTombstonesOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) }
    }

    var driveMode by mutableStateOf(sync.driveModeEnabled); private set
    var lastSyncedAt by mutableStateOf(sync.lastSyncedAt); private set
    var syncError by mutableStateOf<String?>(null); private set
    var initialConflict by mutableStateOf<List<SavedCode>?>(null); private set
    var syncConflict by mutableStateOf<List<SavedCode>?>(null); private set

    private var pushJob: Job? = null

    fun save(name: String, text: String, format: String) = viewModelScope.launch {
        dao.upsert(SavedCode(id = UUID.randomUUID().toString(), name = name.trim(), text = text, format = format))
        onLocalChange()
    }

    fun rename(code: SavedCode, name: String) = viewModelScope.launch {
        dao.upsert(code.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
        onLocalChange()
    }

    fun delete(code: SavedCode) = viewModelScope.launch {
        dao.upsert(code.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        onLocalChange()
    }

    private fun onLocalChange() {
        sync.markLocalDirty()
        if (driveMode) {
            pushJob?.cancel()
            pushJob = viewModelScope.launch {
                delay(PUSH_DEBOUNCE_MS)
                handleOutcome(sync.push())
            }
        }
    }

    /** Called once Google has granted Drive access, with [scannedKey] non-null when linking via another device's QR code. */
    fun startDriveSetup(accessToken: String, scannedKey: ByteArray?) = viewModelScope.launch {
        if (scannedKey != null) SyncCrypto.setKey(getApplication(), scannedKey)
        else SyncCrypto.getOrCreateKey(getApplication())
        handleOutcome(sync.continueEnable(accessToken), isInitialSetup = true)
    }

    fun resolveInitialConflict(choice: ConflictChoice) = viewModelScope.launch {
        val remote = initialConflict ?: return@launch
        initialConflict = null
        handleOutcome(sync.resolveInitialConflict(choice, remote), isInitialSetup = true)
    }

    fun resolveSyncConflict(choice: ConflictChoice) = viewModelScope.launch {
        val remote = syncConflict ?: return@launch
        syncConflict = null
        handleOutcome(sync.resolveSyncConflict(choice, remote))
    }

    fun syncNow() = viewModelScope.launch { handleOutcome(sync.pull()) }

    fun disableDriveMode() {
        sync.disableDriveMode()
        driveMode = false
    }

    fun reportConnectError(message: String) {
        syncError = message
    }

    private fun handleOutcome(outcome: SyncOutcome, isInitialSetup: Boolean = false) {
        when (outcome) {
            SyncOutcome.Done -> {
                driveMode = sync.driveModeEnabled
                lastSyncedAt = sync.lastSyncedAt
                syncError = null
            }
            SyncOutcome.NeedsConsent -> syncError = "Reconnect to Google Drive in Settings"
            is SyncOutcome.Conflict -> if (isInitialSetup) initialConflict = outcome.remoteCodes else syncConflict = outcome.remoteCodes
            is SyncOutcome.Error -> syncError = outcome.message
        }
    }
}

private enum class Tab(val label: String) { Saved("Saved"), Scan("Scan"), Create("Create"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: CodesViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.Saved) }
    val codes by vm.codes.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val save: (String, String, String) -> Unit = { name, text, format ->
        vm.save(name, text, format)
        scope.launch { snackbar.showSnackbar("Saved") }
    }
    val (driveSetupBusy, beginDriveSetup) = rememberDriveSetup(vm)

    Scaffold(
        topBar = { TopAppBar(title = { Text("JujuCodes") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Saved -> Icons.Default.List
                                    Tab.Scan -> Icons.Default.QrCodeScanner
                                    Tab.Create -> Icons.Default.Add
                                    Tab.Settings -> Icons.Default.Settings
                                }, contentDescription = null
                            )
                        },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            Tab.Saved -> SavedScreen(codes, vm::rename, vm::delete, m)
            Tab.Scan -> ScanScreen(
                onSave = { name, text, format ->
                    save(name, text, format)
                    tab = Tab.Saved
                },
                onLinkDevice = { key -> beginDriveSetup(key) },
                modifier = m,
            )
            Tab.Create -> CreateScreen(save, m)
            Tab.Settings -> SettingsScreen(vm, driveSetupBusy, beginDriveSetup, m)
        }
    }

    vm.initialConflict?.let { remote ->
        val remoteCount = remote.count { it.deletedAt == null }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Data found in Google Drive") },
            text = {
                Text(
                    "There ${if (remoteCount == 1) "is" else "are"} already $remoteCount code" +
                        "${if (remoteCount == 1) "" else "s"} saved in the JujuCodes Drive folder. " +
                        "Use that data on this phone, or keep what's already on this phone? " +
                        "Keeping this phone's data will overwrite the Drive copy."
                )
            },
            confirmButton = { TextButton({ vm.resolveInitialConflict(ConflictChoice.USE_REMOTE) }) { Text("Use Drive's data") } },
            dismissButton = { TextButton({ vm.resolveInitialConflict(ConflictChoice.KEEP_LOCAL) }) { Text("Keep this phone's data") } },
        )
    }

    vm.syncConflict?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Changes on another device") },
            text = { Text("Both this phone and another device have changes since the last sync. Which should win?") },
            confirmButton = { TextButton({ vm.resolveSyncConflict(ConflictChoice.USE_REMOTE) }) { Text("Use other device's") } },
            dismissButton = { TextButton({ vm.resolveSyncConflict(ConflictChoice.KEEP_LOCAL) }) { Text("Keep this phone's") } },
        )
    }
}
