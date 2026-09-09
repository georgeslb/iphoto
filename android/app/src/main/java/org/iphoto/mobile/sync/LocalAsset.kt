package org.iphoto.mobile.sync

import android.net.Uri

/** One image/video file found under the user's chosen SAF folder tree. */
data class LocalAsset(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String
)
