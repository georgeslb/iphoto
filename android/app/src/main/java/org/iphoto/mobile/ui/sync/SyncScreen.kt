package org.iphoto.mobile.ui.sync

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import org.iphoto.mobile.sync.LocalAsset
import org.iphoto.mobile.ui.common.PhotoThumbnail

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    onAssetClick: (LocalAsset) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSpacePicker by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var showUploadAndTrashConfirm by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
            viewModel.addFolder(uri, displayName)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAllFolders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }

    Scaffold(
        bottomBar = {
            if (state.isSelectionMode) {
                BottomAppBar {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Default.Close, contentDescription = "Clear selection")
                            }
                            Text(
                                text = "${state.selectedAssets.size} selected",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                enabled = state.selectedAssets.isNotEmpty() && !state.isUploading
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Move to trash")
                            }
                            IconButton(
                                onClick = {
                                    showSpacePicker = { spaceId ->
                                        viewModel.uploadSelectedAssets(spaceId, deleteAfter = false)
                                    }
                                },
                                enabled = state.selectedAssets.isNotEmpty() && !state.isUploading
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = "Upload")
                            }
                            IconButton(
                                onClick = { showUploadAndTrashConfirm = true },
                                enabled = state.selectedAssets.isNotEmpty() && !state.isUploading
                            ) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Upload and Trash")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isUploading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Default.Folder, contentDescription = "Select Folder")
                }
                Text("Select folders")
            }

            if (state.selectedFolders.isNotEmpty()) {
                val pagerState = rememberPagerState { state.selectedFolders.size }

                LaunchedEffect(pagerState.currentPage, state.selectedFolders) {
                    if (state.selectedFolders.isNotEmpty()) {
                        val folder = state.selectedFolders[pagerState.currentPage]
                        viewModel.onTabSelected(folder)
                    }
                }

                TabRow(selectedTabIndex = pagerState.currentPage) {
                    state.selectedFolders.forEachIndexed { index, folder ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(folder.name)
                                    IconButton(onClick = { viewModel.removeFolder(folder) }) {
                                        Icon(Icons.Default.Close, "Close Tab")
                                    }
                                }
                            }
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().weight(1f)
                ) { pageIndex ->
                    val folder = state.selectedFolders[pageIndex]
                    FolderContentGrid(
                        items = state.folderContents[folder.uri],
                        isLoading = state.isLoadingFolderContents.contains(folder.uri),
                        isSelectionMode = state.isSelectionMode,
                        selectedAssetUris = state.selectedAssets,
                        onAssetLongClick = viewModel::enterSelectionMode,
                        onAssetClick = { asset ->
                            if (state.isSelectionMode) {
                                viewModel.toggleAssetSelection(asset)
                            } else {
                                onAssetClick(asset)
                            }
                        },
                        onDayClick = viewModel::toggleDaySelection
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Move to Trash?") },
            text = { Text("Move ${state.selectedAssets.size} selected file(s) to the local trash?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSelectedAssets()
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showUploadAndTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showUploadAndTrashConfirm = false },
            title = { Text("Upload and Trash?") },
            text = { Text("This will upload ${state.selectedAssets.size} selected file(s) and then move them to the local trash on your device. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    showUploadAndTrashConfirm = false
                    showSpacePicker = { spaceId ->
                        viewModel.uploadSelectedAssets(spaceId, deleteAfter = true)
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showUploadAndTrashConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showSpacePicker != null) {
        SpacePickerDialog(
            spaces = state.spaces,
            onDismiss = { showSpacePicker = null },
            onConfirm = { spaceId ->
                showSpacePicker?.invoke(spaceId)
                showSpacePicker = null
            }
        )
    }
}

@Composable
fun FolderContentGrid(
    items: List<GridItem>?,
    isLoading: Boolean,
    isSelectionMode: Boolean,
    selectedAssetUris: Set<Uri>,
    onAssetClick: (LocalAsset) -> Unit,
    onAssetLongClick: (LocalAsset) -> Unit,
    onDayClick: (List<LocalAsset>) -> Unit
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (items.isNullOrEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No media found.")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = items,
            key = { item ->
                when (item) {
                    is GridItem.Header -> item.text
                    is GridItem.Asset -> item.asset.uri
                }
            },
            contentType = { item ->
                when (item) {
                    is GridItem.Header -> "header"
                    is GridItem.Asset -> "asset"
                }
            },
            span = { item ->
                when (item) {
                    is GridItem.Header -> GridItemSpan(maxLineSpan)
                    is GridItem.Asset -> GridItemSpan(1)
                }
            }
        ) { item ->
            when (item) {
                is GridItem.Header -> {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onDayClick(item.assets) }.padding(horizontal = 6.dp, vertical = 10.dp)
                    )
                }
                is GridItem.Asset -> {
                    val asset = item.asset
                    PhotoThumbnail(
                        imageUrl = asset.uri.toString(),
                        isVideo = asset.mimeType.startsWith("video/"),
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedAssetUris.contains(asset.uri),
                        onLongClick = { onAssetLongClick(asset) },
                        modifier = Modifier.aspectRatio(1f),
                        onClick = { onAssetClick(asset) }
                    )
                }
            }
        }
    }
}
