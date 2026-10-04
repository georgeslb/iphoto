package org.iphoto.mobile.ui.sync

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.imageLoader
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import org.iphoto.mobile.data.SyncPrefs
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
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val syncPrefs = remember { SyncPrefs(context) }

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
    var isUploading by remember { mutableStateOf(false) }
    var showEditorPicker by remember { mutableStateOf(false) }
    var availableEditors by remember { mutableStateOf<List<ExternalEditorApp>>(emptyList()) }
    var pendingEditorApp by remember { mutableStateOf<ExternalEditorApp?>(null) }

    val editLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Clear Coil cache so updated image is reloaded
        context.imageLoader.let { loader ->
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        viewModel.refreshAllFolders()
    }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onAssetsTrashed(listOf(currentAsset))
            onBack()
        }
    }

    fun handleSingleAssetTrash() {
        val mediaUris = viewModel.getMediaUrisForAssets(listOf(currentAsset))
        if (mediaUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, mediaUris, true)
                trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                return
            } catch (e: Exception) {
                Log.w(TAG, "createTrashRequest failed: ${e.message}")
            }
        }
        viewModel.trashAssetsDirectly(listOf(currentAsset))
        onBack()
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

    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (currentAsset.mimeType.startsWith("video/")) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasMediaPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            mediaPermission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun executeEditorLaunch(app: ExternalEditorApp) {
        coroutineScope.launch {
            val isMediaPermGranted = hasMediaPermission()
            val targetUri = if (isMediaPermGranted) {
                viewModel.prepareMediaStoreUriForEditing(currentAsset) ?: currentAsset.uri
            } else {
                currentAsset.uri
            }

            val mimeType = currentAsset.mimeType.ifBlank {
                if (currentAsset.mimeType.startsWith("video/")) "video/*" else "image/*"
            }

            val intent = SystemEditorHelper.buildTargetIntent(app, targetUri, mimeType)
            try {
                editLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch editor ${app.packageName} with $targetUri", e)
                if (targetUri != currentAsset.uri) {
                    try {
                        val safIntent = SystemEditorHelper.buildTargetIntent(app, currentAsset.uri, mimeType)
                        editLauncher.launch(safIntent)
                        return@launch
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback to SAF Uri also failed", e2)
                    }
                }
                Toast.makeText(context, "Failed to open ${app.label}: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        pendingEditorApp?.let { app ->
            executeEditorLaunch(app)
        }
        pendingEditorApp = null
    }

    fun launchTargetEditor(app: ExternalEditorApp) {
        if (!hasMediaPermission()) {
            pendingEditorApp = app
            permissionLauncher.launch(mediaPermission)
        } else {
            executeEditorLaunch(app)
        }
    }

    fun handleEditClick(forceShowPicker: Boolean = false) {
        val mediaUri = viewModel.getMediaStoreUri(currentAsset) ?: currentAsset.uri
        val mimeType = currentAsset.mimeType.ifBlank {
            context.contentResolver.getType(mediaUri) ?: if (currentAsset.mimeType.startsWith("video/")) "video/*" else "image/*"
        }
        val editors = SystemEditorHelper.queryAvailableEditors(context, mediaUri, mimeType)
        availableEditors = editors

        if (editors.isEmpty()) {
            Toast.makeText(context, "No gallery or editor app found on device", Toast.LENGTH_SHORT).show()
            return
        }

        val savedPackage = syncPrefs.defaultEditorPackage
        val savedApp = if (!forceShowPicker && savedPackage != null) {
            editors.find { it.packageName == savedPackage }
        } else null

        if (savedApp != null) {
            launchTargetEditor(savedApp)
        } else {
            showEditorPicker = true
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
                    IconButton(
                        enabled = !isUploading,
                        onClick = { showDeleteConfirm = true }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Move to Trash")
                    }
                    IconButton(
                        enabled = !isUploading,
                        onClick = { handleEditClick(forceShowPicker = false) }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(
                        enabled = !isUploading,
                        onClick = {
                            showSpacePicker = { spaceId ->
                                coroutineScope.launch {
                                    isUploading = true
                                    try {
                                        val verified = viewModel.uploadAndVerify(listOf(currentAsset), spaceId)
                                        isUploading = false
                                        if (verified.isNotEmpty()) {
                                            Toast.makeText(context, "Uploaded successfully", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Upload failed on server", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        isUploading = false
                                        Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload")
                    }
                    IconButton(
                        enabled = !isUploading,
                        onClick = { showUploadAndTrashConfirm = true }
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Upload and Trash")
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
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

            if (isUploading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
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
                    handleSingleAssetTrash()
                }) { Text("Move to Trash") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showUploadAndTrashConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isUploading) showUploadAndTrashConfirm = false },
            title = { Text("Upload and Trash?") },
            text = { Text("This will upload '${currentAsset.displayName}' to the selected Space, verify server response, and then move it to the trash. Continue?") },
            confirmButton = {
                TextButton(
                    enabled = !isUploading,
                    onClick = {
                        showUploadAndTrashConfirm = false
                        showSpacePicker = { spaceId ->
                            coroutineScope.launch {
                                isUploading = true
                                try {
                                    val verified = viewModel.uploadAndVerify(listOf(currentAsset), spaceId)
                                    isUploading = false
                                    if (verified.isNotEmpty()) {
                                        handleSingleAssetTrash()
                                    } else {
                                        Toast.makeText(context, "Upload failed on server", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    isUploading = false
                                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isUploading,
                    onClick = { showUploadAndTrashConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (showEditorPicker) {
        EditorPickerDialog(
            apps = availableEditors,
            hasSavedDefault = syncPrefs.defaultEditorPackage != null,
            onDismiss = { showEditorPicker = false },
            onResetDefault = {
                syncPrefs.defaultEditorPackage = null
                showEditorPicker = false
                Toast.makeText(context, "Default editor reset", Toast.LENGTH_SHORT).show()
            },
            onSelectApp = { app, rememberChoice ->
                if (rememberChoice) {
                    syncPrefs.defaultEditorPackage = app.packageName
                }
                showEditorPicker = false
                launchTargetEditor(app)
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
            playWhenReady = true // Automatically starts playing
            prepare()
        }
    }

    // Auto-pause video when the user swipes away to another asset, resume when swiping back
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
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
