package org.iphoto.mobile.ui.spaces

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModel
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModelFactory
import org.iphoto.mobile.ui.common.PhotoGridContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceDetailScreen(spaceId: String, onPhotoClick: (String) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current

    val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = remember(spaceId) {
        { page, size ->
            val response = container.photoApi.listForSpace(spaceId, page, size)
            response.content to response.page.isLast
        }
    }

    val viewModel: PagedPhotoGridViewModel = viewModel(
        key = "space-$spaceId",
        factory = PagedPhotoGridViewModelFactory(
            loadPage = loadPage,
            canMoveToMain = true
        )
    )
    val state by viewModel.uiState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshQuietly()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Space") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            modifier = Modifier.padding(padding).fillMaxSize()
        )
    }
}
