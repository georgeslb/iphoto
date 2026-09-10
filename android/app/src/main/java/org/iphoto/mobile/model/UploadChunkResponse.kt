package org.iphoto.mobile.model

/** Mirrors the backend's UploadChunkResponse(status, chunkIndex, task) from POST /api/upload/chunk. */
data class UploadChunkResponse(
    val status: String,
    val chunkIndex: String?,
    val task: TaskDTO?
)
