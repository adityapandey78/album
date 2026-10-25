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

    @Test fun collectionPinsNeverLookLikeFolders() {
        // Folder pins are RELATIVE_PATHs, which always end in "/"
        listOf(1L, 42L).forEach { assertTrue(!collectionKey(it).endsWith("/")) }
        assertTrue(collectionKey(1) != collectionKey(11))
    }

    @Test fun subtitleCountsEachKind() {
        assertEquals("12 albums · 3 people · 5 places", collectionsSubtitle(12, 3, 5))
        assertEquals("1 album · 1 person · 1 place", collectionsSubtitle(1, 1, 1))
        assertEquals("0 albums · 0 people · 0 places", collectionsSubtitle(0, 0, 0))
    }

    private fun entry(id: Long, name: String, count: Int, recent: Long, camera: Boolean = false, key: String = "pictures/$name/") =
        AlbumEntry(id, key, name, count, recent, camera)

    private val camera = entry(1, "Camera", 900, recent = 50, camera = true)
    private val goa = entry(2, "Goa", 40, recent = 300, key = collectionKey(7)) // a collection, edited most recently
    private val whatsApp = entry(3, "WhatsApp Images", 2_000, recent = 200)
    private val bills = entry(4, "Bills", 12, recent = 100, key = collectionKey(8))
    private val all = listOf(bills, whatsApp, goa, camera)

    private fun names(list: List<AlbumEntry>) = list.map { it.name }

    @Test fun defaultPutsCameraFirstThenMostRecentlyChangedOfEitherKind() {
        assertEquals(listOf("Camera", "Goa", "WhatsApp Images", "Bills"), names(albumEntries(all, AlbumSort.Default, emptySet())))
    }

    @Test fun pinnedFoldersAndCollectionsComeFirstInTheChosenSort() {
        val pinned = setOf(collectionKey(8), "pictures/WhatsApp Images/")
        assertEquals(listOf("Bills", "WhatsApp Images", "Camera", "Goa"), names(albumEntries(all, AlbumSort.Name, pinned)))
        assertEquals(listOf("WhatsApp Images", "Bills", "Camera", "Goa"), names(albumEntries(all, AlbumSort.Count, pinned)))
    }

    @Test fun otherSortsMixBothKinds() {
        assertEquals(listOf("Bills", "Camera", "Goa", "WhatsApp Images"), names(albumEntries(all, AlbumSort.Name, emptySet())))
        assertEquals(listOf("WhatsApp Images", "Camera", "Goa", "Bills"), names(albumEntries(all, AlbumSort.Count, emptySet())))
        assertEquals(listOf("Goa", "WhatsApp Images", "Bills", "Camera"), names(albumEntries(all, AlbumSort.Updated, emptySet())))
    }

    // Eight folders, "Album 8" the most recently changed
    private val eight = (1..8).map { entry(10L + it, "Album $it", count = it, recent = it.toLong()) }

    @Test fun sectionShowsTwoRowsOfThreeWithPinnedFirst() {
        val shown = albumsInSection(albumEntries(eight, AlbumSort.Updated, setOf("pictures/Album 2/")))
        assertEquals(listOf("Album 2", "Album 8", "Album 7", "Album 6", "Album 5", "Album 4"), names(shown))
    }

    @Test fun sectionFollowsTheChosenSort() {
        assertEquals(listOf("Album 1", "Album 2", "Album 3", "Album 4", "Album 5", "Album 6"), names(albumsInSection(albumEntries(eight, AlbumSort.Name, emptySet()))))
    }

    @Test fun sectionKeepsPinnedAlbumsEvenWhenTheyFillIt() {
        val pinned = (1..7).map { "pictures/Album $it/" }.toSet()
        assertEquals(listOf("Album 7", "Album 6", "Album 5", "Album 4", "Album 3", "Album 2"), names(albumsInSection(albumEntries(eight, AlbumSort.Updated, pinned))))
    }

    @Test fun seeAllOnlyWhenSomeAreLeftOut() {
        // the screen offers See all when the section shows fewer than there are
        assertEquals(ALBUMS_IN_SECTION, albumsInSection(eight).size)
        assertEquals(all, albumsInSection(all)) // four: every one shows, so Sort stays on the heading
        assertEquals(eight.take(6), albumsInSection(eight.take(6)))
        assertTrue(albumsInSection(emptyList<AlbumEntry>()).isEmpty())
    }
}
