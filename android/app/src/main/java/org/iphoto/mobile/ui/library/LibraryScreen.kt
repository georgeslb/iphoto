package org.iphoto.mobile.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModel
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModelFactory
import org.iphoto.mobile.ui.common.PhotoGridContent

@Composable
fun LibraryScreen(onPhotoClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = remember(container) {
        { page, size ->
            val response = container.photoApi.listMain(page, size)
            response.content to response.last
        }
    }
    val viewModel: PagedPhotoGridViewModel = viewModel(
        key = "library",
        factory = PagedPhotoGridViewModelFactory(loadPage)
    )
    val state by viewModel.uiState.collectAsState()

    PhotoGridContent(state = state, onPhotoClick = onPhotoClick, onLoadMore = viewModel::loadMore)
}
