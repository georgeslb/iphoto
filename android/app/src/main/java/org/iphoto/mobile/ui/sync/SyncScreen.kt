package org.iphoto.mobile.ui.sync

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import java.text.DateFormat
import java.util.Date

@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val viewModel: SyncViewModel = viewModel(
        factory = SyncViewModelFactory(
            context.applicationContext,
            container.spaceApi,
            container.syncRepository(),
            container.syncPrefs
        )
    )
    val state by viewModel.uiState.collectAsState()
    var spaceMenuExpanded by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
            viewModel.selectFolder(uri, displayName)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Device folder sync", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick a Space and a folder on this phone. \"Sync now\" makes that Space mirror the " +
                "folder: new photos/videos are uploaded, and any that are no longer in the folder " +
                "are moved to the Garbage. Nothing happens automatically or in the background - " +
                "only when you tap the button.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Text("Space", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { spaceMenuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                val selectedName = state.spaces.find { it.id == state.selectedSpaceId }?.name
                Text(selectedName ?: "Choose a space…")
            }
            DropdownMenu(expanded = spaceMenuExpanded, onDismissRequest = { spaceMenuExpanded = false }) {
                if (state.isLoadingSpaces) {
                    DropdownMenuItem(text = { Text("Loading…") }, onClick = {}, enabled = false)
                } else if (state.spaces.isEmpty()) {
                    DropdownMenuItem(text = { Text("No spaces available") }, onClick = {}, enabled = false)
                }
                state.spaces.forEach { space ->
                    DropdownMenuItem(
                        text = { Text(space.name) },
                        onClick = {
                            viewModel.selectSpace(space.id)
                            spaceMenuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Folder", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(state.folderDisplayName ?: "Choose a folder…")
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (state.isSyncRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Sync in progress…", modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = { viewModel.previewSync() },
                enabled = state.selectedSpaceId != null && state.folderUri != null && !state.isPreviewing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isPreviewing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Text("Sync now")
                }
            }
        }

        if (state.lastSyncAt > 0) {
            Text(
                "Last synced: ${DateFormat.getDateTimeInstance().format(Date(state.lastSyncAt))}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
    }

    val diff = state.pendingDiff
    if (diff != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text("Confirm sync") },
            text = {
                Text(
                    "${diff.toUpload.size} photo(s)/video(s) will be uploaded to the space.\n" +
                        "${diff.toRemove.size} photo(s) will be moved to the Garbage " +
                        "(no longer found in the folder)."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSync) { Text("Sync") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPreview) { Text("Cancel") }
            }
        )
    }
}
