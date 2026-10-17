package org.iphoto.mobile.sync

import android.net.Uri

/**
 * One image/video file found under the user's chosen SAF folder tree.
 *
 * [relativeDir] is the folder path between the synced root and this file - slash-separated, no
 * leading/trailing slash, empty for a file directly in the root (e.g. "Camera/2024/06" for
 * root/Camera/2024/06/IMG.jpg, which is a common camera-roll layout - see LocalFolderScanner).
 * LocalTrash mirrors this same structure under its own `.iPhotoTrash` subfolder, so a file's
 * nested location survives a trash/restore round trip instead of always landing back at the root.
 */
data class LocalAsset(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val relativeDir: String = "",
    val duration: Long? = null
)
