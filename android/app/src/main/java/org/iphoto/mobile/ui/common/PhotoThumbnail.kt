package org.iphoto.mobile.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.iphoto.mobile.LocalAppContainer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnail(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    // Selection mode is opt-in: callers that don't pass onLongClick (e.g. AlbumRow's cover
    // thumbnail) get plain tap-to-open behavior, unchanged from before selection existed.
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val imageLoader = LocalAppContainer.current.imageLoader
    Box(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = imageUrl,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        }
        // Standard Photos-app grid convention: a small play glyph marks video thumbnails, same
        // role as the web grid's own .video-badge (PhotoGrid.jsx). Hidden once selection mode is
        // on so it doesn't fight visually with the selection checkmark in the same corner area.
        if (isVideo && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(12.dp)
                )
            }
        }
        // Empty outline once selection mode is active on the grid (even for un-selected photos,
        // so it's clear tapping selects them), filled once this one is picked - same affordance
        // shape stock Google Photos uses.
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(
                        if (isSelected) Color.Transparent else Color.Black.copy(alpha = 0.35f),
                        CircleShape
                    )
                    .border(1.5.dp, Color.White, CircleShape)
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
