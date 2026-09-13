package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Walks a SAF-picked folder tree looking for image/video files. Recurses into subfolders (common
 * for camera-roll-style layouts, e.g. Camera/2024/06) but caps depth so accidentally picking a
 * folder much higher up the tree than intended can't turn one sync tap into an unbounded scan.
 */
object LocalFolderScanner {
    private const val MAX_DEPTH = 6

    fun scan(context: Context, treeUri: Uri): List<LocalAsset> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val results = mutableListOf<LocalAsset>()
        walk(root, 0, results)
        return results
    }

    private fun walk(dir: DocumentFile, depth: Int, out: MutableList<LocalAsset>) {
        if (depth > MAX_DEPTH) return
        for (child in dir.listFiles()) {
            when {
                // The local-trash subfolder (see LocalTrash) holds files the sync feature has
                // already moved out of the active folder - it must never be scanned back in as if
                // it were still-present content, or a garbaged photo's file would look "still
                // there" and computeDiff would never flag the Space photo for removal.
                child.isDirectory && child.name == LocalTrash.TRASH_DIR_NAME -> Unit
                child.isDirectory -> walk(child, depth + 1, out)
                child.isFile -> {
                    val mimeType = child.type.orEmpty()
                    if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                        out.add(
                            LocalAsset(
                                uri = child.uri,
                                displayName = child.name ?: child.uri.lastPathSegment.orEmpty(),
                                size = child.length(),
                                lastModified = child.lastModified(),
                                mimeType = mimeType
                            )
                        )
                    }
                }
            }
        }
    }
}
