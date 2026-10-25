package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumsTest {
    @Test fun specialIdsNeverClashWithFolders() {
        val ids = Special.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it in Int.MIN_VALUE..Int.MAX_VALUE }) // BUCKET_ID is a 32-bit hash
    }

    @Test fun foldersMatchByBucket() {
        assertTrue(belongsTo(42, bucketId = 42, isFavorite = false, isVideo = false))
        assertFalse(belongsTo(42, bucketId = 7, isFavorite = true, isVideo = true))
    }

    @Test fun favouritesAndVideosIgnoreFolders() {
        assertTrue(belongsTo(Special.Favourites.id, bucketId = 7, isFavorite = true, isVideo = false))
        assertFalse(belongsTo(Special.Favourites.id, bucketId = 7, isFavorite = false, isVideo = true))
        assertTrue(belongsTo(Special.Videos.id, bucketId = 7, isFavorite = false, isVideo = true))
        assertFalse(belongsTo(Special.Videos.id, bucketId = 7, isFavorite = true, isVideo = false))
    }

    @Test fun trashMatchesNothingInTheGallery() {
        assertFalse(belongsTo(Special.Trash.id, bucketId = 7, isFavorite = true, isVideo = true))
    }
}
