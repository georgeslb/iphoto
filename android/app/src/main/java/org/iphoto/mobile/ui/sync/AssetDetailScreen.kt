package org.iphoto.mobile.ui.sync

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.imageLoader
import coil3.memory.MemoryCache
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import org.iphoto.mobile.sync.LocalAsset

private const val TAG = "AssetDetailScreen"

@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: SyncViewModel,
    asset: LocalAsset,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val folder = viewModel.findAssetAndFolder(asset.uri)?.second
    val assetsInFolder = remember(folder, state.folderContents) {
        folder?.let { f ->
            state.folderContents[f.uri]?.filterIsInstance<GridItem.Asset>()?.map { it.asset }
        } ?: listOf(asset)
    }

    val initialIndex = remember(asset, assetsInFolder) {
        val idx = assetsInFolder.indexOfFirst { it.uri == asset.uri }
        if (idx >= 0) idx else 0
    }

    val pagerState = rememberPagerState(initialPage = initialIndex) { assetsInFolder.size }
    val currentAsset = assetsInFolder.getOrElse(pagerState.currentPage) { asset }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSpacePicker by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var showUploadAndTrashConfirm by remember { mutableStateOf(false) }

    val editLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Invalidate Coil cache for this asset in case it was modified in-place by the editor
        context.imageLoader.let { loader ->
            loader.memoryCache?.remove(MemoryCache.Key(currentAsset.uri.toString()))
            loader.diskCache?.remove(currentAsset.uri.toString())
        }
        // Refresh the folder in ViewModel so any changes are immediately re-scanned
        coroutineScope.launch {
            viewModel.findAssetAndFolder(currentAsset.uri)?.let { (_, f) ->
                viewModel.refreshFolder(f.uri, f.name)
            }
        }
    }

    fun launchSystemEditor() {
        val mediaUri = viewModel.getMediaStoreUri(currentAsset) ?: currentAsset.uri
        val mimeType = currentAsset.mimeType.ifBlank {
            context.contentResolver.getType(mediaUri) ?: if (currentAsset.mimeType.startsWith("video/")) "video/*" else "image/*"
        }
        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(mediaUri, mimeType)
            clipData = ClipData.newRawUri("media", mediaUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(mediaUri, mimeType)
            clipData = ClipData.newRawUri("media", mediaUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            val resolveList = context.packageManager.queryIntentActivities(editIntent, 0)
            if (resolveList.isNotEmpty()) {
                val chooser = Intent.createChooser(editIntent, "Edit photo")
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(viewIntent))
                editLauncher.launch(chooser)
            } else {
                val chooser = Intent.createChooser(viewIntent, "Open with")
                editLauncher.launch(chooser)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch editor chooser", e)
            try {
                context.startActivity(viewIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start viewIntent directly", e2)
                Toast.makeText(context, "No gallery or editor app found on device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (assetsInFolder.size > 1) {
                        Text("${pagerState.currentPage + 1} / ${assetsInFolder.size}")
                    } else {
                        Text(currentAsset.displayName)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                    IconButton(onClick = { launchSystemEditor() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = {
                        showSpacePicker = { spaceId ->
                            viewModel.uploadSingleAsset(currentAsset.uri, spaceId, false)
                        }
                    }) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload")
                    }
                    IconButton(onClick = { showUploadAndTrashConfirm = true }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Upload and Trash")
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { pageIndex ->
            val pageAsset = assetsInFolder[pageIndex]
            val isVideo = pageAsset.mimeType.startsWith("video/")
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    LocalVideoPlayer(
                        uri = pageAsset.uri,
                        isCurrentPage = (pageIndex == pagerState.currentPage),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ZoomableAsyncImage(
                        model = pageAsset.uri,
                        contentDescription = pageAsset.displayName,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Move to Trash?") },
            text = { Text("Move '${currentAsset.displayName}' to the local trash?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.trashSingleAsset(currentAsset)
                    if (assetsInFolder.size <= 1) {
                        onBack()
                    }
                }) { Text("Move to Trash") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showUploadAndTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showUploadAndTrashConfirm = false },
            title = { Text("Upload and Delete?") },
            text = { Text("This will upload '${currentAsset.displayName}' and then move it to the local trash on your device. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    showUploadAndTrashConfirm = false
                    showSpacePicker = { spaceId ->
                        viewModel.uploadSingleAsset(currentAsset.uri, spaceId, true)
                        if (assetsInFolder.size <= 1) {
                            onBack()
                        }
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showUploadAndTrashConfirm = false }) { Text("Cancel") } }
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

@OptIn(UnstableApi::class)
@Composable
private fun LocalVideoPlayer(
    uri: Uri,
    isCurrentPage: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = false
            prepare()
        }
    }

    // Auto-pause video when the user swipes away to another asset
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            exoPlayer.pause()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
            }
        },
        update = { playerView ->
            if (playerView.player != exoPlayer) {
                playerView.player = exoPlayer
            }
        }
    )
}
