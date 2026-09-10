package org.iphoto.mobile.model

/** Mirrors the backend's TaskDTO(id, status, photoId) - polled via GET /api/tasks/{id}. */
data class TaskDTO(
    val id: String,
    val status: String,
    val photoId: String?
)
