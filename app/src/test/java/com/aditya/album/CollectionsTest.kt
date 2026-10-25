package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsTest {
    @Test fun collectionAlbumIdsRoundTrip() {
        listOf(1L, 2L, 999_999L).forEach { assertEquals(it, collectionIdOf(albumIdOf(it))) }
    }

    @Test fun collectionAlbumIdsNeverClashWithFoldersOrSpecials() {
        val ids = listOf(1L, 2L, 999_999L).map { albumIdOf(it) }
        assertTrue(ids.none { it in Int.MIN_VALUE..Int.MAX_VALUE }) // BUCKET_ID is a 32-bit hash
        assertTrue(ids.none { id -> Special.entries.any { it.id == id } })
    }

    @Test fun foldersAndSpecialsAreNotCollections() {
        listOf(0L, 42L, -42L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong()).forEach { assertNull(collectionIdOf(it)) }
        Special.entries.forEach { assertNull(collectionIdOf(it.id)) }
        assertNotNull(collectionIdOf(albumIdOf(1)))
    }

    private fun item(collection: Long, media: Long) = CollectionItem(collection, media, added = 0)

    @Test fun membersFollowTheTimelineAndSkipDeletedPhotos() {
        val media = listOf(50L, 40L, 30L, 20L) // newest first, like Gallery.media
        val items = listOf(item(1, 20), item(1, 50), item(1, 99), item(2, 40), item(2, 50)) // 99 was deleted from the phone
        val members = membersOf(items, media) { it }
        assertEquals(listOf(50L, 20L), members[1])
        assertEquals(listOf(50L, 40L), members[2]) // one photo, two collections
        assertNull(members[3]) // empty collection
    }

    @Test fun namesMustNotBeBlank() {
        assertEquals("Enter a name", collectionNameError("", emptyList()))
        assertEquals("Enter a name", collectionNameError("   ", emptyList()))
    }

    @Test fun namesAreUniqueIgnoringCaseAndSpaces() {
        val taken = listOf("Goa trip", "Family")
        assertNotNull(collectionNameError("goa TRIP", taken))
        assertNotNull(collectionNameError("  Family ", taken))
        assertNull(collectionNameError("Goa trip 2", taken))
        assertNull(collectionNameError("Goa", taken))
    }

    @Test fun renameCanChangeTheCaseOfItsOwnName() {
        // Rename passes the other collections only, so "Goa trip" can become "Goa Trip"
        assertNull(collectionNameError("Goa Trip", listOf("Family")))
    }

    @Test fun addedMessageSaysWhenNothingWasNew() {
        assertEquals("Added 5 to Goa trip", addedMessage(5, "Goa trip"))
        assertEquals("Already in Goa trip", addedMessage(0, "Goa trip"))
    }
}
