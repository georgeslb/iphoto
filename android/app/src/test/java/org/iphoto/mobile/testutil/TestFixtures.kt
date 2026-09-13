package org.iphoto.mobile.testutil

import org.iphoto.mobile.model.Photo

fun testPhoto(
    id: String,
    groupDate: String = "Monday, Jan 01, 2026",
    fullDateTime: String = "2026-01-01T12:00:00",
    contentType: String? = "image/jpeg"
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
    fileHash = null,
    width = 1920,
    height = 1080,
    fileSystemCreationDate = null,
    duplicates = null
)
