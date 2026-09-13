package org.iphoto.mobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.network.AlbumApi

/**
 * "Add to album" picker: an existing album to add to, or a name for a brand-new one - there's no
 * other album-creation entry point in the app yet, so this doubles as that. Purely presentational:
 * the caller owns actually calling the API and dismissing this on success/failure.
 */
@Composable
fun AlbumPickerDialog(
    albumApi: AlbumApi,
    onDismiss: () -> Unit,
    onAlbumPicked: (albumId: String) -> Unit,
    onCreateAlbum: (name: String) -> Unit
) {
    var albums by remember { mutableStateOf<List<AlbumDTO>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var newAlbumName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            albums = albumApi.list()
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load albums"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text("New album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    enabled = newAlbumName.isNotBlank(),
                    onClick = { onCreateAlbum(newAlbumName.trim()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create & add")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                when {
                    loadError != null -> Text(loadError ?: "")
                    albums == null -> CircularProgressIndicator()
                    albums.isNullOrEmpty() -> Text("No existing albums yet")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(albums.orEmpty(), key = { it.id }) { album ->
                            Text(
                                album.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAlbumPicked(album.id) }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
