package org.iphoto.mobile.testutil

import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.SpaceDTO

fun testPhoto(
    id: String,
    groupDate: String = "Monday, Jan 01, 2026",
    fullDateTime: String = "2026-01-01T12:00:00",
    contentType: String? = "image/jpeg",
    fileHash: String? = null
): Photo = Photo(
    id = id,
    title = "photo-$id",
    url = "/api/photos/$id/thumbnail",
    date = "Jan 01, 2026",
    fullDateTime = fullDateTime,
    yearMonth = "January 2026",
    groupDate = groupDate,
    size = "1 MB",
    resolution = "1920x1080",
    location = null,
    camera = null,
    contentType = contentType,
    fileHash = fileHash,
    width = 1920,
    height = 1080,
    fileSystemCreationDate = null,
    duplicates = null
)

fun testAlbum(
    id: String,
    name: String = "Album $id",
    createdAt: String = "2026-01-01T00:00:00"
): AlbumDTO = AlbumDTO(
    id = id,
    name = name,
    description = null,
    coverPhoto = null,
    photoCount = 0,
    createdAt = createdAt,
    updatedAt = createdAt
)

fun testSpace(
    id: String,
    name: String = "Space $id",
    deletionAllowed: Boolean = true
): SpaceDTO = SpaceDTO(
    id = id,
    name = name,
    photoCount = 0,
    createdAt = "2026-01-01T00:00:00",
    updatedAt = "2026-01-01T00:00:00",
    albumAllowed = true,
    allowCopyToMain = true,
    deletionAllowed = deletionAllowed,
    deleted = false
)
