package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

private const val TAG = "LocalTrash"

/**
 * App-managed "phone garbage" for a synced folder: a `.iPhotoTrash` subfolder inside the SAF tree
 * the user picked for sync. The real system trash (MediaStore's `IS_TRASHED`, or a gallery app's
 * own "Recently deleted") only covers MediaStore-indexed locations and needs a per-call user
 * consent dialog to touch - neither holds for an arbitrary SAF-picked folder, which is all this
 * feature has to work with. So a photo moved to the Garbage in a synced Space moves its local file
 * here instead of deleting it outright, and restoring the photo moves it back out. Being a plain
 * folder, a user's file manager can also reach into `.iPhotoTrash` directly and move a file back to
 * the synced folder's root itself - SyncRepository.computeDiff notices that (by content hash, same
 * as every other comparison this feature makes) and restores the matching Space photo instead of
 * re-uploading it as new.
 *
 * Mirrors each file's original [LocalAsset.relativeDir] under `.iPhotoTrash` itself (so
 * root/Camera/2024/06/IMG.jpg trashes to root/.iPhotoTrash/Camera/2024/06/IMG.jpg) rather than
 * flattening everything into one directory, so a trash/restore round trip puts a nested
 * camera-roll-style file back where it actually came from instead of always at the root.
 */
object LocalTrash {
    const val TRASH_DIR_NAME = ".iPhotoTrash"

    fun trashDir(context: Context, rootUri: Uri, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val existing = root.findFile(TRASH_DIR_NAME)?.takeIf { it.isDirectory }
        return existing ?: if (create) root.createDirectory(TRASH_DIR_NAME) else null
    }

    /** Every image/video anywhere under the trash folder - each asset's relativeDir is where it should be restored to under the synced root. */
    fun scan(context: Context, rootUri: Uri): List<LocalAsset> {
        val dir = trashDir(context, rootUri, create = false) ?: return emptyList()
        val results = mutableListOf<LocalAsset>()
        walk(context, dir.uri, "", results)
        return results
    }

    // See SafDir's kdoc: a single batched query per directory instead of DocumentFile.listFiles()
    // plus 4 more IPC round trips per child.
    private fun walk(context: Context, dirUri: Uri, relativeDir: String, out: MutableList<LocalAsset>) {
        for (child in SafDir.listChildren(context, dirUri)) {
            when {
                child.isDirectory -> {
                    val childName = child.name ?: continue
                    val childRelativeDir = if (relativeDir.isEmpty()) childName else "$relativeDir/$childName"
                    walk(context, child.uri, childRelativeDir, out)
                }
                else -> {
                    val mimeType = child.mimeType.orEmpty()
                    if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                        out.add(
                            LocalAsset(
                                uri = child.uri,
                                displayName = child.name ?: child.uri.lastPathSegment.orEmpty(),
                                size = child.size,
                                lastModified = child.lastModified,
                                mimeType = mimeType,
                                relativeDir = relativeDir
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Moves [asset] (as found by LocalFolderScanner, so its `uri` lives directly under [rootUri]
     * at [LocalAsset.relativeDir]) into the trash folder, recreating that same relativeDir under
     * `.iPhotoTrash` so a later restore can put it back where it came from.
     */
    fun moveToTrash(context: Context, rootUri: Uri, asset: LocalAsset): Boolean {
        val source = DocumentFile.fromSingleUri(context, asset.uri)?.takeIf { it.exists() }
        if (source == null) {
            Log.w(TAG, "moveToTrash: source missing/doesn't exist for ${asset.uri}")
            return false
        }
        val trashRoot = trashDir(context, rootUri, create = true)
        if (trashRoot == null) {
            Log.w(TAG, "moveToTrash: could not create/find $TRASH_DIR_NAME under $rootUri")
            return false
        }
        val destParent = resolveOrCreateDir(trashRoot, asset.relativeDir)
        if (destParent == null) {
            Log.w(TAG, "moveToTrash: could not create dest dir '${asset.relativeDir}' under $trashRoot")
            return false
        }
        val ok = moveDocument(context, source, destParent)
        if (!ok) Log.w(TAG, "moveToTrash: moveDocument failed for ${asset.uri} -> $destParent")
        return ok
    }

    /** Moves a trashed [asset] (as found by [scan]) back to its original relativeDir under the synced folder root. */
    fun restoreFromTrash(context: Context, rootUri: Uri, asset: LocalAsset): Boolean {
        val source = DocumentFile.fromSingleUri(context, asset.uri)?.takeIf { it.exists() } ?: return false
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return false
        val destParent = resolveOrCreateDir(root, asset.relativeDir) ?: return false
        return moveDocument(context, source, destParent)
    }

    // Walks/creates each path segment of relativeDir (e.g. "Camera/2024/06") under start, so a
    // trash/restore round trip can recreate whatever nested folder structure the original file
    // lived in. `internal` rather than private specifically so LocalTrashTest can exercise this
    // path-walking logic directly against a hand-built DocumentFile tree, without needing to fake
    // the static DocumentFile.fromTreeUri/fromSingleUri resolution moveToTrash/restoreFromTrash
    // themselves depend on.
    internal fun resolveOrCreateDir(start: DocumentFile, relativeDir: String): DocumentFile? {
        if (relativeDir.isEmpty()) return start
        var current = start
        for (segment in relativeDir.split("/")) {
            if (segment.isEmpty()) continue
            val existing = current.findFile(segment)?.takeIf { it.isDirectory }
            current = existing ?: current.createDirectory(segment) ?: return null
        }
        return current
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
