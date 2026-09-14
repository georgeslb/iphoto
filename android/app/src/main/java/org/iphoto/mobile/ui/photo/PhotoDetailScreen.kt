package org.iphoto.mobile.ui.photo

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            val loaded = state as? PhotoDetailUiState.Loaded
            if (loaded != null) {
                BottomAppBar(containerColor = Color.Black) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { showDatePicker = true }, enabled = !loaded.isMutating) {
                            Icon(Icons.Filled.Event, contentDescription = "Edit date", tint = Color.White)
                        }
                        IconButton(onClick = { showAlbumPicker = true }, enabled = !loaded.isMutating) {
                            Icon(Icons.Filled.PhotoAlbum, contentDescription = "Add to album", tint = Color.White)
                        }
                        if (albumId != null) {
                            IconButton(onClick = { viewModel.removeFromAlbum(container.albumApi) }, enabled = !loaded.isMutating) {
                                Icon(Icons.Filled.PlaylistRemove, contentDescription = "Remove from album", tint = Color.White)
                            }
                        }
                        if (viewModel.canExcludeFromSync) {
                            IconButton(onClick = { viewModel.excludeFromSync(container.photoApi) }, enabled = !loaded.isMutating) {
                                Icon(Icons.Filled.VisibilityOff, contentDescription = "Exclude from sync", tint = Color.White)
                            }
                        }
                        if (viewModel.canMoveToMain) {
                            IconButton(
                                onClick = {
                                    // Same policy as PhotoGridContent's selection action bar: only
                                    // ask "also remove from device?" when this Space is actually
                                    // the one synced to a device folder.
                                    if (viewModel.canExcludeFromSync) {
                                        showMoveToMainDeviceConfirm = true
                                    } else {
                                        viewModel.moveToMain(container.photoApi)
                                    }
                                },
                                enabled = !loaded.isMutating
                            ) {
                                Icon(Icons.Filled.DriveFileMove, contentDescription = "Move to Main Space", tint = Color.White)
                            }
                        }
                        IconButton(onClick = viewModel::moveToGarbage, enabled = !loaded.isMutating) {
                            Icon(Icons.Filled.Delete, contentDescription = "Move to garbage", tint = Color.White)
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
                    if (current.photo.contentType?.startsWith("video") == true) {
                        // Coil/AsyncImage (below) can't decode video at all - this mirrors the web
                        // app's own <video> element, both against the same /stream endpoint rather
                        // than /original (see VideoPlayer's kdoc).
                        val streamUrl = container.absoluteUrl("/api/photos/${current.photo.id}/stream")
                        VideoPlayer(
                            streamUrl = streamUrl,
                            dataSourceFactory = videoDataSourceFactory,
                            modifier = Modifier.fillMaxSize()
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
                            modifier = Modifier.fillMaxSize()
                        )
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
