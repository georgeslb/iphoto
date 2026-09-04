package org.iphoto.mobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import org.iphoto.mobile.LocalAppContainer

@Composable
fun PhotoThumbnail(imageUrl: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val imageLoader = LocalAppContainer.current.imageLoader
    AsyncImage(
        model = imageUrl,
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clickable(onClick = onClick)
    )
}
