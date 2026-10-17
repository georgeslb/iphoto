package org.iphoto.mobile.ui.sync

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.sync.LocalAsset
import org.iphoto.mobile.ui.common.PhotoThumbnail

private const val TAG = "SyncScreen"

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
    var pendingTrashAssets by remember { mutableStateOf<List<LocalAsset>?>(null) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Best-effort permission grant for unredacted media location bytes */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val needed = buildList {
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                mediaPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
            viewModel.addFolder(uri, displayName)
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val assetsToTrash = pendingTrashAssets ?: state.folderContents.values.flatten()
                .filterIsInstance<GridItem.Asset>()
                .map { it.asset }
                .filter { it.uri in state.selectedAssets }
            viewModel.onAssetsTrashed(assetsToTrash)
        }
        pendingTrashAssets = null
    }

    fun handleMoveToTrash(targetAssets: List<LocalAsset>? = null) {
        val selected = state.selectedAssets
        val assets = targetAssets ?: state.folderContents.values.flatten()
            .filterIsInstance<GridItem.Asset>()
            .map { it.asset }
            .filter { it.uri in selected }

        if (assets.isEmpty()) return

        pendingTrashAssets = assets
        val mediaUris = viewModel.getMediaUrisForAssets(assets)
        if (mediaUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, mediaUris, true)
                trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                return
            } catch (e: Exception) {
                Log.w(TAG, "createTrashRequest failed: ${e.message}")
            }
        }
        viewModel.trashAssetsDirectly(assets)
        pendingTrashAssets = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAllFolders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.isUploading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 0.dp),
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
                        folderUri = folder.uri,
                        items = state.folderContents[folder.uri],
                        isLoading = state.isLoadingFolderContents.contains(folder.uri),
                        isSelectionMode = state.isSelectionMode,
                        selectedAssetUris = state.selectedAssets,
                        uploadedAssetKeys = state.uploadedAssetKeys,
                        onAssetLongClick = viewModel::enterSelectionMode,
                        onAssetSelectClick = { asset ->
                            if (!state.isSelectionMode) {
                                viewModel.enterSelectionMode(asset)
                            } else {
                                viewModel.toggleAssetSelection(asset)
                            }
                        },
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

        // Selection Action Bar at the bottom
        if (state.isSelectionMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    coroutineScope.launch {
                                        val selected = state.selectedAssets
                                        val assets = state.folderContents.values.flatten()
                                            .filterIsInstance<GridItem.Asset>()
                                            .map { it.asset }
                                            .filter { it.uri in selected }
                                        val verified = viewModel.uploadAndVerify(assets, spaceId)
                                        if (verified.isNotEmpty()) {
                                            Toast.makeText(context, verified.summaryMessage(), Toast.LENGTH_SHORT).show()
                                            viewModel.clearSelection()
                                        } else {
                                            Toast.makeText(context, "Upload failed on server", Toast.LENGTH_SHORT).show()
                                        }
                                    }
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Move to Trash?") },
            text = { Text("Move ${state.selectedAssets.size} selected file(s) to the local trash?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    handleMoveToTrash()
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showUploadAndTrashConfirm) {
        AlertDialog(
            onDismissRequest = { if (!state.isUploading) showUploadAndTrashConfirm = false },
            title = { Text("Upload and Trash?") },
            text = { Text("This will upload ${state.selectedAssets.size} selected file(s) to the selected Space, verify server response, and then move them to the trash. Continue?") },
            confirmButton = {
                TextButton(
                    enabled = !state.isUploading,
                    onClick = {
                        showUploadAndTrashConfirm = false
                        showSpacePicker = { spaceId ->
                            coroutineScope.launch {
                                val selected = state.selectedAssets
                                val assets = state.folderContents.values.flatten()
                                    .filterIsInstance<GridItem.Asset>()
                                    .map { it.asset }
                                    .filter { it.uri in selected }

                                // Step 1: Upload chunks. Step 2: Poll back office task until finished.
                                val verified = viewModel.uploadAndVerify(assets, spaceId)

                                if (verified.size == assets.size) {
                                    Toast.makeText(context, "${verified.summaryMessage()}. Moving to trash.", Toast.LENGTH_SHORT).show()
                                    handleMoveToTrash(verified.allVerified)
                                } else if (verified.isNotEmpty()) {
                                    Toast.makeText(
                                        context,
                                        "${verified.summaryMessage()} (${verified.size}/${assets.size}). Moving ${verified.size} to trash.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    handleMoveToTrash(verified.allVerified)
                                } else {
                                    Toast.makeText(context, "Upload failed on server. No files were moved to trash.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.isUploading,
                    onClick = { showUploadAndTrashConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (showSpacePicker != null) {
        LaunchedEffect(Unit) {
            viewModel.refreshSpaces()
        }
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
    folderUri: Uri,
    items: List<GridItem>?,
    isLoading: Boolean,
    isSelectionMode: Boolean,
    selectedAssetUris: Set<Uri>,
    uploadedAssetKeys: Set<String>,
    onAssetClick: (LocalAsset) -> Unit,
    onAssetLongClick: (LocalAsset) -> Unit,
    onAssetSelectClick: (LocalAsset) -> Unit,
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
                    val exactKey = SyncPrefs.encodeUploadedKey(folderUri.toString(), asset.relativeDir, asset.displayName)
                    val decodedKey = SyncPrefs.encodeUploadedKey(Uri.decode(folderUri.toString()), asset.relativeDir, asset.displayName)
                    val isUploaded = uploadedAssetKeys.contains(exactKey) || uploadedAssetKeys.contains(decodedKey)
                    PhotoThumbnail(
                        imageUrl = asset.uri.toString(),
                        isVideo = asset.mimeType.startsWith("video/"),
                        duration = asset.duration,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedAssetUris.contains(asset.uri),
                        isUploaded = isUploaded,
                        onLongClick = { onAssetLongClick(asset) },
                        onSelectClick = { onAssetSelectClick(asset) },
                        modifier = Modifier.aspectRatio(1f),
                        onClick = { onAssetClick(asset) }
                    )
                }
            }
        }
    }
}
