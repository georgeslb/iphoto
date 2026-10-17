package org.iphoto.mobile.model

/** Mirrors the backend's CheckHashesRequest(hashes) for POST /api/photos/check-hashes. */
data class CheckHashesRequest(
    val hashes: List<String>
)

/** Mirrors the backend's CheckHashesResponse(existingHashes, statuses) from POST /api/photos/check-hashes. */
data class CheckHashesResponse(
    val existingHashes: List<String>? = emptyList(),
    val statuses: Map<String, Boolean>? = emptyMap()
) {
    fun isExisting(hash: String): Boolean =
        statuses?.get(hash) ?: (existingHashes?.contains(hash) == true)
}
