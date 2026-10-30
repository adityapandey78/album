package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGroupsTest {
    private fun entry(id: Long, name: String, count: Int, recent: Long, key: String = "pictures/$name/") = AlbumEntry(id, key, name, count, recent)

    private val goa = entry(1, "Goa 2024", 40, recent = 300)
    private val manali = entry(2, "Manali", 30, recent = 100, key = collectionKey(5)) // a collection can be in a group too
    private val camera = entry(3, "Camera", 900, recent = 50)
    private val bills = entry(4, "Bills", 12, recent = 200)
    private val all = listOf(goa, manali, camera, bills)
    private val trips = AlbumGroup(id = 7, name = "Trips", created = 0)
    private val inTrips = listOf(GroupMember(goa.key, 7, 0), GroupMember(manali.key, 7, 0))

    private fun names(list: List<AlbumEntry>) = list.map { it.name }

    @Test fun groupedAlbumsLeaveTheTopForTheirGroupsCard() {
        val view = albumsView(all, listOf(trips), inTrips, AlbumSort.Default, emptySet())
        assertEquals(listOf("Trips", "Bills", "Camera"), names(view.top))
        val card = view.top.first()
        assertEquals(groupKey(7), card.key)
        assertEquals(groupAlbumId(7), card.id)
        assertEquals(70, card.count) // its albums' items together
        assertEquals(300L, card.recent) // as recent as its newest album
        assertEquals(listOf("Goa 2024", "Manali"), names(card.group!!.albums))
        assertSame(card, view.groupOf(goa.key))
        assertNull(view.groupOf(bills.key))
        assertEquals("Trips", view.group(7)?.name)
    }

    @Test fun aGroupWhoseAlbumsAreAllOutOfSightHidesWithThem() {
        // Goa's folder is on an SD card that's out, and Manali is in Hidden items: neither is listed, so neither is Trips
        val view = albumsView(listOf(camera, bills), listOf(trips), inTrips, AlbumSort.Default, emptySet())
        assertEquals(listOf("Bills", "Camera"), names(view.top))
        assertTrue(view.groups.isEmpty())
    }

    @Test fun anAlbumListedInAGroupThatsGoneStaysOnItsOwn() {
        val view = albumsView(all, emptyList(), inTrips, AlbumSort.Default, emptySet())
        assertEquals(listOf("Goa 2024", "Bills", "Manali", "Camera"), names(view.top))
    }

    @Test fun groupsTakePinsAndSortsLikeAlbums() {
        val pinned = setOf(groupKey(7))
        assertEquals(listOf("Trips", "Bills", "Camera"), names(albumsView(all, listOf(trips), inTrips, AlbumSort.Name, pinned).top))
        assertEquals(listOf("Bills", "Camera", "Trips"), names(albumsView(all, listOf(trips), inTrips, AlbumSort.Name, emptySet()).top))
        assertEquals(listOf("Camera", "Trips", "Bills"), names(albumsView(all, listOf(trips), inTrips, AlbumSort.Count, emptySet()).top))
    }

    @Test fun albumsInAGroupKeepTheChosenOrderWithPinnedFirst() {
        val byName = albumsView(all, listOf(trips), inTrips, AlbumSort.Name, emptySet()).group(7)!!
        assertEquals(listOf("Goa 2024", "Manali"), names(byName.albums))
        val pinned = albumsView(all, listOf(trips), inTrips, AlbumSort.Name, setOf(manali.key)).group(7)!!
        assertEquals(listOf("Manali", "Goa 2024"), names(pinned.albums))
    }

    @Test fun eachGroupHoldsOnlyItsOwnAlbums() {
        val family = AlbumGroup(id = 8, name = "Family", created = 0)
        val members = inTrips + GroupMember(bills.key, 8, 0)
        val view = albumsView(all, listOf(trips, family), members, AlbumSort.Default, emptySet())
        assertEquals(listOf("Trips", "Family", "Camera"), names(view.top))
        assertEquals(listOf("Bills"), names(view.group(8)!!.albums))
    }

    @Test fun groupKeysAndIdsNeverClashWithAlbums() {
        assertTrue(!groupKey(1).endsWith("/")) // folder keys are paths
        assertTrue(groupKey(1) != collectionKey(1))
        assertTrue(groupKey(1) != groupKey(11))
        val ids = listOf(1L, 2L, 999_999L).map(::groupAlbumId)
        assertTrue(ids.none { it in Int.MIN_VALUE..Int.MAX_VALUE }) // folder BUCKET_IDs
        assertTrue(ids.none { id -> Special.entries.any { it.id == id } })
        assertTrue(ids.none { it == albumIdOf(1) || it == albumIdOf(999_999) })
    }

    @Test fun groupNamesAreNeededAndUnique() {
        assertEquals("Enter a name", groupNameError("  ", emptyList()))
        assertNotNull(groupNameError("trips ", listOf("Trips")))
        assertNull(groupNameError("Trips 2", listOf("Trips")))
        assertNull(groupNameError("Trips", emptyList()))
    }

    @Test fun albumsFoundInOrOutOfGroupsAndPickableForAnotherGroup() {
        val view = albumsView(all, listOf(trips), inTrips, AlbumSort.Name, emptySet())
        assertEquals("Goa 2024", view.album(goa.id)?.name) // in Trips
        assertEquals("Bills", view.album(bills.id)?.name) // on its own
        assertNull(view.album(groupAlbumId(7))) // a group's card isn't an album
        // Groups can't hold groups, and albums already in this group aren't offered again
        assertEquals(listOf("Bills", "Camera"), names(view.groupable(except = 7)))
        assertEquals(listOf("Bills", "Camera", "Goa 2024", "Manali"), names(view.groupable(except = null)))
    }

    @Test fun pickersNameGroupedAlbumsWithTheirGroup() {
        val names = Organisation(listOf(trips), inTrips + GroupMember("pictures/gone/", 99, 0), emptyList()).groupNames()
        assertEquals(mapOf(goa.key to "Trips", manali.key to "Trips"), names) // a member of a deleted group is in none
        assertEquals("Trips · Goa 2024", pickerName(goa.name, names[goa.key]))
        assertEquals("Bills", pickerName(bills.name, names[bills.key]))
    }

    @Test fun groupPhotoPagesHaveTheirOwnAlbumIds() {
        assertEquals(7L, groupIdOf(groupAlbumId(7)))
        assertNull(groupIdOf(albumIdOf(7))) // a collection
        assertNull(groupIdOf(Special.Favourites.id))
    }

    @Test fun favouritesCountsAndCoversItsNewestFavourite() {
        val fav = favouritesEntry(emptyList())
        assertEquals(Special.Favourites.id, fav.id)
        assertEquals(0, fav.count)
        assertNull(fav.cover) // the card shows a heart instead
        assertTrue(fav.key != groupKey(1) && !fav.key.endsWith("/") && !fav.key.startsWith("collection:"))
    }

    @Test fun spansReadAsDays() {
        fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay()
        assertEquals("12 Oct 2026", dateSpan(day(2026, 10, 12), day(2026, 10, 12)))
        assertEquals("12–15 Oct 2026", dateSpan(day(2026, 10, 12), day(2026, 10, 15)))
        assertEquals("29 Oct–2 Nov 2026", dateSpan(day(2026, 10, 29), day(2026, 11, 2)))
        assertEquals("Dec 2025–Jan 2026", dateSpan(day(2025, 12, 30), day(2026, 1, 2)))
    }

    private val photos = listOf(10L, 20L, 30L) // an album's photos, newest first
    private val find = { id: Long -> photos.find { it == id } }

    @Test fun aChosenCoverStaysWhileItsPhotoIsInTheAlbum() {
        assertEquals(20L, pickCover(20L, find, { true }, photos.first()))
    }

    @Test fun coverFallsBackWhenThePhotoIsGoneOrLeftOrNoneWasChosen() {
        assertEquals(10L, pickCover(99L, find, { true }, photos.first())) // deleted from the phone
        assertEquals(10L, pickCover(20L, find, { false }, photos.first())) // moved out of the album
        assertEquals(10L, pickCover(null, find, { true }, photos.first())) // Choose automatically
        assertNull(pickCover(null, find, { true }, null)) // an empty collection
    }
}
