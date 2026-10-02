package org.iphoto.mobile.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

    val currentVersion by container.libraryChangeVersion.collectAsState()
    var lastLoadedVersion by rememberSaveable { mutableStateOf(currentVersion) }
    LaunchedEffect(currentVersion) {
        if (currentVersion > lastLoadedVersion) {
            lastLoadedVersion = currentVersion
            viewModel.loadFirstPage()
        }
    }

    PhotoGridContent(
        state = state,
        viewModel = viewModel,
        onPhotoClick = onPhotoClick,
        onLoadMore = viewModel::loadMore
    )
}
