package org.iphoto.mobile.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bottom contextual action bar shown while long-press multi-select is active - same role as stock
 * Google Photos' bottom bar during selection (rather than reworking every screen's own top bar,
 * which would mean touching Library/Album-detail/Space-detail's Scaffolds individually).
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    enabled: Boolean,
    onCancel: () -> Unit,
    onMoveToGarbage: () -> Unit,
    onAddToAlbum: () -> Unit,
    onEditDate: () -> Unit,
    // Only shown inside an album (AlbumDetailScreen) - Library/Space have no album membership to
    // remove from.
    onRemoveFromAlbum: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                }
                Text("$selectedCount selected", style = MaterialTheme.typography.titleMedium)
            }
            Row {
                IconButton(onClick = onEditDate, enabled = enabled) {
                    Icon(Icons.Filled.Event, contentDescription = "Edit date")
                }
                IconButton(onClick = onAddToAlbum, enabled = enabled) {
                    Icon(Icons.Filled.PhotoAlbum, contentDescription = "Add to album")
                }
                if (onRemoveFromAlbum != null) {
                    IconButton(onClick = onRemoveFromAlbum, enabled = enabled) {
                        Icon(Icons.Filled.PlaylistRemove, contentDescription = "Remove from album")
                    }
                }
                IconButton(onClick = onMoveToGarbage, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "Move to garbage")
                }
            }
        }
    }
}
