package com.qrcodejuju.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.qrcodejuju.app.data.CodeGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(onSave: (name: String, text: String, format: String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var formatIndex by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val (formatName, format) = CodeGenerator.formats[formatIndex]
    val result = remember(text, formatIndex) { CodeGenerator.generate(text, format) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readCodeFromImage(context, uri,
            onFound = { codeText, codeFormat ->
                text = codeText
                formatIndex = CodeGenerator.formats.indexOfFirst { it.second.name == codeFormat }
                imageError = null
            },
            onError = { imageError = it },
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton({ pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Default.Image, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("From image")
        }
        imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") })
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Text") })

        ExposedDropdownMenuBox(menuOpen, { menuOpen = it }) {
            OutlinedTextField(
                formatName, {}, Modifier.menuAnchor().fillMaxWidth(),
                readOnly = true, label = { Text("Format") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
            )
            ExposedDropdownMenu(menuOpen, { menuOpen = false }) {
                CodeGenerator.formats.forEachIndexed { i, (name, _) ->
                    DropdownMenuItem({ Text(name) }, { formatIndex = i; menuOpen = false })
                }
            }
        }

        val bmp = result.getOrNull()
        if (bmp != null) {
            Image(bmp.asImageBitmap(), "Generated code", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ onSave(name, text, format.name) }) { Text("Save") }
                OutlinedButton({ shareImage(context, bmp, text) }) { Text("Share") }
                if (isUrl(text)) OutlinedButton({ openIfUrl(context, text) }) { Text("Open link") }
            }
        } else if (text.isNotEmpty()) {
            Text(result.exceptionOrNull()?.message ?: "Can't encode", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Decodes the first supported code in the image at [uri] and reports its text and ZXing format name. */
private fun readCodeFromImage(
    context: android.content.Context,
    uri: Uri,
    onFound: (text: String, format: String) -> Unit,
    onError: (String) -> Unit,
) {
    val image = try {
        InputImage.fromFilePath(context, uri)
    } catch (e: Exception) {
        onError("Couldn't open that image")
        return
    }
    val scanner = BarcodeScanning.getClient()
    scanner.process(image)
        .addOnSuccessListener { list ->
            val hit = list.firstOrNull { it.rawValue != null && it.zxingFormatName() != null }
            if (hit != null) onFound(hit.rawValue!!, hit.zxingFormatName()!!)
            else onError("No supported code found in that image")
        }
        .addOnFailureListener { onError("Couldn't read that image") }
        .addOnCompleteListener { scanner.close() }
}
