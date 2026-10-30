package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetsTest {
    private val pool = (0 until 40).toList()

    @Test fun photosAreSpreadAcrossThePool() {
        val picked = widgetPhotos(pool, turn = 0, count = 4)
        assertEquals(listOf(0, 10, 20, 30), picked) // never four shots of the same burst
        assertEquals(listOf(0, 1), widgetPhotos(listOf(0, 1), turn = 5, count = 4).sorted()) // fewer than asked: all, once each
        assertEquals(emptyList<Int>(), widgetPhotos(emptyList<Int>(), turn = 3, count = 4))
    }

    @Test fun eachTurnStartsSomewhereNewAndEveryPhotoComesRound() {
        val firsts = (0L until 120L).map { widgetPhotos(pool, it, 1).single() }
        assertTrue((1 until firsts.size).all { firsts[it] != firsts[it - 1] }) // two turns in a row never show the same one
        assertTrue((1 until firsts.size).all { Math.floorMod(firsts[it] - firsts[it - 1], 40) !in setOf(1, 39) }) // nor the next shot
        assertEquals(pool.toSet(), firsts.toSet())
    }

    private fun person(cluster: Long, name: String?, locked: Boolean = false) =
        PersonAlbum(cluster, name, hidden = false, FaceCrop(1, 1, FaceBox(0f, 0f, 1f, 1f)), emptyList(), faces = 5, locked = locked)

    private val goa = CollectionAlbum(MediaCollection(id = 3, name = "Goa", created = 0, updated = 0), emptyList())

    @Test fun aWidgetShowsItsAlbumByItsNameNow() {
        val people = listOf(person(1, "Asha"), person(2, null), person(3, "Ravi", locked = true))
        assertEquals("Asha", widgetAlbumOf(personAlbumId(1), emptyList(), people, emptyList())!!.title)
        assertEquals("Goa", widgetAlbumOf(albumIdOf(3), emptyList(), emptyList(), listOf(goa))!!.title)
        assertEquals("Favourites", widgetAlbumOf(Special.Favourites.id, emptyList(), emptyList(), emptyList())!!.title)
    }

    @Test fun hiddenOrGoneAlbumsShowNothing() {
        val people = listOf(person(2, null), person(3, "Ravi", locked = true))
        assertNull(widgetAlbumOf(personAlbumId(3), emptyList(), people, emptyList())) // in Hidden items: not even the name
        assertNull(widgetAlbumOf(personAlbumId(2), emptyList(), people, emptyList())) // never named
        assertNull(widgetAlbumOf(personAlbumId(9), emptyList(), people, emptyList())) // merged away
        assertNull(widgetAlbumOf(albumIdOf(4), emptyList(), emptyList(), listOf(goa))) // deleted collection
        assertNull(widgetAlbumOf(1234L, emptyList(), emptyList(), emptyList())) // a folder with nothing visible left
    }
}
