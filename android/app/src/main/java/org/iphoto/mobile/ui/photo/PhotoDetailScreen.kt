package org.iphoto.mobile.ui.photo

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.ui.common.AlbumPickerDialog
import org.iphoto.mobile.ui.common.DateTimePickerDialog
import org.iphoto.mobile.ui.common.PhotoBrowsingContext
import org.iphoto.mobile.ui.common.rememberDeviceTrashRequester

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
    // Same synced-space check as SpaceDetailScreen's banner - only set when this lightbox is
    // showing a photo from the Space currently synced to a device folder.
    val syncPrefs = container.syncPrefs
    val isSyncedSpace = spaceId != null && syncPrefs.spaceId == spaceId && syncPrefs.folderUri != null
    val syncedFolderUri = syncPrefs.folderUri
    val canMoveToMain = spaceId != null
    val deviceTrashRequester = rememberDeviceTrashRequester(container)
    val onPhotoMovedToGarbage: (suspend (Photo) -> Unit)? = remember(spaceId, isSyncedSpace, syncedFolderUri, deviceTrashRequester) {
        if (isSyncedSpace && syncedFolderUri != null) {
            { photo: Photo ->
                val hash = photo.fileHash?.takeIf(String::isNotBlank)
                if (hash != null) {
                    deviceTrashRequester.trash(Uri.parse(syncedFolderUri), setOf(hash))
                }
            }
        } else null
    }
    // Mirrors SpaceDetailScreen's onPhotosExcludedFromSync/onPhotosMovedToMain, singular.
    val onPhotoExcludedFromSync: (suspend (Photo) -> Unit)? = remember(spaceId, isSyncedSpace) {
        if (isSyncedSpace) {
            { photo: Photo ->
                val hash = photo.fileHash
                if (!hash.isNullOrBlank()) {
                    syncPrefs.excludeAsset(hash, photo.title, photo.id)
                }
            }
        } else null
    }
    val onPhotoMovedToMain: (suspend (Photo) -> Unit)? = remember(spaceId, isSyncedSpace) {
        if (isSyncedSpace) {
            { photo: Photo ->
                val hash = photo.fileHash
                if (!hash.isNullOrBlank()) {
                    syncPrefs.markMovedToMain(hash, photo.title, photo.id)
                }
            }
        } else null
    }
    val viewModel: PhotoDetailViewModel = viewModel(
        key = "photo-$photoId",
        factory = PhotoDetailViewModelFactory(
            container.photoApi,
            photoId,
            albumId,
            onPhotoMovedToGarbage,
            canMoveToMain,
            onPhotoMovedToMain,
            onPhotoExcludedFromSync
        )
    )
    val state by viewModel.uiState.collectAsState()
    // Built once per screen instance (not per photo) - cheap to share across whatever VideoPlayer
    // instances this screen creates, same as container.imageLoader being shared across AsyncImage.
    val videoDataSourceFactory = remember { container.videoDataSourceFactory() }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showMoveToMainDeviceConfirm by remember { mutableStateOf(false) }

    // Landscape drops the fixed top/bottom app bars in favor of a single collapsed bar overlaid on
    // top of a truly full-screen image - matches the fullscreen-with-collapsible-controls pattern
    // most photo viewers use in landscape, where screen height is the scarce resource.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(isLandscape) { if (isLandscape) controlsVisible = true }

    val onEditDateClick: () -> Unit = { showDatePicker = true }
    val onAddToAlbumClick: () -> Unit = { showAlbumPicker = true }
    val onRemoveFromAlbumClick: () -> Unit = { viewModel.removeFromAlbum(container.albumApi) }
    val onExcludeFromSyncClick: () -> Unit = { viewModel.excludeFromSync(container.photoApi) }
    val onMoveToMainClick: () -> Unit = {
        // Same policy as PhotoGridContent's selection action bar: only ask "also remove from
        // device?" when this Space is actually the one synced to a device folder.
        if (viewModel.canExcludeFromSync) {
            showMoveToMainDeviceConfirm = true
        } else {
            viewModel.moveToMain(container.photoApi)
        }
    }

    // Computed fresh per photoId against whatever grid last populated PhotoBrowsingContext (see its
    // own kdoc) - not reactive, but the browsed list isn't expected to change while viewing a photo.
    val browsingIds = remember(photoId) { PhotoBrowsingContext.photoIds }
    val currentIndex = remember(photoId, browsingIds) { browsingIds.indexOf(photoId) }
    val previousPhotoId = currentIndex.takeIf { it > 0 }?.let { browsingIds[it - 1] }
    val nextPhotoId = currentIndex.takeIf { it in browsingIds.indices && it < browsingIds.size - 1 }?.let { browsingIds[it + 1] }

    LaunchedEffect(state) {
        if (state is PhotoDetailUiState.Removed) onBack()
    }

    Scaffold(
        topBar = {
            if (!isLandscape) {
                TopAppBar(
                    title = { Text("") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            PhotoActionButtons(
                                loaded = loaded,
                                canRemoveFromAlbum = albumId != null,
                                canExcludeFromSync = viewModel.canExcludeFromSync,
                                canMoveToMain = viewModel.canMoveToMain,
                                onEditDate = onEditDateClick,
                                onAddToAlbum = onAddToAlbumClick,
                                onRemoveFromAlbum = onRemoveFromAlbumClick,
                                onExcludeFromSync = onExcludeFromSyncClick,
                                onMoveToMain = onMoveToMainClick,
                                onMoveToGarbage = viewModel::moveToGarbage
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                is PhotoDetailUiState.Loading, is PhotoDetailUiState.Removed ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is PhotoDetailUiState.Failed ->
                    Text(
                        current.message,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                is PhotoDetailUiState.Loaded -> {
                    // In landscape the top/bottom bars are gone entirely (see Scaffold above), so
                    // the image itself is the only tap target left to collapse/reveal the overlay
                    // options bar below - portrait keeps the fixed bars and isn't tappable this way.
                    val mediaModifier = if (isLandscape) {
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { controlsVisible = !controlsVisible }
                    } else {
                        Modifier.fillMaxSize()
                    }
                    if (current.photo.contentType?.startsWith("video") == true) {
                        // Coil/AsyncImage (below) can't decode video at all - this mirrors the web
                        // app's own <video> element, both against the same /stream endpoint rather
                        // than /original (see VideoPlayer's kdoc).
                        val streamUrl = container.absoluteUrl("/api/photos/${current.photo.id}/stream")
                        VideoPlayer(
                            streamUrl = streamUrl,
                            dataSourceFactory = videoDataSourceFactory,
                            modifier = mediaModifier
                        )
                    } else {
                        val imageUrl = container.absoluteUrl("/api/photos/${current.photo.id}/original")
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
                                    is AsyncImagePainter.State.Success -> Log.d(TAG, "Loaded $imageUrl")
                                    else -> {}
                                }
                            },
                            modifier = mediaModifier
                        )
                    }
                    if (isLandscape && controlsVisible) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                                PhotoActionButtons(
                                    loaded = current,
                                    canRemoveFromAlbum = albumId != null,
                                    canExcludeFromSync = viewModel.canExcludeFromSync,
                                    canMoveToMain = viewModel.canMoveToMain,
                                    onEditDate = onEditDateClick,
                                    onAddToAlbum = onAddToAlbumClick,
                                    onRemoveFromAlbum = onRemoveFromAlbumClick,
                                    onExcludeFromSync = onExcludeFromSyncClick,
                                    onMoveToMain = onMoveToMainClick,
                                    onMoveToGarbage = viewModel::moveToGarbage
                                )
                            }
                        }
                    }
                    if (previousPhotoId != null) {
                        IconButton(
                            onClick = { onNavigateToPhoto(previousPhotoId) },
                            modifier = Modifier.align(Alignment.CenterStart).padding(4.dp).size(48.dp)
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous photo", tint = Color.White)
                        }
                    }
                    if (nextPhotoId != null) {
                        IconButton(
                            onClick = { onNavigateToPhoto(nextPhotoId) },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(4.dp).size(48.dp)
                        ) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Next photo", tint = Color.White)
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
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }
        }
    }

    if (showMoveToMainDeviceConfirm) {
        AlertDialog(
            onDismissRequest = { showMoveToMainDeviceConfirm = false },
            title = { Text("Remove from this phone too?") },
            text = {
                Text(
                    "This Space is synced to a folder on this phone. Moving to Main Space keeps " +
                        "the photo, but do you also want to remove the matching file on this phone?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.moveToMain(container.photoApi, alsoRemoveFromDevice = true)
                    showMoveToMainDeviceConfirm = false
                }) { Text("Remove from device") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.moveToMain(container.photoApi, alsoRemoveFromDevice = false)
                    showMoveToMainDeviceConfirm = false
                }) { Text("Keep on device") }
            }
        )
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(
            albumApi = container.albumApi,
            onDismiss = { showAlbumPicker = false },
            onAlbumPicked = { pickedAlbumId ->
                viewModel.addToAlbum(container.albumApi, pickedAlbumId)
                showAlbumPicker = false
            },
            onCreateAlbum = { name ->
                viewModel.createAlbumAndAdd(container.albumApi, name)
                showAlbumPicker = false
            }
        )
    }

    if (showDatePicker) {
        val loaded = state as? PhotoDetailUiState.Loaded
        if (loaded != null) {
            val initial = try {
                LocalDateTime.parse(loaded.photo.fullDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
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
}

// Shared between the portrait BottomAppBar and the landscape collapsed overlay bar so both stay
// in sync automatically - one row of action icons, not two definitions of "what actions exist".
@Composable
private fun PhotoActionButtons(
    loaded: PhotoDetailUiState.Loaded,
    canRemoveFromAlbum: Boolean,
    canExcludeFromSync: Boolean,
    canMoveToMain: Boolean,
    onEditDate: () -> Unit,
    onAddToAlbum: () -> Unit,
    onRemoveFromAlbum: () -> Unit,
    onExcludeFromSync: () -> Unit,
    onMoveToMain: () -> Unit,
    onMoveToGarbage: () -> Unit
) {
    IconButton(onClick = onEditDate, enabled = !loaded.isMutating) {
        Icon(Icons.Filled.Event, contentDescription = "Edit date", tint = Color.White)
    }
    IconButton(onClick = onAddToAlbum, enabled = !loaded.isMutating) {
        Icon(Icons.Filled.PhotoAlbum, contentDescription = "Add to album", tint = Color.White)
    }
    if (canRemoveFromAlbum) {
        IconButton(onClick = onRemoveFromAlbum, enabled = !loaded.isMutating) {
            Icon(Icons.Filled.PlaylistRemove, contentDescription = "Remove from album", tint = Color.White)
        }
    }
    if (canExcludeFromSync) {
        IconButton(onClick = onExcludeFromSync, enabled = !loaded.isMutating) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = "Exclude from sync", tint = Color.White)
        }
    }
    if (canMoveToMain) {
        IconButton(onClick = onMoveToMain, enabled = !loaded.isMutating) {
            Icon(Icons.Filled.DriveFileMove, contentDescription = "Move to Main Space", tint = Color.White)
        }
    }
    IconButton(onClick = onMoveToGarbage, enabled = !loaded.isMutating) {
        Icon(Icons.Filled.Delete, contentDescription = "Move to garbage", tint = Color.White)
    }
}
