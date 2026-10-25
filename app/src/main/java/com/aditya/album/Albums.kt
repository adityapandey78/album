package com.aditya.album

/**
 * Built-in albums: Favourites and Videos in Collections' utilities, Recently deleted at its end. They open through the
 * same album id as folders, and their ids sit outside the Int range, so they can never clash with a folder's BUCKET_ID
 * (a 32-bit path hash).
 */
enum class Special(val id: Long, val title: String, val emptyText: String) {
    Favourites(1L shl 32, "Favourites", "Tap the heart on a photo or video to add it here."),
    Videos(2L shl 32, "Videos", "Videos you take or save show up here."),
    Trash(3L shl 32, "Recently deleted", "Deleted photos stay here for 30 days, then Android erases them."),
}

/** Whether an item belongs in album [albumId]: a folder, or the Favourites or Videos special album. */
fun belongsTo(albumId: Long, bucketId: Long, isFavorite: Boolean, isVideo: Boolean): Boolean = when (albumId) {
    Special.Favourites.id -> isFavorite
    Special.Videos.id -> isVideo
    else -> bucketId == albumId // Trash matches nothing here: trashed items aren't in Gallery.media
}

fun Media.isIn(albumId: Long) = belongsTo(albumId, bucketId, isFavorite, isVideo)

/** Item count for each special album. [trashed] comes from [rememberTrash], since Gallery.media leaves trash out. */
fun specialCounts(media: List<Media>, trashed: Int): Map<Special, Int> =
    Special.entries.associateWith { s -> if (s == Special.Trash) trashed else media.count { it.isIn(s.id) } }
