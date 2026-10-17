package org.iphoto.mobile.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Walks a SAF-picked folder tree looking for image/video files. Recurses into subfolders (common
 * for camera-roll-style layouts, e.g. Camera/2024/06) but caps depth so accidentally picking a
 * folder much higher up the tree than intended can't turn one sync tap into an unbounded scan.
 * Records each file's folder path relative to the root as it goes (see LocalAsset.relativeDir) so
 * LocalTrash can preserve it across a trash/restore round trip.
 */
object LocalFolderScanner {
    private const val MAX_DEPTH = 6

    // In-memory cache of video durations keyed by "uri|size|lastModified" so scanning a folder
    // never repeatedly opens MediaMetadataRetriever on the same video file.
    private val videoDurationCache = ConcurrentHashMap<String, Long>()

    private class MediaStoreVideoDurations(
        val byNameAndSize: Map<Pair<String, Long>, Long>,
        val byName: Map<String, Long>
    )

    fun scan(context: Context, treeUri: Uri): List<LocalAsset> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val mediaStoreDurations = queryMediaStoreVideoDurations(context)
        val results = mutableListOf<LocalAsset>()
        walk(context, root.uri, 0, "", mediaStoreDurations, results)
        return results
    }

    private fun walk(
        context: Context,
        dirUri: Uri,
        depth: Int,
        relativeDir: String,
        mediaStoreDurations: MediaStoreVideoDurations,
        out: MutableList<LocalAsset>
    ) {
        if (depth > MAX_DEPTH) return
        for (child in SafDir.listChildren(context, dirUri)) {
            when {
                // The local-trash subfolder (see LocalTrash) holds files the sync feature has
                // already moved out of the active folder - it must never be scanned back in as if
                // it were still-present content, or a garbaged photo's file would look "still
                // there" and computeDiff would never flag the Space photo for removal.
                child.isDirectory && child.name == LocalTrash.TRASH_DIR_NAME -> Unit
                child.isDirectory -> {
                    val childName = child.name ?: continue
                    val childRelativeDir = if (relativeDir.isEmpty()) childName else "$relativeDir/$childName"
                    walk(context, child.uri, depth + 1, childRelativeDir, mediaStoreDurations, out)
                }
                else -> {
                    val mimeType = child.mimeType.orEmpty()
                    if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                        val displayName = child.name ?: child.uri.lastPathSegment.orEmpty()
                        val duration = if (mimeType.startsWith("video/")) {
                            resolveVideoDuration(
                                context = context,
                                uri = child.uri,
                                displayName = displayName,
                                size = child.size,
                                lastModified = child.lastModified,
                                mediaStoreDurations = mediaStoreDurations
                            )
                        } else null
                        out.add(
                            LocalAsset(
                                uri = child.uri,
                                displayName = displayName,
                                size = child.size,
                                lastModified = child.lastModified,
                                mimeType = mimeType,
                                relativeDir = relativeDir,
                                duration = duration
                            )
                        )
                    }
                }
            }
        }
    }

    private fun resolveVideoDuration(
        context: Context,
        uri: Uri,
        displayName: String,
        size: Long,
        lastModified: Long,
        mediaStoreDurations: MediaStoreVideoDurations
    ): Long? {
        val cacheKey = "$uri|$size|$lastModified"
        videoDurationCache[cacheKey]?.let { cached ->
            return if (cached > 0L) cached else null
        }

        val fromMediaStore = mediaStoreDurations.byNameAndSize[displayName to size]
            ?: mediaStoreDurations.byName[displayName]
        if (fromMediaStore != null && fromMediaStore > 0L) {
            videoDurationCache[cacheKey] = fromMediaStore
            return fromMediaStore
        }

        val extracted = extractVideoDuration(context, uri)
        videoDurationCache[cacheKey] = extracted ?: -1L
        return extracted
    }

    private fun queryMediaStoreVideoDurations(context: Context): MediaStoreVideoDurations {
        val byNameAndSize = HashMap<Pair<String, Long>, Long>()
        val byName = HashMap<String, Long>()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION
            )
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                if (nameCol >= 0 && sizeCol >= 0 && durCol >= 0) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)
                        val duration = cursor.getLong(durCol)
                        if (duration > 0L) {
                            byNameAndSize[name to size] = duration
                            byName.putIfAbsent(name, duration)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall back to per-file extraction if MediaStore query is unavailable (e.g. unit tests)
        }
        return MediaStoreVideoDurations(byNameAndSize, byName)
    }

    private fun extractVideoDuration(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}

