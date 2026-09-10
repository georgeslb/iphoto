package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/**
 * Content-hashes a local file the same way the backend does (PhotoUploadServiceImpl /
 * PhotoServiceImpl / EditPhotoServiceImpl all use HexFormat.of().formatHex(MessageDigest
 * .getInstance("MD5").digest(bytes))) - matching algorithms is what lets the sync diff compare a
 * local file directly against a Space photo's `fileHash` without ever uploading it first.
 */
object FileHasher {
    private const val BUFFER_SIZE = 64 * 1024

    fun md5(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        val stream = context.contentResolver.openInputStream(uri)
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
