package org.iphoto.mobile.ui.albums

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModel
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModelFactory
import org.iphoto.mobile.ui.common.PhotoGridContent

@Composable
fun AlbumDetailScreen(albumId: String, onPhotoClick: (String) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = remember(albumId) {
        { page, size ->
            val response = container.photoApi.listForAlbum(albumId, page, size)
            response.content to response.page.isLast
        }
    }
    val viewModel: PagedPhotoGridViewModel = viewModel(
        key = "album-$albumId",
        factory = PagedPhotoGridViewModelFactory(loadPage)
    )
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Album") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        PhotoGridContent(
            state = state,
            onPhotoClick = onPhotoClick,
            onLoadMore = viewModel::loadMore,
            modifier = Modifier.padding(padding)
        )
    }
}
