package org.iphoto.mobile.ui.common

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.iphoto.mobile.AppContainer
import org.iphoto.mobile.sync.LocalAsset

private const val TAG = "DeviceTrashRequester"

/**
 * Composable-scoped helper for "also remove this from my phone" (SpaceDetailScreen/PhotoDetailScreen,
 * synced Spaces only): prefers the phone's real system trash for files MediaStore indexes (standard
 * camera-roll locations like DCIM/Camera) - one system confirmation dialog, then the file lands in
 * Gallery/Photos "Recently deleted", recoverable there like any normal deletion - falling back to the
 * app's own hidden .iPhotoTrash subfolder (silent, no dialog) only for files MediaStore doesn't know
 * about, or below API 30 where MediaStore.createTrashRequest doesn't exist.
 *
 * Needs an ActivityResultLauncher, so must be built once per composition via [rememberDeviceTrashRequester]
 * rather than constructed directly - the launcher has to be registered before the composable it's used
 * in reaches STARTED.
 */
class DeviceTrashRequester internal constructor(
    private val context: Context,
    private val container: AppContainer,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
    private val resultChannel: Channel<Boolean>
) {
    private data class Resolved(val asset: LocalAsset, val mediaUri: Uri?)

    suspend fun trash(folderUri: Uri, hashes: Set<String>) {
        val repo = container.syncRepository()
        val matched = repo.findLocalAssetsForHashes(folderUri, hashes)
        if (matched.isEmpty()) return

        val resolved = matched.map { Resolved(it, repo.toMediaStoreUri(it)) }
        val mediaStoreResolved = resolved.filter { it.mediaUri != null }
        val unresolved = resolved.filter { it.mediaUri == null }.map { it.asset }

        if (mediaStoreResolved.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = mediaStoreResolved.map { it.mediaUri!! }
            try {
                val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                val approved = resultChannel.receive()
                if (!approved) {
                    Log.w(TAG, "trash: user declined the system trash confirmation for ${uris.size} file(s) - left on device")
                }
            } catch (e: Exception) {
                Log.w(TAG, "trash: MediaStore.createTrashRequest failed for ${uris.size} file(s), falling back to the app folder", e)
                repo.trashLocalFilesInAppFolder(folderUri, mediaStoreResolved.map { it.asset })
            }
        } else if (mediaStoreResolved.isNotEmpty()) {
            // Below API 30 - MediaStore.createTrashRequest doesn't exist yet.
            repo.trashLocalFilesInAppFolder(folderUri, mediaStoreResolved.map { it.asset })
        }

        if (unresolved.isNotEmpty()) {
            // Fire-and-forget on the process-lifetime scope: no UI/dialog involved for this part, so
            // it doesn't need to be tied to (or block) this screen - see AppContainer.applicationScope.
            container.applicationScope.launch { repo.trashLocalFilesInAppFolder(folderUri, unresolved) }
        }
    }
}

@Composable
fun rememberDeviceTrashRequester(container: AppContainer): DeviceTrashRequester {
    val context = LocalContext.current
    val resultChannel = remember { Channel<Boolean>(Channel.CONFLATED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        resultChannel.trySend(result.resultCode == Activity.RESULT_OK)
    }
    return remember(container) { DeviceTrashRequester(context, container, launcher, resultChannel) }
}
