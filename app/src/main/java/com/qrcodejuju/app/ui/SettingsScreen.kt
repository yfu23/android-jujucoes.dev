package com.qrcodejuju.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.qrcodejuju.app.data.CodeGenerator
import com.qrcodejuju.app.data.DeviceLink
import com.qrcodejuju.app.data.SyncCrypto
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    vm: CodesViewModel,
    busy: Boolean,
    beginDriveSetup: (ByteArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showLinkQr by remember { mutableStateOf(false) }
    var showRecoveryKey by remember { mutableStateOf(false) }
    // Which mode a tap is asking to switch to, awaiting confirmation -- null means no change is pending.
    // A stray tap only gets this far; it takes a second, deliberate tap on the confirm dialog to actually apply.
    var pendingDriveMode by remember { mutableStateOf<Boolean?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            StorageOption(
                selected = !vm.driveMode,
                enabled = !busy,
                title = "This phone only",
                description = "Your codes stay on this device.",
                onSelect = { if (vm.driveMode) pendingDriveMode = false },
            )
            StorageOption(
                selected = vm.driveMode,
                enabled = !busy,
                title = "Google Drive",
                description = "Synced through the JujuCodes folder in My Drive. Contents are encrypted, so only this app can read them.",
                onSelect = { if (!vm.driveMode) pendingDriveMode = true },
            )
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (vm.driveMode) {
            HorizontalDivider()
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                if (vm.lastSyncedAt > 0) "Last synced ${DateFormat.getDateTimeInstance().format(Date(vm.lastSyncedAt))}"
                else "Not synced yet",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton({ vm.syncNow() }, enabled = !busy && !vm.syncing) {
                if (vm.syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (vm.syncing) "Syncing…" else "Sync now")
            }

            HorizontalDivider()
            Text("Other devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "A Google sign-in alone isn't enough to read your codes on another phone -- it also needs this " +
                    "device's encryption key.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ showLinkQr = true }) { Text("Link another device") }
                OutlinedButton({ showRecoveryKey = true }) { Text("Show recovery key") }
            }
        }
    }

    if (showLinkQr) {
        val key = remember { SyncCrypto.getOrCreateKey(context) }
        val payload = remember(key) { DeviceLink.encode(key) }
        val bmp = remember(payload) { CodeGenerator.generate(payload, BarcodeFormat.QR_CODE).getOrNull() }
        AlertDialog(
            onDismissRequest = { showLinkQr = false },
            title = { Text("Link another device") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("On your other phone, open JujuCodes, turn on Google Drive in Settings, then scan this from the Scan tab.")
                    Spacer(Modifier.height(12.dp))
                    if (bmp != null) Image(bmp.asImageBitmap(), "Device link QR code", Modifier.size(220.dp))
                }
            },
            confirmButton = { TextButton({ showLinkQr = false }) { Text("Done") } },
        )
    }

    if (showRecoveryKey) {
        val key = remember { SyncCrypto.getOrCreateKey(context) }
        val text = remember(key) { DeviceLink.encode(key) }
        AlertDialog(
            onDismissRequest = { showRecoveryKey = false },
            title = { Text("Recovery key") },
            text = {
                Column {
                    Text(
                        "Anyone with this key can read your saved codes. Keep it private -- use it only to set up " +
                            "a new phone when you don't have another signed-in device to scan a link code from."
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Row {
                    TextButton({ copyText(context, text) }) { Text("Copy") }
                    TextButton({ showRecoveryKey = false }) { Text("Close") }
                }
            },
        )
    }

    pendingDriveMode?.let { switchingToDrive ->
        AlertDialog(
            onDismissRequest = { pendingDriveMode = null },
            title = { Text(if (switchingToDrive) "Switch to Google Drive?" else "Switch to this phone only?") },
            text = {
                Text(
                    if (switchingToDrive)
                        "You'll be asked to grant access to your Google account, and your codes will start " +
                            "syncing through the JujuCodes folder in My Drive."
                    else
                        "Your codes will stop syncing. Nothing already in Google Drive will be deleted, and you " +
                            "can turn syncing back on later."
                )
            },
            confirmButton = {
                TextButton({
                    pendingDriveMode = null
                    if (switchingToDrive) beginDriveSetup(null) else vm.disableDriveMode()
                }) { Text("Switch") }
            },
            dismissButton = { TextButton({ pendingDriveMode = null }) { Text("Cancel") } },
        )
    }
}

/** One row of a radio group: both storage options are always visible, with their consequence spelled out underneath. */
@Composable
private fun StorageOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.padding(top = 12.dp)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
