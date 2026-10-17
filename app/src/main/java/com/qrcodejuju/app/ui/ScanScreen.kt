package com.qrcodejuju.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.qrcodejuju.app.data.DeviceLink
import java.util.concurrent.Executors

private data class Scanned(val text: String, val format: String)

@Composable
fun ScanScreen(
    onSave: (name: String, text: String, format: String) -> Unit,
    onLinkDevice: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Camera permission is needed to scan codes.")
            Spacer(Modifier.height(8.dp))
            Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
        }
        return
    }

    var scanned by remember { mutableStateOf<Scanned?>(null) }
    var torch by remember { mutableStateOf(false) }
    var brightBoost by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
    KeepScreenBright(brightBoost)

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media == null) { proxy.close(); return@setAnalyzer }
                        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { list ->
                                val hit = list.firstOrNull { it.rawValue != null && it.zxingFormatName() != null }
                                if (hit != null && scanned == null) scanned = Scanned(hit.rawValue!!, hit.zxingFormatName()!!)
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconToggleButton(checked = brightBoost, onCheckedChange = { brightBoost = it }) {
                Icon(
                    if (brightBoost) Icons.Default.BrightnessHigh else Icons.Default.BrightnessLow,
                    "Boost screen brightness",
                )
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                FilledIconToggleButton(
                    checked = torch,
                    onCheckedChange = { torch = it; camera?.cameraControl?.enableTorch(it) },
                ) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flashlight") }
            }
        }
    }

    scanned?.let { s ->
        val linkKey = remember(s) { DeviceLink.decode(s.text) }
        if (linkKey != null) {
            AlertDialog(
                onDismissRequest = { scanned = null },
                title = { Text("Link this device") },
                text = {
                    Text(
                        "Connect this phone to the Google Drive sync set up on your other device? " +
                            "It'll be able to read and write the same saved codes."
                    )
                },
                confirmButton = {
                    TextButton({ onLinkDevice(linkKey); scanned = null }) { Text("Link device") }
                },
                dismissButton = { TextButton({ scanned = null }) { Text("Cancel") } },
            )
        } else {
            var name by remember(s) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { scanned = null },
                title = { Text(com.qrcodejuju.app.data.CodeGenerator.displayName(s.format)) },
                text = {
                    Column {
                        Text(s.text)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                    }
                },
                confirmButton = {
                    Row {
                        if (isUrl(s.text)) TextButton({ openIfUrl(context, s.text) }) { Text("Open link") }
                        TextButton({ onSave(name, s.text, s.format); scanned = null }) { Text("Save") }
                    }
                },
                dismissButton = {
                    Row {
                        TextButton({ copyText(context, s.text) }) { Text("Copy") }
                        TextButton({ scanned = null }) { Text("Dismiss") }
                    }
                },
            )
        }
    }
}
