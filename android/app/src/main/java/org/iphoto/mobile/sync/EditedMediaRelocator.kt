package org.iphoto.mobile.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File

/**
 * State of the original media captured right before an external editor (Google Photos, Samsung
 * Gallery, Snapseed...) is launched from the Sync Lightbox via ACTION_EDIT.
 */
data class EditSnapshot(
    val originalUri: Uri,
    val collection: Uri,
    val originalId: Long,
    val relativePath: String?,
    val dateTakenMs: Long?,
    val dateModifiedSec: Long,
    val sizeBytes: Long,
    val fileLastModifiedMs: Long,
    val launchedAtSec: Long,
    val isVideo: Boolean,
    val editorPackage: String?,
    val exifAttributes: Map<String, String>
)

/** A MediaStore row observed after the editor returned. */
data class MediaRow(
    val id: Long,
    val relativePath: String?,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val sizeBytes: Long,
    val ownerPackage: String?,
    val mimeType: String?
)

/** A file written by the editor that must be aligned with the original. */
data class EditedOutput(
    val uri: Uri,
    val relativePath: String?,
    val overwritesOriginal: Boolean
)

/** What needs to be changed on an [EditedOutput] to match the original. */
data class PropertyFix(
    val targetRelativePath: String?,
    val dateTakenMs: Long?,
    val lastModifiedMs: Long
) {
    val needsMove: Boolean get() = targetRelativePath != null
}

object EditedMediaRelocator {

    private const val TAG = "EditedMediaRelocator"

    // Small tolerance: DATE_ADDED is in seconds and the editor may round down.
    private const val DATE_ADDED_TOLERANCE_SEC = 2L

    /** EXIF tags describing *when* and *where* the photo was taken, copied from original to edit. */
    val EXIF_TAGS_TO_PRESERVE = listOf(
        "DateTime", "DateTimeOriginal", "DateTimeDigitized",
        "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
        "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
        "Make", "Model",
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
        "GPSProcessingMethod"
    )

    // ---------------------------------------------------------------------------------------
    // Pure decision logic (unit tested)
    // ---------------------------------------------------------------------------------------

    /** Whether [row] is a brand new file produced by the editor for this edit session. */
    fun isNewEditorOutput(row: MediaRow, snapshot: EditSnapshot): Boolean {
        if (row.id == snapshot.originalId) return false
        if (row.dateAddedSec < snapshot.launchedAtSec - DATE_ADDED_TOLERANCE_SEC) return false
        val expectedFamily = if (snapshot.isVideo) "video/" else "image/"
        if (row.mimeType != null && !row.mimeType.startsWith(expectedFamily)) return false
        
        // Check owner package: if specified, ensure it matches either the launched editor,
        // or a system / OEM media provider (ColorOS/Oppo com.oplus/com.coloros, Xiaomi, Samsung, MediaProvider)
        // because OEM photo editors frequently delegate saving to a system daemon or MediaProvider.
        if (snapshot.editorPackage != null && row.ownerPackage != null) {
            val owner = row.ownerPackage
            val launched = snapshot.editorPackage
            val isDirectMatch = owner == launched
            val isOemMediaProvider = owner == "com.android.providers.media" ||
                owner == "com.google.android.providers.media.module" ||
                owner.startsWith("com.oplus.") ||
                owner.startsWith("com.coloros.") ||
                owner.startsWith("com.sec.android.") ||
                owner.startsWith("com.miui.") ||
                owner.startsWith("com.xiaomi.") ||
                owner.startsWith("com.huawei.")
            if (!isDirectMatch && !isOemMediaProvider) return false
        }
        return true
    }

    /** Whether the editor overwrote the original file in place. */
    fun isOriginalOverwritten(row: MediaRow, snapshot: EditSnapshot): Boolean =
        row.id == snapshot.originalId &&
            (row.dateModifiedSec != snapshot.dateModifiedSec || row.sizeBytes != snapshot.sizeBytes)

    fun planFix(snapshot: EditSnapshot, output: EditedOutput): PropertyFix {
        val target = snapshot.relativePath
        val needsMove = !output.overwritesOriginal && target != null &&
            normalizeRelPath(output.relativePath) != normalizeRelPath(target)
        return PropertyFix(
            targetRelativePath = if (needsMove) target else null,
            dateTakenMs = snapshot.dateTakenMs,
            lastModifiedMs = snapshot.fileLastModifiedMs
        )
    }

    internal fun normalizeRelPath(path: String?): String? =
        path?.trim('/')?.lowercase()

    // ---------------------------------------------------------------------------------------
    // Android plumbing
    // ---------------------------------------------------------------------------------------

    fun snapshot(
        context: Context,
        mediaUri: Uri,
        isVideo: Boolean,
        fileLastModifiedMs: Long,
        editorPackage: String?
    ): EditSnapshot? {
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val id = try { ContentUris.parseId(mediaUri) } catch (e: Exception) { return null }

        val projection = buildList {
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()

        return try {
            context.contentResolver.query(mediaUri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val relCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                EditSnapshot(
                    originalUri = mediaUri,
                    collection = collection,
                    originalId = id,
                    relativePath = if (relCol >= 0) c.getString(relCol) else null,
                    dateTakenMs = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol).takeIf { it > 0 } else null,
                    dateModifiedSec = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)),
                    sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                    fileLastModifiedMs = fileLastModifiedMs,
                    launchedAtSec = System.currentTimeMillis() / 1000,
                    isVideo = isVideo,
                    editorPackage = editorPackage,
                    exifAttributes = if (isVideo) emptyMap() else readExif(context, mediaUri)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "snapshot failed for $mediaUri", e)
            null
        }
    }

    private fun readExif(context: Context, mediaUri: Uri): Map<String, String> = try {
        // Unredacted stream so GPS tags survive (ACCESS_MEDIA_LOCATION).
        FileHasher.openUnredactedInputStream(context, mediaUri, mediaUri)?.use { stream ->
            val exif = ExifInterface(stream)
            EXIF_TAGS_TO_PRESERVE.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
        } ?: emptyMap()
    } catch (e: Exception) {
        Log.d(TAG, "readExif failed: ${e.message}")
        emptyMap()
    }

    /**
     * Looks up the files written by the editor. Editors usually save before returning, but some
     * (especially OEM editors like ColorOS/MIUI) finish writing or media indexing slightly asynchronously,
     * so we poll for up to ~6 seconds.
     */
    suspend fun findEditedOutputs(context: Context, snapshot: EditSnapshot): List<EditedOutput> {
        repeat(8) { attempt ->
            val found = queryOutputs(context, snapshot)
            if (found.isNotEmpty()) return found
            if (attempt < 7) delay(800)
        }
        return emptyList()
    }

    private fun queryOutputs(context: Context, snapshot: EditSnapshot): List<EditedOutput> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            }
        }.toTypedArray()
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns._ID} = ?"
        val args = arrayOf(
            (snapshot.launchedAtSec - DATE_ADDED_TOLERANCE_SEC).toString(),
            snapshot.originalId.toString()
        )
        val outputs = mutableListOf<EditedOutput>()
        try {
            context.contentResolver.query(snapshot.collection, projection, selection, args, null)?.use { c ->
                val relCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val ownerCol = c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                while (c.moveToNext()) {
                    val row = MediaRow(
                        id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                        relativePath = if (relCol >= 0) c.getString(relCol) else null,
                        dateAddedSec = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)),
                        dateModifiedSec = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)),
                        sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                        ownerPackage = if (ownerCol >= 0) c.getString(ownerCol) else null,
                        mimeType = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                    )
                    val uri = ContentUris.withAppendedId(snapshot.collection, row.id)
                    when {
                        isOriginalOverwritten(row, snapshot) ->
                            outputs += EditedOutput(uri, row.relativePath, overwritesOriginal = true)
                        isNewEditorOutput(row, snapshot) ->
                            outputs += EditedOutput(uri, row.relativePath, overwritesOriginal = false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryOutputs failed", e)
        }
        return outputs
    }

    /**
     * Moves each output next to the original and restores its date / location properties.
     * Requires write access to the outputs (MediaStore.createWriteRequest on API 30+).
     * Returns the number of outputs successfully aligned.
     */
    suspend fun applyOriginalProperties(
        context: Context,
        snapshot: EditSnapshot,
        outputs: List<EditedOutput>
    ): Int {
        var fixed = 0
        for (output in outputs) {
            val fix = planFix(snapshot, output)
            try {
                // 1. EXIF first: rewriting EXIF touches the file mtime, which we restore last.
                if (!snapshot.isVideo && snapshot.exifAttributes.isNotEmpty()) {
                    copyExif(context, output.uri, snapshot.exifAttributes)
                }

                // 2. Same folder + same "date taken" and "date modified" as the original.
                val values = ContentValues()
                if (fix.needsMove && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, fix.targetRelativePath)
                }
                fix.dateTakenMs?.let { values.put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                if (snapshot.dateModifiedSec > 0) {
                    values.put(MediaStore.MediaColumns.DATE_MODIFIED, snapshot.dateModifiedSec)
                }
                if (values.size() > 0) {
                    context.contentResolver.update(output.uri, values, null, null)
                }

                // 3. Same file modification time, so the edit sorts next to the original in the
                //    Sync grid (which groups by lastModified) and keeps its original date.
                restoreLastModified(context, output.uri, fix.lastModifiedMs)
                fixed++
            } catch (e: Exception) {
                Log.w(TAG, "applyOriginalProperties failed for ${output.uri}", e)
            }
        }
        return fixed
    }

    private fun copyExif(context: Context, uri: Uri, attributes: Map<String, String>) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                var changed = false
                attributes.forEach { (tag, value) ->
                    if (exif.getAttribute(tag) != value) {
                        exif.setAttribute(tag, value)
                        changed = true
                    }
                }
                if (changed) exif.saveAttributes()
            }
        } catch (e: Exception) {
            // Formats like HEIC cannot be rewritten by ExifInterface - not fatal.
            Log.d(TAG, "copyExif skipped for $uri: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun restoreLastModified(context: Context, uri: Uri, lastModifiedMs: Long) {
        if (lastModifiedMs <= 0) return
        val path = try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null } ?: return

        val file = File(path)
        if (!file.setLastModified(lastModifiedMs)) {
            Log.d(TAG, "setLastModified refused for $path")
        }
        // Rescan so MediaStore.DATE_MODIFIED picks up the restored timestamp.
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
    }
}
