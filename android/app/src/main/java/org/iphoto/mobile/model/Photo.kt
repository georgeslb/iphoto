package org.iphoto.mobile.model

data class Photo(
    val id: String,
    val title: String,
    val url: String,
    val date: String,
    val fullDateTime: String,
    val yearMonth: String,
    val groupDate: String,
    val size: String?,
    val resolution: String,
    val location: String?,
    val camera: String?,
    val contentType: String?,
    val fileHash: String?,
    val width: Int?,
    val height: Int?,
    val fileSystemCreationDate: String?,
    val duplicates: List<Photo>?,
    val duration: Long? = null
)
