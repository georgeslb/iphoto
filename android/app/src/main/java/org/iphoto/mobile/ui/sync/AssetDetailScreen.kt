package org.iphoto.mobile.ui.sync

import android.app.Activity
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.pm.PackageManager
import androidx.compose.material.icons.filled.Edit
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.sync.EditSnapshot
import org.iphoto.mobile.sync.EditedMediaRelocator
import org.iphoto.mobile.sync.EditedOutput
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
    val isCurrentAssetUploaded = remember(folder, currentAsset, state.uploadedAssetKeys) {
        val folderUri = folder?.uri ?: return@remember false
        val exactKey = SyncPrefs.encodeUploadedKey(
            folderUri.toString(),
            currentAsset.relativeDir,
            currentAsset.displayName
        )
        val decodedKey = SyncPrefs.encodeUploadedKey(
            Uri.decode(folderUri.toString()),
            currentAsset.relativeDir,
            currentAsset.displayName
        )
        state.uploadedAssetKeys.contains(exactKey) || state.uploadedAssetKeys.contains(decodedKey)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSpacePicker by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var isUploading by remember { mutableStateOf(false) }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onAssetsTrashed(listOf(currentAsset))
            onBack()
        }
    }

    fun handleSingleAssetTrash(confirmFallback: Boolean = false) {
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
        if (confirmFallback) {
            showDeleteConfirm = true
            return
        }
        viewModel.trashAssetsDirectly(listOf(currentAsset))
        onBack()
    }

    // ---- External editor (ACTION_EDIT) -------------------------------------------------------
    val syncPrefs = remember { SyncPrefs(context) }
    val appContainer = LocalAppContainer.current
    var showEditorPicker by remember { mutableStateOf(false) }
    var availableEditors by remember { mutableStateOf<List<ExternalEditorApp>>(emptyList()) }
    var pendingEditorApp by remember { mutableStateOf<ExternalEditorApp?>(null) }
    var editSnapshot by remember { mutableStateOf<EditSnapshot?>(null) }
    var pendingEditOutputs by remember { mutableStateOf<List<EditedOutput>>(emptyList()) }

    fun finishEdit(message: String?) {
        // Drop cached bitmaps so an original edited in place is redrawn.
        SingletonImageLoader.get(context).memoryCache?.clear()
        appContainer.localImageLoader.memoryCache?.clear()
        editSnapshot = null
        pendingEditOutputs = emptyList()
        viewModel.refreshAllFolders()
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    fun applyEditedOutputFix() {
        val snapshot = editSnapshot
        val outputs = pendingEditOutputs
        if (snapshot == null || outputs.isEmpty()) {
            finishEdit(null)
            return
        }
        coroutineScope.launch {
            val fixed = withContext(Dispatchers.IO) {
                EditedMediaRelocator.applyOriginalProperties(context, snapshot, outputs)
            }
            finishEdit(
                if (fixed > 0) "Edited photo saved next to the original"
                else "Could not move the edited photo next to the original"
            )
        }
    }

    // Single system prompt granting us write access to the editor's output so we can move it
    // into the original folder and restore its dates/location.
    val editWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) applyEditedOutputFix() else finishEdit(null)
    }

    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val snapshot = editSnapshot
        if (snapshot == null) {
            finishEdit(null)
        } else {
            coroutineScope.launch {
                val outputs = withContext(Dispatchers.IO) {
                    EditedMediaRelocator.findEditedOutputs(context, snapshot)
                }
                if (outputs.isEmpty()) {
                    finishEdit(null)
                    return@launch
                }
                pendingEditOutputs = outputs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val pendingIntent = MediaStore.createWriteRequest(
                            context.contentResolver,
                            outputs.map { it.uri }
                        )
                        editWriteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "createWriteRequest failed: ${e.message}")
                    }
                }
                applyEditedOutputFix()
            }
        }
    }

    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (currentAsset.mimeType.startsWith("video/")) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED

    fun executeEditorLaunch(app: ExternalEditorApp) {
        val assetToEdit = currentAsset
        coroutineScope.launch {
            val mediaUri = if (hasMediaPermission()) viewModel.prepareMediaStoreUriForEditing(assetToEdit) else null
            val targetUri = mediaUri ?: assetToEdit.uri
            val isVideo = assetToEdit.mimeType.startsWith("video/")
            val mimeType = assetToEdit.mimeType.ifBlank { if (isVideo) "video/*" else "image/*" }

            // Remember where/when the original lives so the edited copy can be put back there.
            editSnapshot = if (mediaUri != null) {
                withContext(Dispatchers.IO) {
                    EditedMediaRelocator.snapshot(
                        context = context,
                        mediaUri = mediaUri,
                        isVideo = isVideo,
                        fileLastModifiedMs = assetToEdit.lastModified,
                        editorPackage = app.packageName
                    )
                }
            } else null

            try {
                editLauncher.launch(SystemEditorHelper.buildTargetIntent(app, targetUri, mimeType))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch editor ${app.packageName} with $targetUri", e)
                if (targetUri != assetToEdit.uri) {
                    try {
                        editLauncher.launch(SystemEditorHelper.buildTargetIntent(app, assetToEdit.uri, mimeType))
                        return@launch
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback to SAF Uri also failed", e2)
                    }
                }
                editSnapshot = null
                Toast.makeText(context, "Failed to open ${app.label}: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingEditorApp?.let { executeEditorLaunch(it) }
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

    fun handleEditClick() {
        val mediaUri = viewModel.getMediaStoreUri(currentAsset) ?: currentAsset.uri
        val mimeType = currentAsset.mimeType.ifBlank {
            context.contentResolver.getType(mediaUri)
                ?: if (currentAsset.mimeType.startsWith("video/")) "video/*" else "image/*"
        }
        val editors = SystemEditorHelper.queryAvailableEditors(context, mediaUri, mimeType)
        availableEditors = editors
        if (editors.isEmpty()) {
            Toast.makeText(context, "No gallery or editor app found on device", Toast.LENGTH_SHORT).show()
            return
        }
        val savedApp = syncPrefs.defaultEditorPackage?.let { pkg -> editors.find { it.packageName == pkg } }
        if (savedApp != null) launchTargetEditor(savedApp) else showEditorPicker = true
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
                },
                actions = {
                    if (isCurrentAssetUploaded) {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Uploaded",
                            modifier = Modifier.padding(end = 16.dp)
                        )
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
                        onClick = { handleSingleAssetTrash(confirmFallback = true) }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Move to Trash")
                    }
                    IconButton(
                        enabled = !isUploading,
                        onClick = { handleEditClick() }
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
                                        val verified = viewModel.uploadAndVerify(listOf(currentAsset), spaceId, targetFolderUri = folder?.uri)
                                        isUploading = false
                                        if (verified.isNotEmpty()) {
                                            Toast.makeText(context, verified.summaryMessage(singleAsset = true), Toast.LENGTH_SHORT).show()
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
                        onClick = {
                            showSpacePicker = { spaceId ->
                                coroutineScope.launch {
                                    isUploading = true
                                    try {
                                        val verified = viewModel.uploadAndVerify(
                                            listOf(currentAsset),
                                            spaceId,
                                            targetFolderUri = folder?.uri
                                        )
                                        isUploading = false
                                        if (verified.isNotEmpty()) {
                                            Toast.makeText(
                                                context,
                                                "${verified.summaryMessage(singleAsset = true)} - moved to trash",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            handleSingleAssetTrash()
                                        } else {
                                            Toast.makeText(context, "Upload verification failed", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        isUploading = false
                                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
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
                    viewModel.trashAssetsDirectly(listOf(currentAsset))
                    onBack()
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
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
                if (rememberChoice) syncPrefs.defaultEditorPackage = app.packageName
                showEditorPicker = false
                launchTargetEditor(app)
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
            playWhenReady = true
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
