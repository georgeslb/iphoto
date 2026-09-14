package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** One child of a SAF directory, as listed by [SafDir.listChildren]. */
internal data class SafChild(
    val uri: Uri,
    val name: String?,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/**
 * Lists the direct children of a SAF directory in a single batched ContentResolver query.
 *
 * `DocumentFile.listFiles()` plus per-child `.name`/`.type`/`.length()`/`.lastModified()` (the
 * previous approach here) issues a separate ContentResolver round-trip for the listing itself and
 * then again for *each* of those four properties on *every* child - 5 IPC calls per file. Measured
 * on a real device: ~40 seconds to walk an 843-file DCIM/Camera folder, long enough that a user
 * checking their gallery right after tapping "remove from device" would see nothing had happened
 * yet. Querying all columns for every child of a directory in one shot cuts that to one round trip
 * per *directory* instead of per *file*.
 */
internal object SafDir {
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    /** [dirUri] must be a SAF document/tree URI, as returned by DocumentFile.fromTreeUri(...).uri or a previous listChildren() entry's uri. */
    fun listChildren(context: Context, dirUri: Uri): List<SafChild> {
        val documentId = DocumentsContract.getDocumentId(dirUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, documentId)
        val results = mutableListOf<SafChild>()
        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol)
                val mimeType = cursor.getString(mimeCol)
                results.add(
                    SafChild(
                        uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, childId),
                        name = cursor.getString(nameCol),
                        mimeType = mimeType,
                        size = cursor.getLong(sizeCol),
                        lastModified = cursor.getLong(modifiedCol),
                        isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        return results
    }
}
