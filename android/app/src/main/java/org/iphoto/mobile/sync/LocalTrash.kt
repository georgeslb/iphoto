package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * App-managed "phone garbage" for a synced folder: a flat `.iPhotoTrash` subfolder inside the SAF
 * tree the user picked for sync. The real system trash (MediaStore's `IS_TRASHED`, or a gallery
 * app's own "Recently deleted") only covers MediaStore-indexed locations and needs a per-call user
 * consent dialog to touch - neither holds for an arbitrary SAF-picked folder, which is all this
 * feature has to work with. So a photo moved to the Garbage in a synced Space moves its local file
 * here instead of deleting it outright, and restoring the photo moves it back out. Being a plain
 * folder, a user's file manager can also reach into `.iPhotoTrash` directly and move a file back to
 * the synced folder's root itself - SyncRepository.computeDiff notices that (by content hash, same
 * as every other comparison this feature makes) and restores the matching Space photo instead of
 * re-uploading it as new.
 */
object LocalTrash {
    const val TRASH_DIR_NAME = ".iPhotoTrash"

    fun trashDir(context: Context, rootUri: Uri, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val existing = root.findFile(TRASH_DIR_NAME)?.takeIf { it.isDirectory }
        return existing ?: if (create) root.createDirectory(TRASH_DIR_NAME) else null
    }

    /** Every image/video directly inside the trash folder - it is never nested, so no recursion. */
    fun scan(context: Context, rootUri: Uri): List<LocalAsset> {
        val dir = trashDir(context, rootUri, create = false) ?: return emptyList()
        return dir.listFiles().mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val mimeType = file.type.orEmpty()
            if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) return@mapNotNull null
            LocalAsset(
                uri = file.uri,
                displayName = file.name ?: file.uri.lastPathSegment.orEmpty(),
                size = file.length(),
                lastModified = file.lastModified(),
                mimeType = mimeType
            )
        }
    }

    /** Moves the local file at [assetUri] (found directly under [rootUri]) into the trash folder. */
    fun moveToTrash(context: Context, rootUri: Uri, assetUri: Uri): Boolean {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return false
        val source = DocumentFile.fromSingleUri(context, assetUri)?.takeIf { it.exists() } ?: return false
        val dest = trashDir(context, rootUri, create = true) ?: return false
        return moveDocument(context, source, dest)
    }

    /** Moves a trashed file back to the root of the synced folder. */
    fun restoreFromTrash(context: Context, rootUri: Uri, trashedAssetUri: Uri): Boolean {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return false
        val source = DocumentFile.fromSingleUri(context, trashedAssetUri)?.takeIf { it.exists() } ?: return false
        return moveDocument(context, source, root)
    }

    // Copy+delete rather than DocumentsContract.moveDocument: works uniformly across every SAF
    // provider (some, e.g. certain SD-card providers, don't implement moveDocument at all), at the
    // cost of a full byte copy instead of a metadata-only rename.
    private fun moveDocument(context: Context, source: DocumentFile, destParent: DocumentFile): Boolean {
        val name = source.name ?: return false
        val mimeType = source.type ?: "application/octet-stream"
        val dest = destParent.createFile(mimeType, name) ?: return false
        return try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open ${source.uri}")
            source.delete()
            true
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }
}
