package org.iphoto.mobile.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iphoto.mobile.model.SpaceDTO

@Composable
fun SpacePickerDialog(
    spaces: List<SpaceDTO>,
    onDismiss: () -> Unit,
    onConfirm: (spaceId: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Space") },
        text = {
            if (spaces.isEmpty()) {
                Text("No spaces available")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(spaces, key = { it.id }) { space ->
                        Text(
                            text = space.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(space.id) }
                                .padding(vertical = 12.dp)
                        )
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
