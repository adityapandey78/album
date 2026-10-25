package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {
    private val box = FaceBox(0f, 0f, 1f, 1f)

    private fun tile(id: Long, mediaId: Long, cluster: Long, quality: Float = 0.5f) = FaceTile(id, mediaId, cluster, quality, box)

    @Test fun personPageIdsRoundTripAndClashWithNoOtherAlbum() {
        listOf(1L, 42L, AUTO_CLUSTERS, AUTO_CLUSTERS + 99_999).forEach { assertEquals(it, clusterOfAlbum(personAlbumId(it))) }
        listOf(1L shl 40, (1L shl 44) + 12_345).forEach { assertNull(clusterOfAlbum(it)) } // Explore's things and places bands
        val ids = listOf(1L, AUTO_CLUSTERS + 5).map { personAlbumId(it) }
        assertTrue(ids.none { it in Int.MIN_VALUE..Int.MAX_VALUE }) // folder BUCKET_IDs
        assertTrue(ids.none { id -> Special.entries.any { it.id == id } })
        assertTrue(ids.none { collectionIdOf(it) != null })
        Special.entries.forEach { assertNull(clusterOfAlbum(it.id)) }
        listOf(0L, -5L, Int.MAX_VALUE.toLong(), albumIdOf(1)).forEach { assertNull(clusterOfAlbum(it)) }
        // Explore's things and places never open a person page, and person pages are never things or places
        Category.entries.forEach { assertNull(clusterOfAlbum(thingIdOf(it))) }
        assertNull(clusterOfAlbum(placeIdOf(Place("Bengaluru", "Karnataka", "IN", "India"))))
        assertNull(thingOf(personAlbumId(AUTO_CLUSTERS + 5)))
        assertEquals(false, isPlaceId(personAlbumId(1)))
    }

    @Test fun membersFollowTheTimelineAndPickTheBestFace() {
        val media = listOf(50L, 40L, 30L, 20L) // newest first, like Gallery.media
        val tiles = listOf(
            tile(1, 20, cluster = 1, quality = 0.9f),
            tile(2, 50, cluster = 1, quality = 0.3f),
            tile(3, 50, cluster = 1, quality = 0.4f), // twice in one photo (a reflection): one photo, two faces
            tile(4, 99, cluster = 1, quality = 1f), // photo deleted from the phone
            tile(5, 40, cluster = 2),
        )
        val members = clusterMembers(tiles, media) { it }
        assertEquals(listOf(50L, 20L), members.getValue(1).items)
        assertEquals(3, members.getValue(1).faces)
        assertEquals(1L, members.getValue(1).cover.id)
        assertEquals(listOf(40L), members.getValue(2).items)
    }

    @Test fun onlyNamedOrBigEnoughGroupsShow() {
        assertTrue(isShown("Asha", 1))
        assertFalse(isShown(null, MIN_FACES - 1))
        assertTrue(isShown(null, MIN_FACES))
    }

    @Test fun searchSeesNamedPeopleOnly() {
        val names = listOf(PersonName(1, "Asha", hidden = false), PersonName(2, null, hidden = false), PersonName(3, "Ravi", hidden = true))
        val tiles = listOf(tile(1, 10, 1), tile(2, 11, 1), tile(3, 12, 2), tile(4, 13, 3), tile(5, 14, AUTO_CLUSTERS))
        assertEquals(mapOf("Asha" to setOf(10L, 11L)), namedMedia(names, tiles))
    }
}
