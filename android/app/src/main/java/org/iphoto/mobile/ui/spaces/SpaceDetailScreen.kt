package org.iphoto.mobile.ui.spaces

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.sync.SyncWorker
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModel
import org.iphoto.mobile.ui.common.PagedPhotoGridViewModelFactory
import org.iphoto.mobile.ui.common.PhotoGridContent

// How often to poll for new thumbnails while a sync is actively running against the
// currently-viewed space - frequent enough to feel live, infrequent enough not to hammer
// GET /api/spaces/{id}/photos every frame.
private const val LIVE_REFRESH_INTERVAL_MS = 8_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceDetailScreen(spaceId: String, onPhotoClick: (String) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    // Read once per composition of this space, same as the rest of this screen's remember(spaceId)
    // usages - SyncPrefs only ever changes from the Sync tab, which recreates this screen's
    // backstack entry on return, so a live observer isn't needed for a status banner like this.
    val syncPrefs = container.syncPrefs
    val isSyncedSpace = syncPrefs.spaceId == spaceId && syncPrefs.folderUri != null
    val syncedFolderUri = syncPrefs.folderUri

    val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = remember(spaceId) {
        { page, size ->
            val response = container.photoApi.listForSpace(spaceId, page, size)
            response.content to response.page.isLast
        }
    }
    // Only set for the Space currently synced to a device folder - mirrors "move to garbage" onto
    // the matching local file right away (see PagedPhotoGridViewModel.onPhotosMovedToGarbage).
    val onPhotosMovedToGarbage: (suspend (List<Photo>) -> Unit)? = remember(spaceId, isSyncedSpace, syncedFolderUri) {
        if (isSyncedSpace && syncedFolderUri != null) {
            { photos: List<Photo> ->
                val hashes = photos.mapNotNull { it.fileHash?.takeIf(String::isNotBlank) }.toSet()
                container.syncRepository().trashLocalFilesForHashes(Uri.parse(syncedFolderUri), hashes)
            }
        } else null
    }
    // Also only set for the currently synced Space - see PagedPhotoGridViewModel.canExcludeFromSync.
    val onPhotosExcludedFromSync: (suspend (List<Photo>) -> Unit)? = remember(spaceId, isSyncedSpace) {
        if (isSyncedSpace) {
            { photos: List<Photo> ->
                photos.forEach { photo ->
                    val hash = photo.fileHash
                    if (!hash.isNullOrBlank()) {
                        syncPrefs.excludeAsset(hash, photo.title, photo.id)
                    }
                }
            }
        } else null
    }
    // Move to Main Space is offered from every Space, synced or not - only the bookkeeping below
    // (so a synced folder's file isn't re-uploaded next sync) is conditional on isSyncedSpace.
    val onPhotosMovedToMain: (suspend (List<Photo>) -> Unit)? = remember(spaceId, isSyncedSpace) {
        if (isSyncedSpace) {
            { photos: List<Photo> ->
                photos.forEach { photo ->
                    val hash = photo.fileHash
                    if (!hash.isNullOrBlank()) {
                        syncPrefs.markMovedToMain(hash, photo.title, photo.id)
                    }
                }
            }
        } else null
    }
    val viewModel: PagedPhotoGridViewModel = viewModel(
        key = "space-$spaceId",
        factory = PagedPhotoGridViewModelFactory(
            loadPage,
            onPhotosMovedToGarbage = onPhotosMovedToGarbage,
            onPhotosExcludedFromSync = onPhotosExcludedFromSync,
            canMoveToMain = true,
            onPhotosMovedToMain = onPhotosMovedToMain
        )
    )
    val state by viewModel.uiState.collectAsState()

    // Refresh whenever this destination becomes active again (navigating back into it, or the
    // app returning to the foreground while already on it) - otherwise photos uploaded by a
    // background sync while the user wasn't looking would only ever show up if the ViewModel
    // happened to get recreated.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshQuietly()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While a sync is actively running against *this* space, poll for new thumbnails so they
    // show up live instead of only after leaving and reopening the screen. collectLatest cancels
    // the polling loop the instant the WorkInfo reports the run is no longer RUNNING.
    if (isSyncedSpace) {
        val context = LocalContext.current
        LaunchedEffect(spaceId) {
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.PERIODIC_WORK_NAME)
                .collectLatest { infos ->
                    val running = infos.any { it.state == WorkInfo.State.RUNNING }
                    if (running) {
                        while (isActive) {
                            viewModel.refreshQuietly()
                            delay(LIVE_REFRESH_INTERVAL_MS)
                        }
                    }
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Space") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isSyncedSpace) {
                SyncStatusBanner(folderDisplayName = syncPrefs.folderDisplayName, lastSyncAt = syncPrefs.lastSyncAt)
            }
            PhotoGridContent(
                state = state,
                viewModel = viewModel,
                onPhotoClick = onPhotoClick,
                onLoadMore = viewModel::loadMore,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Tells the user this Space mirrors a folder on this phone (see the Sync tab / SyncScreen) - so
 * "why did this photo disappear/reappear" has an answer right where the photos are, not just on
 * the separate Sync screen.
 */
@Composable
private fun SyncStatusBanner(folderDisplayName: String?, lastSyncAt: Long) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column {
                Text(
                    "Synchronized with \"${folderDisplayName ?: "a folder"}\" on this phone",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (lastSyncAt > 0) {
                        "Last synced: ${DateFormat.getDateTimeInstance().format(Date(lastSyncAt))}"
                    } else {
                        "Not synced yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
