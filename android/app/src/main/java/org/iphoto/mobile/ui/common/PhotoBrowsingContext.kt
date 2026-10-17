package org.iphoto.mobile.ui.common

import org.iphoto.mobile.model.Photo

/**
 * Ordered photo IDs currently shown in whichever grid (Library/Album/Space) the lightbox was
 * opened from - lets PhotoDetailScreen show next/previous arrows without threading the whole,
 * potentially-paginated photo list through nav route args (which only carry simple scalar values).
 *
 * Set by PhotoGridContent whenever its photo list changes (so it's always in sync with what's on
 * screen right before a tap opens the lightbox); read once by PhotoDetailScreen per photo shown,
 * to compute the previous/next id relative to the current one. A plain last-write-wins holder deliberately
 * not observable/reactive - single-Activity app, only one grid is ever "the one that opened the
 * lightbox" at a time, and the browsed list isn't expected to change while a photo is open.
 */
object PhotoBrowsingContext {
    var photoIds: List<String> = emptyList()
    private var photosById: Map<String, Photo> = emptyMap()

    fun updatePhotos(photos: List<Photo>) {
        photoIds = photos.map { it.id }
        photosById = photos.associateBy { it.id }
    }

    fun getPhoto(id: String): Photo? = photosById[id]
}
