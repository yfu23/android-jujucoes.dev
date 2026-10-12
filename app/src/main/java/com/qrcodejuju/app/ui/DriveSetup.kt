package com.qrcodejuju.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.qrcodejuju.app.data.DriveAuth
import com.qrcodejuju.app.data.DriveAuthResult
import kotlinx.coroutines.launch

/**
 * Drives the Google consent flow and hands the resulting access token to
 * [CodesViewModel.startDriveSetup]. Shared by the Settings screen (turning Drive mode on)
 * and the Scan screen (scanning a "link this device" QR code from another phone) so there's
 * one consent launcher, not two.
 *
 * Returns whether a consent round-trip is in flight, and the function to call to start one
 * -- pass a non-null [ByteArray] when linking with a key scanned from another device, or
 * null to generate a new key (first device to turn Drive mode on).
 */
@Composable
fun rememberDriveSetup(vm: CodesViewModel): Pair<Boolean, (ByteArray?) -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var scannedKeyForLink by remember { mutableStateOf<ByteArray?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        busy = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            when (val authResult = DriveAuth.fromIntent(context, data)) {
                is DriveAuthResult.Granted -> vm.startDriveSetup(authResult.accessToken, scannedKeyForLink)
                else -> vm.reportConnectError("Couldn't connect to Google Drive")
            }
        } else {
            vm.reportConnectError("Google Drive access wasn't granted")
        }
        scannedKeyForLink = null
    }

    val begin: (ByteArray?) -> Unit = { scannedKey ->
        busy = true
        scannedKeyForLink = scannedKey
        scope.launch {
            when (val auth = DriveAuth.authorize(context)) {
                is DriveAuthResult.Granted -> {
                    busy = false
                    vm.startDriveSetup(auth.accessToken, scannedKey)
                }
                is DriveAuthResult.NeedsConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(auth.intentSender).build())
                is DriveAuthResult.Failed -> {
                    busy = false
                    vm.reportConnectError(auth.message)
                }
            }
        }
    }

    return busy to begin
}
