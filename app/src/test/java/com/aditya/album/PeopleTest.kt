package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {
    private val box = FaceBox(0f, 0f, 1f, 1f)

    private fun tile(id: Long, mediaId: Long, cluster: Long, quality: Float = 0.5f, fit: Float = 0.6f, b: FaceBox = box) = FaceTile(id, mediaId, cluster, quality, fit, b)

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

    @Test fun membersFollowTheTimelineAndPickTheBestCover() {
        val media = listOf(50L, 40L, 30L, 20L) // newest first, like Gallery.media
        val tiles = listOf(
            tile(1, 20, cluster = 1, quality = 0.9f),
            tile(2, 50, cluster = 1, quality = 0.3f),
            tile(3, 50, cluster = 1, quality = 0.4f), // twice in one photo (a reflection): one photo, two faces
            tile(4, 99, cluster = 1, quality = 1f), // photo deleted from the phone
            tile(5, 40, cluster = 2),
        )
        val members = clusterMembers(tiles, media, { it })
        assertEquals(listOf(50L, 20L), members.getValue(1).items)
        assertEquals(3, members.getValue(1).faces)
        assertEquals(1L, members.getValue(1).cover.id)
        assertEquals(listOf(40L), members.getValue(2).items)
        // A screenshot loses to a camera photo of the same quality
        val camera = clusterMembers(listOf(tile(1, 20, 1), tile(2, 50, 1)), media, { it }, isCamera = { it == 50L })
        assertEquals(2L, camera.getValue(1).cover.id)
    }

    @Test fun coverPrefersClearTypicalCameraFaces() {
        val clear = tile(1, 1, 1, quality = 0.8f, fit = 0.7f)
        assertTrue(coverScore(clear, camera = true) > coverScore(tile(2, 2, 1, quality = 0.4f, fit = 0.7f), camera = true))
        assertTrue(coverScore(clear, camera = true) > coverScore(tile(3, 3, 1, quality = 0.8f, fit = 0.2f), camera = true)) // an odd one out
        assertTrue(coverScore(clear, camera = true) > coverScore(clear, camera = false))
    }

    @Test fun aPinnedCoverWinsWhileItIsStillTheirs() {
        val faces = listOf(tile(1, 10, 1, quality = 0.9f), tile(2, 11, 1, quality = 0.2f, b = FaceBox(0.5f, 0.5f, 0.7f, 0.7f)))
        val pinned = FaceSpot(11, FaceBox(0.51f, 0.5f, 0.71f, 0.7f)) // a newer detector's box, a little off
        assertEquals(2L, coverOf(faces, pinned) { true }.id)
        // Its photo deleted, or the face taken out of the person: back to the automatic pick
        assertEquals(1L, coverOf(faces.take(1), pinned) { true }.id)
        assertEquals(1L, coverOf(faces, FaceSpot(11, FaceBox(0f, 0f, 0.1f, 0.1f))) { true }.id) // another face in that photo
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

    private fun album(cluster: Long, name: String? = null, faces: Int = 5, hidden: Boolean = false) =
        PersonAlbum(cluster, name, hidden, FaceCrop(cluster, cluster, box), emptyList(), faces)

    @Test fun reviewAsksOnlyWhatsWorthAsking() {
        val asha = album(1, "Asha", faces = 9)
        val group = album(AUTO_CLUSTERS + 1, faces = 12)
        val small = album(AUTO_CLUSTERS + 2, faces = 2)
        val ravi = album(2, "Ravi")
        val hidden = album(AUTO_CLUSTERS + 3, hidden = true)
        val albums = listOf(asha, group, small, ravi, hidden)
        fun ask(a: Long, b: Long, skipped: Set<Pair<Long, Long>> = emptySet()) = reviewPairs(listOf(Suggestion(a, b, 0.5f)), albums, skipped)
        assertEquals(listOf(PeoplePair(group, asha)), ask(1, AUTO_CLUSTERS + 1)) // the bigger one first
        assertEquals(emptyList<PeoplePair>(), ask(1, 2)) // two names: two people
        assertEquals(emptyList<PeoplePair>(), ask(1, AUTO_CLUSTERS + 2)) // too small to show
        assertEquals(emptyList<PeoplePair>(), ask(1, AUTO_CLUSTERS + 3)) // hidden
        assertEquals(emptyList<PeoplePair>(), ask(1, AUTO_CLUSTERS + 1, setOf(1L to AUTO_CLUSTERS + 1))) // Not sure before
        assertEquals(emptyList<PeoplePair>(), ask(1, AUTO_CLUSTERS + 9)) // gone since
    }

    @Test fun notSureFollowsTheFacesShownNotTheGroupIds() {
        val a = FaceBox(0.1f, 0.1f, 0.3f, 0.3f)
        val b = FaceBox(0.5f, 0.5f, 0.7f, 0.7f)
        val answer = NotSure(FaceSpot(10, a), FaceSpot(20, b))
        val tiles = listOf(tile(1, 10, AUTO_CLUSTERS + 1, b = a), tile(2, 20, AUTO_CLUSTERS + 2, b = b))
        assertEquals(setOf(AUTO_CLUSTERS + 1 to AUTO_CLUSTERS + 2), answeredPairs(listOf(answer), tiles))
        // The second group became person 5 (Different on another pair, say) and a rescan moved its box a little:
        // the answer follows the face there
        val later = listOf(tile(1, 10, AUTO_CLUSTERS + 1, b = a), tile(7, 20, 5, b = FaceBox(0.51f, 0.5f, 0.71f, 0.7f)))
        assertEquals(setOf(5L to AUTO_CLUSTERS + 1), answeredPairs(listOf(answer), later))
        // A face that's gone (photo deleted, face no longer in a group) ends the answer instead of guessing
        assertEquals(emptySet<Pair<Long, Long>>(), answeredPairs(listOf(answer), later.take(1)))
    }

    @Test fun answersAndSuggestionsSurviveARestart() {
        val answer = NotSure(FaceSpot(10, FaceBox(0.1f, 0.2f, 0.3f, 0.4f)), FaceSpot(11, FaceBox(0.5f, 0.6f, 0.7f, 0.8f)))
        assertEquals(answer, decodeNotSure(encodeNotSure(answer)))
        val suggestions = listOf(Suggestion(3, AUTO_CLUSTERS + 4, 0.42f), Suggestion(1, 2, 0.3f))
        assertEquals(suggestions, decodeSuggestions(encodeSuggestions(suggestions)))
        // Anything unreadable (group ids, as test builds stored Not sure) is dropped, not trusted
        assertNull(decodeNotSure("4294967297:4294967298"))
        assertEquals(emptyList<Suggestion>(), decodeSuggestions(null))
        assertEquals(emptyList<Suggestion>(), decodeSuggestions("1:2"))
    }
}
