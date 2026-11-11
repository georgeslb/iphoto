package org.iphoto.mobile.ui.photo

import android.content.ContentValues
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.sync.EditSnapshot
import org.iphoto.mobile.sync.EditedMediaRelocator
import org.iphoto.mobile.ui.common.AlbumPickerDialog
import org.iphoto.mobile.ui.common.DateTimePickerDialog
import org.iphoto.mobile.ui.common.PhotoBrowsingContext
import org.iphoto.mobile.ui.sync.EditorPickerDialog
import org.iphoto.mobile.ui.sync.ExternalEditorApp
import org.iphoto.mobile.ui.sync.SystemEditorHelper
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "PhotoDetailScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photoId: String,
    albumId: String? = null,
    spaceId: String? = null,
    onBack: () -> Unit,
    // Called for the next/previous arrows - navigates to a sibling photo (see PhotoBrowsingContext)
    // while staying on this same route shape (albumId/spaceId carried along by the caller).
    // Defaults to onBack's no-op-if-absent behavior so existing callers/tests don't need updating.
    onNavigateToPhoto: (String) -> Unit = {}
) {
    val container = LocalAppContainer.current
    val canMoveToMain = spaceId != null

    val viewModel: PhotoDetailViewModel = viewModel(
        key = "photo-$photoId",
        factory = PhotoDetailViewModelFactory(
            container.photoApi,
            photoId,
            albumId,
            canMoveToMain,
            initialPhoto = PhotoBrowsingContext.getPhoto(photoId)
        )
    )
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val videoDataSourceFactory = remember { container.videoDataSourceFactory() }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEditorPicker by remember { mutableStateOf(false) }
    var availableEditors by remember { mutableStateOf<List<ExternalEditorApp>>(emptyList()) }
    var pendingEditUri by remember { mutableStateOf<Uri?>(null) }
    var confirmedEditedUri by remember { mutableStateOf<Uri?>(null) }
    var editSnapshot by remember { mutableStateOf<EditSnapshot?>(null) }
    var showSaveChoiceDialog by remember { mutableStateOf(false) }
    var isPreparingEdit by remember { mutableStateOf(false) }
    var imageRevision by remember(photoId) { mutableLongStateOf(System.currentTimeMillis()) }

    fun cleanupPendingEdit() {
        val uri = pendingEditUri
        if (uri != null && uri.authority == "media") {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete staged MediaStore uri: ${e.message}")
            }
        }
        val editsDir = File(context.cacheDir, "edits")
        if (editsDir.exists()) {
            editsDir.deleteRecursively()
        }
        pendingEditUri = null
        confirmedEditedUri = null
        editSnapshot = null
    }

    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = pendingEditUri
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            var finalEditedUri: Uri? = null

            // 1. Did the external editor return a result URI?
            val returnedUri = result.data?.data
            if (returnedUri != null) {
                finalEditedUri = returnedUri
            } else {
                // 2. Check if EditedMediaRelocator found new output in MediaStore
                val snapshot = editSnapshot
                if (snapshot != null) {
                    val outputs = withContext(Dispatchers.IO) {
                        EditedMediaRelocator.findEditedOutputs(context, snapshot)
                    }
                    if (outputs.isNotEmpty()) {
                        finalEditedUri = outputs.first().uri
                    }
                }
            }

            // 3. Fallback: check private cache file if using FileProvider
            if (finalEditedUri == null && uri.authority != "media") {
                val file = File(context.cacheDir, "edits/" + (uri.lastPathSegment ?: ""))
                if (file.exists() && file.length() > 0L) {
                    finalEditedUri = uri
                }
            }

            if (finalEditedUri != null) {
                confirmedEditedUri = finalEditedUri
                showSaveChoiceDialog = true
            } else {
                cleanupPendingEdit()
            }
        }
    }

    fun launchEditorWithApp(app: ExternalEditorApp) {
        val loaded = state as? PhotoDetailUiState.Loaded ?: return
        val photo = loaded.photo
        coroutineScope.launch {
            isPreparingEdit = true
            try {
                val ext = photo.title.substringAfterLast('.', "")
                    .ifBlank { if (photo.contentType?.contains("png") == true) "png" else "jpg" }
                val mimeType = photo.contentType?.ifBlank { "image/jpeg" } ?: "image/jpeg"

                var targetUri: Uri? = null
                var editSnapshotObj: EditSnapshot? = null

                // Staging in MediaStore allows Google Photos and OEM editors to edit without "cannot edit image" error
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "iPhoto_edit_${photo.id}.$ext")
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/iPhoto_edits")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    targetUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (targetUri != null) {
                        viewModel.downloadOriginalToUri(context.contentResolver, targetUri)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val updateValues = ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }
                            context.contentResolver.update(targetUri, updateValues, null, null)
                        }
                        editSnapshotObj = EditedMediaRelocator.snapshot(
                            context = context,
                            mediaUri = targetUri,
                            isVideo = false,
                            fileLastModifiedMs = System.currentTimeMillis(),
                            editorPackage = app.packageName
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stage edit in MediaStore, falling back to FileProvider: ${e.message}")
                    targetUri = null
                }

                if (targetUri == null) {
                    val editsDir = File(context.cacheDir, "edits").apply { mkdirs() }
                    val tempFile = File(editsDir, "edit_${photo.id}.$ext")
                    viewModel.downloadOriginalToCache(tempFile)
                    targetUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    )
                }

                pendingEditUri = targetUri
                editSnapshot = editSnapshotObj
                val intent = SystemEditorHelper.buildTargetIntent(app, targetUri, mimeType)
                editLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare photo for editing", e)
                Toast.makeText(context, "Failed to prepare photo for edit: ${e.message}", Toast.LENGTH_SHORT).show()
                cleanupPendingEdit()
            } finally {
                isPreparingEdit = false
            }
        }
    }

    val onEditPhotoClick: () -> Unit = {
        val loaded = state as? PhotoDetailUiState.Loaded
        if (loaded != null) {
            val photo = loaded.photo
            val mimeType = photo.contentType?.ifBlank { "image/*" } ?: "image/*"
            val dummyUri = Uri.parse("content://${context.packageName}.fileprovider/cache/edits/preview.jpg")
            val editors = SystemEditorHelper.queryAvailableEditors(context, dummyUri, mimeType)
            availableEditors = editors
            if (editors.isEmpty()) {
                Toast.makeText(context, "No gallery or editor app found on device", Toast.LENGTH_SHORT).show()
            } else {
                val savedPkg = container.syncPrefs.defaultEditorPackage
                val savedApp = savedPkg?.let { pkg -> editors.find { it.packageName == pkg } }
                if (savedApp != null) {
                    launchEditorWithApp(savedApp)
                } else {
                    showEditorPicker = true
                }
            }
        }
    }

    // Landscape drops the fixed top/bottom app bars in favor of a single collapsed bar overlaid on
    // top of a truly full-screen image - matches the fullscreen-with-collapsible-controls pattern
    // most photo viewers use in landscape, where screen height is the scarce resource.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(isLandscape) { if (isLandscape) controlsVisible = true }

    val onEditDateClick: () -> Unit = { showDatePicker = true }
    val onAddToAlbumClick: () -> Unit = { showAlbumPicker = true }
    val onRemoveFromAlbumClick: () -> Unit = { viewModel.removeFromAlbum(container.albumApi, container::notifyAlbumsChanged) }
    val onMoveToMainClick: () -> Unit = { viewModel.moveToMain(container.photoApi) }

    // Computed fresh per photoId against whatever grid last populated PhotoBrowsingContext (see its
    // own kdoc) - not reactive, but the browsed list isn't expected to change while viewing a photo.
    val activePhotoId = (state as? PhotoDetailUiState.Loaded)?.photo?.id ?: photoId
    val browsingIds = remember(activePhotoId) { PhotoBrowsingContext.photoIds }
    val currentIndex = remember(activePhotoId, browsingIds) { browsingIds.indexOf(activePhotoId) }
    val previousPhotoId = currentIndex.takeIf { it > 0 }?.let { browsingIds[it - 1] }
    val nextPhotoId = currentIndex.takeIf { it in browsingIds.indices && it < browsingIds.size - 1 }
        ?.let { browsingIds[it + 1] }

    val swipeModifier = Modifier.pointerInput(previousPhotoId, nextPhotoId) {
        var totalDragX = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDragX = 0f },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                totalDragX += dragAmount
            },
            onDragEnd = {
                val swipeThreshold = 50.dp.toPx()
                if (totalDragX < -swipeThreshold && nextPhotoId != null) {
                    onNavigateToPhoto(nextPhotoId)
                } else if (totalDragX > swipeThreshold && previousPhotoId != null) {
                    onNavigateToPhoto(previousPhotoId)
                }
                totalDragX = 0f
            },
            onDragCancel = {
                totalDragX = 0f
            }
        )
    }

    LaunchedEffect(state) {
        if (state is PhotoDetailUiState.Removed) {
            container.notifyLibraryChanged()
            onBack()
        }
    }

    Scaffold(
        contentWindowInsets = if (isLandscape) WindowInsets(
            0,
            0,
            0,
            0
        ) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!isLandscape) {
                TopAppBar(
                    title = { Text("") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            }
        },
        bottomBar = {
            if (!isLandscape) {
                val loaded = state as? PhotoDetailUiState.Loaded
                if (loaded != null) {
                    BottomAppBar(containerColor = Color.Black) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            PhotoActionButtons(
                                loaded = loaded,
                                canRemoveFromAlbum = albumId != null,
                                canMoveToMain = viewModel.canMoveToMain,
                                onEditPhoto = onEditPhotoClick,
                                onEditDate = onEditDateClick,
                                onAddToAlbum = onAddToAlbumClick,
                                onRemoveFromAlbum = onRemoveFromAlbumClick,
                                onMoveToMain = onMoveToMainClick,
                                onMoveToGarbage = { viewModel.moveToGarbage(onLibraryChanged = { container.notifyLibraryChanged() }) }
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .then(swipeModifier)
        ) {
            when (val current = state) {
                is PhotoDetailUiState.Loading, is PhotoDetailUiState.Removed ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                is PhotoDetailUiState.Failed ->
                    Text(
                        current.message,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )

                is PhotoDetailUiState.Loaded -> {
                    // In landscape the top/bottom bars are gone entirely (see Scaffold above), so
                    // the image itself is the only tap target left to collapse/reveal the overlay
                    // options bar below - portrait keeps the fixed bars and isn't tappable this way.
                    val mediaModifier = Modifier
                        .fillMaxSize()
                        .then(swipeModifier)
                        .then(
                            if (isLandscape) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { controlsVisible = !controlsVisible }
                            } else {
                                Modifier
                            }
                        )
                    if (current.photo.contentType?.startsWith("video") == true) {
                        // Coil/AsyncImage (below) can't decode video at all - this mirrors the web
                        // app's own <video> element, both against the same /stream endpoint rather
                        // than /original (see VideoPlayer's kdoc).
                        val streamUrl =
                            container.absoluteUrl("/api/photos/${current.photo.id}/stream?t=$imageRevision")
                        VideoPlayer(
                            streamUrl = streamUrl,
                            dataSourceFactory = videoDataSourceFactory,
                            modifier = mediaModifier,
                            mimeType = current.photo.contentType
                        )
                    } else {
                        val imageUrl =
                            container.absoluteUrl("/api/photos/${current.photo.id}/original?t=$imageRevision")
                        AsyncImage(
                            model = imageUrl,
                            imageLoader = container.imageLoader,
                            contentDescription = current.photo.title,
                            contentScale = ContentScale.Fit,
                            onState = { state ->
                                when (state) {
                                    is AsyncImagePainter.State.Error -> Log.e(
                                        TAG,
                                        "Failed to load $imageUrl (contentType=${current.photo.contentType})",
                                        state.result.throwable
                                    )

                                    is AsyncImagePainter.State.Success -> Log.d(
                                        TAG,
                                        "Loaded $imageUrl"
                                    )

                                    else -> {}
                                }
                            },
                            modifier = mediaModifier
                        )
                    }
                    if (isLandscape && controlsVisible) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .displayCutoutPadding()
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .navigationBarsPadding()
                                .displayCutoutPadding()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            PhotoActionButtons(
                                loaded = current,
                                canRemoveFromAlbum = albumId != null,
                                canMoveToMain = viewModel.canMoveToMain,
                                onEditPhoto = onEditPhotoClick,
                                onEditDate = onEditDateClick,
                                onAddToAlbum = onAddToAlbumClick,
                                onRemoveFromAlbum = onRemoveFromAlbumClick,
                                onMoveToMain = onMoveToMainClick,
                                onMoveToGarbage = { viewModel.moveToGarbage(onLibraryChanged = { container.notifyLibraryChanged() }) }
                            )
                        }
                    }
                    val showNavigationArrows = !isLandscape || controlsVisible
                    if (showNavigationArrows && previousPhotoId != null) {
                        IconButton(
                            onClick = { onNavigateToPhoto(previousPhotoId) },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(8.dp)
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.ChevronLeft,
                                contentDescription = "Previous photo",
                                tint = Color.White
                            )
                        }
                    }
                    if (showNavigationArrows && nextPhotoId != null) {
                        IconButton(
                            onClick = { onNavigateToPhoto(nextPhotoId) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(8.dp)
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = "Next photo",
                                tint = Color.White
                            )
                        }
                    }
                    if (current.actionError != null) {
                        Text(
                            current.actionError,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.error)
                                .clickable { viewModel.dismissActionError() }
                                .padding(12.dp)
                        )
                    }
                }
            }
            // Loading/Failed states render no bar of their own in landscape (see topBar above), so
            // the back button still needs a way to reach the user - the Loaded branch's own overlay
            // bar above already covers that case for a loaded photo.
            if (isLandscape && state !is PhotoDetailUiState.Loaded) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
        }
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(
            albumApi = container.albumApi,
            onDismiss = { showAlbumPicker = false },
            onAlbumPicked = { pickedAlbumId ->
                viewModel.addToAlbum(container.albumApi, pickedAlbumId, container::notifyAlbumsChanged)
                showAlbumPicker = false
            },
            onCreateAlbum = { name ->
                viewModel.createAlbumAndAdd(container.albumApi, name, container::notifyAlbumsChanged)
                showAlbumPicker = false
            }
        )
    }

    if (showDatePicker) {
        val loaded = state as? PhotoDetailUiState.Loaded
        if (loaded != null) {
            val initial = try {
                LocalDateTime.parse(
                    loaded.photo.fullDateTime,
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
                )
            } catch (e: Exception) {
                LocalDateTime.now()
            }
            DateTimePickerDialog(
                initial = initial,
                onDismiss = { showDatePicker = false },
                onConfirm = { newDate ->
                    viewModel.updateDate(newDate)
                    showDatePicker = false
                }
            )
        }
    }

    if (showEditorPicker) {
        EditorPickerDialog(
            apps = availableEditors,
            hasSavedDefault = container.syncPrefs.defaultEditorPackage != null,
            onDismiss = { showEditorPicker = false },
            onResetDefault = {
                container.syncPrefs.defaultEditorPackage = null
                showEditorPicker = false
                Toast.makeText(context, "Default editor reset", Toast.LENGTH_SHORT).show()
            },
            onSelectApp = { app, rememberChoice ->
                if (rememberChoice) container.syncPrefs.defaultEditorPackage = app.packageName
                showEditorPicker = false
                launchEditorWithApp(app)
            }
        )
    }

    if (isPreparingEdit) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Preparing Photo") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text("Downloading photo for editing...")
                }
            }
        )
    }

    if (showSaveChoiceDialog) {
        val targetUri = confirmedEditedUri ?: pendingEditUri
        var selectedChoice by remember { mutableStateOf(false) } // false = replace, true = save as new
        AlertDialog(
            onDismissRequest = {
                showSaveChoiceDialog = false
                cleanupPendingEdit()
            },
            icon = {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text("Save Edited Photo", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How would you like to save your changes?")

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedChoice = false }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = !selectedChoice,
                            onClick = { selectedChoice = false }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Replace original", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Overwrites this photo on the server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedChoice = true }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedChoice,
                            onClick = { selectedChoice = true }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Save as new", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Keeps the original and adds a new photo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveChoiceDialog = false
                        val saveAsNew = selectedChoice
                        if (targetUri != null) {
                            val activeId = (state as? PhotoDetailUiState.Loaded)?.photo?.id ?: photoId
                            val originalUrl = container.absoluteUrl("/api/photos/$activeId/original")
                            val thumbUrl = container.absoluteUrl("/api/photos/$activeId/thumbnail")
                            viewModel.saveEditedPhoto(context.contentResolver, targetUri, saveAsNew) { updatedPhoto ->
                                try {
                                    container.imageLoader.diskCache?.clear()
                                } catch (_: Exception) {
                                    container.imageLoader.diskCache?.remove(originalUrl)
                                    container.imageLoader.diskCache?.remove(thumbUrl)
                                }
                                container.imageLoader.memoryCache?.clear()
                                imageRevision = System.currentTimeMillis()

                                container.notifyLibraryChanged()
                                if (albumId != null) {
                                    container.notifyAlbumsChanged()
                                }

                                if (saveAsNew) {
                                    PhotoBrowsingContext.insertPhotoAfter(activeId, updatedPhoto)
                                    onNavigateToPhoto(updatedPhoto.id)
                                    Toast.makeText(context, "Saved as new photo", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Photo updated successfully", Toast.LENGTH_SHORT).show()
                                }
                                cleanupPendingEdit()
                            }
                        } else {
                            cleanupPendingEdit()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSaveChoiceDialog = false
                        cleanupPendingEdit()
                    }
                ) {
                    Text("Discard")
                }
            }
        )
    }
}

// Shared between the portrait BottomAppBar and the landscape collapsed overlay bar so both stay
// in sync automatically - one row of action icons, not two definitions of "what actions exist".
@Composable
private fun PhotoActionButtons(
    loaded: PhotoDetailUiState.Loaded,
    canRemoveFromAlbum: Boolean,
    canMoveToMain: Boolean,
    onEditPhoto: () -> Unit,
    onEditDate: () -> Unit,
    onAddToAlbum: () -> Unit,
    onRemoveFromAlbum: () -> Unit,
    onMoveToMain: () -> Unit,
    onMoveToGarbage: () -> Unit
) {
    val isImage = loaded.photo.contentType?.startsWith("video") != true
    if (isImage) {
        IconButton(onClick = onEditPhoto, enabled = !loaded.isMutating) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit photo", tint = Color.White)
        }
    }
    IconButton(onClick = onEditDate, enabled = !loaded.isMutating) {
        Icon(Icons.Filled.Event, contentDescription = "Edit date", tint = Color.White)
    }
    IconButton(onClick = onAddToAlbum, enabled = !loaded.isMutating) {
        Icon(Icons.Filled.PhotoAlbum, contentDescription = "Add to album", tint = Color.White)
    }
    if (canRemoveFromAlbum) {
        IconButton(onClick = onRemoveFromAlbum, enabled = !loaded.isMutating) {
            Icon(
                Icons.Filled.PlaylistRemove,
                contentDescription = "Remove from album",
                tint = Color.White
            )
        }
    }
    if (canMoveToMain) {
        IconButton(onClick = onMoveToMain, enabled = !loaded.isMutating) {
            Icon(
                Icons.AutoMirrored.Filled.DriveFileMove,
                contentDescription = "Move to Main Space",
                tint = Color.White
            )
        }
    }
    IconButton(onClick = onMoveToGarbage, enabled = !loaded.isMutating) {
        Icon(Icons.Filled.Delete, contentDescription = "Move to garbage", tint = Color.White)
    }
}
