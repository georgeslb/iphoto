package org.iphoto.mobile.ui.sync

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
            container.photoApi,
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

    // The screen used to be a plain Column with no scroll modifier at all - fine while it was
    // short, but the description text plus a growing "Excluded from sync" list (unbounded, one
    // row per excluded photo) could overflow the screen height with no way to reach the rest.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Device folder sync", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick a Space and a folder on this phone. Turn on \"Sync automatically\" and this Space " +
                "will mirror the folder on its own - usually within moments of a new photo/video " +
                "showing up (works even if the app isn't running), with a check every 15 minutes as " +
                "a backup in case that doesn't fire. New files upload a bit at a time (a large " +
                "backlog is spread across several runs rather than sent all at once), and any that " +
                "are no longer in the folder are moved to the Garbage (and restored back if the " +
                "same file, or a garbaged photo, reappears). There's no manual \"sync now\" - only " +
                "the automatic background job.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sync automatically", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Runs roughly every 15 minutes in the background, network and battery permitting.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = state.autoSyncEnabled,
                onCheckedChange = viewModel::setAutoSyncEnabled,
                enabled = state.selectedSpaceId != null && state.folderUri != null
            )
        }

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

        // Block/unblock-on-sync status: the paired Space is locked against edits from outside
        // this app for as long as the pairing exists - "Stop sync" is the way to free it again
        // (see SyncViewModel#stopSync/lockCurrentPairingIfComplete).
        if (state.selectedSpaceId != null && state.folderUri != null) {
            val selectedSpace = state.spaces.find { it.id == state.selectedSpaceId }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selectedSpace?.syncLocked == true) {
                        "🔒 Locked for sync - no edits outside this app"
                    } else {
                        "Not yet locked - will retry automatically"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = viewModel::stopSync) { Text("Stop sync") }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (state.isSyncRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Sync in progress…", modifier = Modifier.padding(top = 8.dp))
        } else if (state.autoSyncEnabled) {
            Text(
                "Waiting for the next automatic sync…",
                style = MaterialTheme.typography.bodyMedium
            )
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

        if (state.excludedAssets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Excluded from sync", style = MaterialTheme.typography.labelLarge)
            Text(
                "These photos were removed from the space but kept on this phone - they won't be " +
                    "re-uploaded. Tap \"Include\" to bring one back into the space.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            state.excludedAssets.forEach { asset ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(asset.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.includeAsset(asset) }) { Text("Include") }
                }
            }
        }
    }

    // Block/unblock-on-sync: shown once, right after SyncViewModel notices on app start that this
    // device's sync lock was released remotely (web "I lost my phone" unlock, or this device
    // being revoked from Manage Devices) - the pairing above is already cleared by this point, so
    // the Space/Folder fields already show empty and ready to be filled in again.
    if (state.syncStoppedRemotely) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSyncStoppedWarning,
            title = { Text("Sync was stopped") },
            text = {
                Text(
                    "This device's sync was stopped - the space was unlocked from another " +
                        "device or the web app. Pick a space and folder again to resume syncing."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSyncStoppedWarning) { Text("Got it") }
            }
        )
    }
}
