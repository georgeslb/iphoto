package org.iphoto.mobile.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Walks a SAF-picked folder tree looking for image/video files. Recurses into subfolders (common
 * for camera-roll-style layouts, e.g. Camera/2024/06) but caps depth so accidentally picking a
 * folder much higher up the tree than intended can't turn one sync tap into an unbounded scan.
 * Records each file's folder path relative to the root as it goes (see LocalAsset.relativeDir) so
 * LocalTrash can preserve it across a trash/restore round trip.
 */
object LocalFolderScanner {
    private const val MAX_DEPTH = 6

    fun scan(context: Context, treeUri: Uri): List<LocalAsset> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val results = mutableListOf<LocalAsset>()
        walk(context, root.uri, 0, "", results)
        return results
    }

    private fun walk(context: Context, dirUri: Uri, depth: Int, relativeDir: String, out: MutableList<LocalAsset>) {
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
                    walk(context, child.uri, depth + 1, childRelativeDir, out)
                }
                else -> {
                    val mimeType = child.mimeType.orEmpty()
                    if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                        val duration = if (mimeType.startsWith("video/")) {
                            extractVideoDuration(context, child.uri)
                        } else null
                        out.add(
                            LocalAsset(
                                uri = child.uri,
                                displayName = child.name ?: child.uri.lastPathSegment.orEmpty(),
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
