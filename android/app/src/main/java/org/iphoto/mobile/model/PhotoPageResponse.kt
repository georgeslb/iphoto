package org.iphoto.mobile.model

/** Matches the app's own PageResponse<T> wrapper, used by GET /api/photos. */
data class PhotoPageResponse(
    val content: List<Photo>,
    val pageNumber: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean
)
