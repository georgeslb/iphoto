package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content-hashes a local file the same way the backend does (PhotoUploadServiceImpl /
 * PhotoServiceImpl / EditPhotoServiceImpl all use HexFormat.of().formatHex(MessageDigest
 * .getInstance("MD5").digest(bytes))) - matching algorithms is what lets the sync diff compare a
 * local file directly against a Space photo's `fileHash` without ever uploading it first.
 */
object FileHasher {
    private const val BUFFER_SIZE = 64 * 1024

    fun openUnredactedInputStream(context: Context, uri: Uri, mediaStoreUri: Uri? = null): InputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val targetMediaUri = mediaStoreUri ?: try {
                    MediaStore.getMediaUri(context, uri)
                } catch (_: Throwable) {
                    null
                }
                if (targetMediaUri != null) {
                    val originalUri = MediaStore.setRequireOriginal(targetMediaUri)
                    context.contentResolver.openInputStream(originalUri)?.let { return it }
                }
            } catch (_: Throwable) {
                // Fall back to opening the document URI directly if ACCESS_MEDIA_LOCATION is not granted
            }
        }
        return context.contentResolver.openInputStream(uri)
    }

    fun md5(context: Context, uri: Uri, mediaStoreUri: Uri? = null): String {
        val digest = MessageDigest.getInstance("MD5")
        val stream = openUnredactedInputStream(context, uri, mediaStoreUri)
            ?: throw IllegalStateException("Cannot open $uri")
        stream.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
