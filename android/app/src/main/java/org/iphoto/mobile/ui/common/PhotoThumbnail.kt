package org.iphoto.mobile.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.iphoto.mobile.LocalAppContainer

fun formatVideoDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0) return null
    val totalSeconds = (durationMs + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnail(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    duration: Long? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isUploaded: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onSelectClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = PhotoThumbnail(
    imageModel = imageUrl as Any,
    modifier = modifier,
    isVideo = isVideo,
    duration = duration,
    isSelectionMode = isSelectionMode,
    isSelected = isSelected,
    isUploaded = isUploaded,
    onLongClick = onLongClick,
    onSelectClick = onSelectClick,
    onClick = onClick
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnail(
    imageModel: Any,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    duration: Long? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isUploaded: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onSelectClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val container = LocalAppContainer.current
    val isRemote = imageModel is String && (imageModel.startsWith("http://") || imageModel.startsWith("https://") || imageModel.startsWith("/"))
    val imageLoader = if (isRemote) container.imageLoader else container.localImageLoader

    Box(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = imageModel,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        }

        // Uploaded status cloud icon badge in the top-right corner
        if (isUploaded && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = "Uploaded",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Standard Photos-app grid convention: video thumbnails show a badge with video length
        // (if known) to the left of the play glyph, matching Google Photos / Apple Photos. Hidden
        // once selection mode is on so it doesn't fight visually with the selection checkmark.
        if (isVideo && !isSelectionMode) {
            val formattedDuration = remember(duration) { formatVideoDuration(duration) }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        if (formattedDuration != null) RoundedCornerShape(4.dp) else CircleShape
                    )
                    .padding(
                        if (formattedDuration != null) PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        else PaddingValues(3.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (formattedDuration != null) {
                    Text(
                        text = formattedDuration,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        // Selection button in the top-left corner:
        // When clicked, toggles selection and enters selection mode.
        if (onSelectClick != null || isSelectionMode) {
            val selectAction = onSelectClick ?: onClick
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(40.dp)
                    .combinedClickable(onClick = selectAction, onLongClick = onLongClick)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isSelectionMode || isSelected) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                                CircleShape
                            )
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
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
                } else {
                    // Visible affordance in top-left corner so user can easily tap to select
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    )
                }
            }
        }
    }
}
