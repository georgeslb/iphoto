package org.iphoto.mobile.ui.albums

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.iphoto.mobile.model.ShareResponse

/**
 * "Create share link" for an album - a form (name/password/validity) that, on success, switches
 * to showing the resulting link with a copy button. serverUrl is needed here (not just the
 * relative path) since the whole point is a link the user can hand to someone outside the app.
 */
@Composable
fun CreateShareDialog(
    defaultName: String,
    serverUrl: String,
    isCreating: Boolean,
    createdShare: ShareResponse?,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, daysAvailable: Int, password: String?) -> Unit
) {
    if (createdShare != null) {
        val link = "${serverUrl.trimEnd('/')}/share/${createdShare.userMd5}/${Uri.encode(createdShare.name)}"
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Share link created") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(link, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy link")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        )
        return
    }

    var name by remember(defaultName) { mutableStateOf(defaultName) }
    var password by remember { mutableStateOf("") }
    var daysAvailable by remember { mutableStateOf("7") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create share link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Link name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = daysAvailable,
                    onValueChange = { daysAvailable = it.filter(Char::isDigit) },
                    label = { Text("Valid for (days)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isCreating && name.isNotBlank() && daysAvailable.toIntOrNull() != null,
                onClick = { onCreate(name.trim(), daysAvailable.toInt(), password) }
            ) {
                if (isCreating) CircularProgressIndicator(modifier = Modifier) else Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel") }
        }
    )
}
