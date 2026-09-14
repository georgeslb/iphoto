package org.iphoto.mobile.ui.albums

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModel
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModelFactory
import org.iphoto.mobile.ui.common.PhotoGridContent

@OptIn(ExperimentalMaterial3Api::class)
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
        factory = PagedPhotoGridViewModelFactory(loadPage, albumId = albumId)
    )
    val state by viewModel.uiState.collectAsState()

    val detailViewModel: AlbumDetailViewModel = viewModel(
        key = "album-detail-$albumId",
        factory = AlbumDetailViewModelFactory(container.albumApi, albumId)
    )
    val detailState by detailViewModel.uiState.collectAsState()
    var showShareDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detailState.albumName ?: "Album") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Create share link")
                    }
                }
            )
        }
    ) { padding ->
        PhotoGridContent(
            state = state,
            viewModel = viewModel,
            onPhotoClick = onPhotoClick,
            onLoadMore = viewModel::loadMore,
            modifier = Modifier.padding(padding)
        )
    }

    if (showShareDialog) {
        CreateShareDialog(
            defaultName = detailState.albumName ?: "",
            serverUrl = container.absoluteUrl(""),
            isCreating = detailState.isCreatingShare,
            createdShare = detailState.createdShare,
            error = detailState.shareError,
            onDismiss = {
                showShareDialog = false
                detailViewModel.dismissShareResult()
            },
            onCreate = { name, daysAvailable, password ->
                detailViewModel.createShare(container.shareApi, name, daysAvailable, password)
            }
        )
    }
}
