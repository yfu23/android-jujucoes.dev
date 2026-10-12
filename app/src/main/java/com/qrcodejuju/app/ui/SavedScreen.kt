package com.qrcodejuju.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.qrcodejuju.app.data.CodeGenerator
import com.qrcodejuju.app.data.SavedCode
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedScreen(
    codes: List<SavedCode>,
    onRename: (SavedCode, String) -> Unit,
    onDelete: (SavedCode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<SavedCode?>(null) }
    if (codes.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No saved codes yet. Scan or create one.")
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(codes, key = { it.id }) { code ->
                val bmp = remember(code.id) { render(code) }
                Card(Modifier.fillMaxWidth().clickable { selected = code }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(72.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(code.name.ifBlank { code.text }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (code.name.isNotBlank()) {
                                Text(code.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "${CodeGenerator.displayName(code.format)} · ${DateFormat.getDateInstance().format(Date(code.createdAt))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { code ->
        val context = LocalContext.current
        val bmp = remember(code.id) { render(code) }
        var editingName by remember(code.id) { mutableStateOf(false) }
        var nameInput by remember(code.id) { mutableStateOf(code.name) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                if (editingName) {
                    OutlinedTextField(
                        nameInput, { nameInput = it }, singleLine = true,
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code.name.ifBlank { CodeGenerator.displayName(code.format) },
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        IconButton({ editingName = true }) { Icon(Icons.Default.Edit, "Rename") }
                    }
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(8.dp))
                    Text(CodeGenerator.displayName(code.format), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(code.text) }
                    Row {
                        IconButton({ copyText(context, code.text) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                        if (bmp != null) IconButton({ shareImage(context, bmp, code.text) }) { Icon(Icons.Default.Share, "Share") }
                        IconButton({ onDelete(code); selected = null }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            },
            confirmButton = {
                if (editingName) {
                    TextButton({ onRename(code, nameInput); editingName = false; selected = null }) { Text("Save name") }
                } else {
                    if (isUrl(code.text)) TextButton({ openIfUrl(context, code.text) }) { Text("Open link") }
                    TextButton({ selected = null }) { Text("Close") }
                }
            },
        )
    }
}

private fun render(code: SavedCode) =
    BarcodeFormat.valueOf(code.format).let { CodeGenerator.generate(code.text, it).getOrNull() }
