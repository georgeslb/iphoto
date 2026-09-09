package org.iphoto.mobile.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Library : Destination("library", "Library", Icons.Default.PhotoLibrary)
    data object Albums : Destination("albums", "Albums", Icons.Default.Collections)
    data object Spaces : Destination("spaces", "Spaces", Icons.Default.Folder)
    data object Shares : Destination("shares", "Shares", Icons.Default.Link)
    data object Sync : Destination("sync", "Sync", Icons.Default.Sync)

    companion object {
        val bottomNavTabs = listOf(Library, Albums, Spaces, Shares, Sync)
    }
}
