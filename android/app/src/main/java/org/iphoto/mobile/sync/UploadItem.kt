package org.iphoto.mobile.sync

import android.net.Uri
import java.util.UUID

enum class UploadStatus {
    QUEUED,
    UPLOADING,
    PROCESSING,
    COMPLETED,
    IGNORED,
    TRASHED,
    FAILED
}

data class UploadItem(
    val id: String = UUID.randomUUID().toString(),
    val asset: LocalAsset,
    val folderUri: Uri? = null,
    val spaceId: String,
    val deleteAfter: Boolean = false,
    val status: UploadStatus = UploadStatus.QUEUED,
    val progress: Float = 0f,
    val error: String? = null,
    val taskId: String? = null,
    val photoId: String? = null
)
