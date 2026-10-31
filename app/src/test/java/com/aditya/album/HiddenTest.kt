package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    private data class Item(val id: Long, val path: String)

    private val box = FaceBox(0f, 0f, 1f, 1f)
    private val items = listOf(Item(1, "DCIM/Camera/"), Item(2, "Pictures/Goa/"), Item(3, "pictures/goa/"), Item(4, "DCIM/Camera/"))

    private fun shown(h: HiddenState) = withoutHidden(items, h, Item::id, Item::path).map { it.id }

    @Test fun nothingHiddenKeepsTheSameList() {
        assertSame(items, withoutHidden(items, HiddenState(), Item::id, Item::path))
    }

    @Test fun hiddenFoldersLeaveWhateverTheCase() {
        // Shared storage ignores case, as folderKey does
        assertEquals(listOf(1L, 4L), shown(HiddenState(folders = setOf(folderKey("Pictures/Goa/")))))
    }

    @Test fun photosOfLockedPeopleLeaveWhereverTheyAre() {
        assertEquals(listOf(2L, 3L), shown(HiddenState(people = setOf(5), personPhotos = setOf(1, 4))))
        assertEquals(listOf(1L), shown(HiddenState(setOf(folderKey("Pictures/Goa/")), setOf(5), setOf(4))))
    }

    @Test fun lockedPeoplesPhotosIncludeConfirmedFacesTheIndexLost() {
        val tiles = listOf(FaceTile(1, 10, 5, 0.5f, 0.5f, box), FaceTile(2, 11, 6, 0.5f, 0.5f, box), FaceTile(3, 12, 5, 0.5f, 0.5f, box))
        val locked = lockedPeople(listOf(5L), tiles, confirmed = listOf(12L, 30L)) // 30: confirmed, not rescanned yet
        assertEquals(setOf(5L), locked.ids)
        assertEquals(setOf(10L, 12L, 30L), locked.photos)
        assertTrue(lockedPeople(emptyList(), tiles, emptyList()).photos.isEmpty())
    }

    @Test fun personPagesOfLockedPeopleAreHiddenAlbums() {
        val h = HiddenState(people = setOf(5L))
        assertTrue(h.isHiddenAlbum(personAlbumId(5), emptyList()))
        assertFalse(h.isHiddenAlbum(personAlbumId(6), emptyList()))
        assertFalse(h.isHiddenAlbum(Special.Favourites.id, emptyList()))
    }

    @Test fun searchNeverFindsLockedPeopleByName() {
        val names = listOf(PersonName(1, "Asha", hidden = false), PersonName(2, "Ravi", hidden = false, locked = true), PersonName(3, "Meera", hidden = true))
        val tiles = listOf(FaceTile(1, 10, 1, 0.5f, 0.5f, box), FaceTile(2, 11, 2, 0.5f, 0.5f, box), FaceTile(3, 12, 3, 0.5f, 0.5f, box))
        assertEquals(mapOf("Asha" to setOf(10L)), namedMedia(names, tiles))
    }

    @Test fun lockedPeopleAreNeverAskedAbout() {
        val crop = FaceCrop(1, 1, box)
        fun person(cluster: Long, locked: Boolean) = PersonAlbum(cluster, null, false, crop, emptyList(), MIN_FACES, locked = locked)
        val albums = listOf(person(1, false), person(2, true), person(3, false))
        val pairs = reviewPairs(listOf(Suggestion(1, 2, 0.9f), Suggestion(1, 3, 0.8f)), albums, emptySet())
        assertEquals(listOf(1L to 3L), pairs.map { it.a.cluster to it.b.cluster })
    }

    @Test fun snackbarsNameWhatMoved() {
        assertEquals("Asha", hiddenWhat(listOf("Asha"), emptyList()))
        assertEquals("1 person", hiddenWhat(listOf(null), emptyList()))
        assertEquals("Goa trip", hiddenWhat(emptyList(), listOf("Goa trip")))
        assertEquals("2 people", hiddenWhat(listOf("Asha", null), emptyList()))
        assertEquals("3 albums", hiddenWhat(emptyList(), listOf("a", "b", "c")))
        assertEquals("1 person and 2 albums", hiddenWhat(listOf("Asha"), listOf("a", "b")))
    }

    @Test fun hidingSaysWhatTheDesignSays() {
        assertEquals("Hid 2 items. Other apps still show their photos.", hiddenNote(listOf("Asha"), listOf("Goa trip"), hide = true, counted = true))
        assertEquals("Unhid 1 item", hiddenNote(emptyList(), listOf("Goa trip"), hide = false, counted = true))
        assertEquals("Hid Goa trip. It is in Hidden items.", hiddenNote(emptyList(), listOf("Goa trip"), hide = true, counted = false))
        assertEquals("Unhid Asha", hiddenNote(listOf("Asha"), emptyList(), hide = false, counted = false))
    }
}
