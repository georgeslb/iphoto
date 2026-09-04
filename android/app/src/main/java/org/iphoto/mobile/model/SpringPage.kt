package org.iphoto.mobile.model

/**
 * Matches Spring Data's default Page<T> JSON envelope (Spring Boot 3.3+ PagedModel shape), used
 * by GET /api/albums/{id}/photos and GET /api/spaces/{id}/photos - deliberately a different shape
 * from PhotoPageResponse, which is this app's own hand-rolled wrapper used by GET /api/photos.
 */
data class SpringPage<T>(
    val content: List<T>,
    val page: SpringPageInfo
)

data class SpringPageInfo(
    val size: Int,
    val number: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    val isLast: Boolean get() = totalPages == 0 || number >= totalPages - 1
}
