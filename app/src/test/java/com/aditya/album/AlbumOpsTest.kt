package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumOpsTest {
    @Test fun freeNamesKeepNamesThatDontClash() {
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), freeNames(listOf("IMG_1.jpg", "IMG_2.jpg"), listOf("IMG_3.jpg")))
    }

    @Test fun freeNamesNumberClashesBeforeTheExtension() {
        assertEquals(listOf("IMG_1 (1).jpg"), freeNames(listOf("IMG_1.jpg"), listOf("IMG_1.jpg")))
        assertEquals(listOf("IMG_1 (2).jpg"), freeNames(listOf("IMG_1.jpg"), listOf("IMG_1.jpg", "IMG_1 (1).jpg")))
        assertEquals(listOf("clip.tar (1).mp4"), freeNames(listOf("clip.tar.mp4"), listOf("clip.tar.mp4")))
    }

    @Test fun freeNamesIgnoreCaseLikeSharedStorage() {
        assertEquals(listOf("img_1 (1).JPG"), freeNames(listOf("img_1.JPG"), listOf("IMG_1.jpg")))
    }

    @Test fun freeNamesKeepArrivalsApart() {
        // the same name from two different folders, moved into a third
        assertEquals(listOf("a.jpg", "a (1).jpg", "a (2).jpg"), freeNames(listOf("a.jpg", "a.jpg", "a.jpg"), emptyList()))
    }

    @Test fun freeNamesWithoutAnExtension() {
        assertEquals(listOf("scan (1)"), freeNames(listOf("scan"), listOf("scan")))
        assertEquals(listOf(".hidden (1)"), freeNames(listOf(".hidden"), listOf(".hidden"))) // a leading dot isn't an extension
    }

    @Test fun albumNamesAreTrimmedAndChecked() {
        val existing = listOf("Camera", "Trips")
        assertNull(albumNameError("  Goa 2026 ", existing))
        assertNotNull(albumNameError("", existing))
        assertNotNull(albumNameError("   ", existing))
        assertNotNull(albumNameError("a/b", existing))
        assertNotNull(albumNameError("what?", existing))
        assertNotNull(albumNameError(".secret", existing))
    }

    @Test fun albumNamesCantClashIgnoringCase() {
        assertEquals("There's already an album called trips", albumNameError(" trips ", listOf("Trips")))
    }

    private class A(val name: String, val count: Int, val newest: Long, val pinned: Boolean = false)

    private fun order(albums: List<A>, sort: AlbumSort) =
        orderAlbums(albums, sort, A::pinned, A::name, A::count, A::newest).map { it.name }

    private val camera = A("Camera", 50, 300)
    private val zoo = A("zoo", 80, 100)
    private val bali = A("Bali", 80, 200)
    private val docs = A("Docs", 5, 50, pinned = true)

    @Test fun defaultKeepsTheGivenOrderAfterPins() {
        assertEquals(listOf("Docs", "Camera", "zoo", "Bali"), order(listOf(camera, zoo, bali, docs), AlbumSort.Default))
    }

    @Test fun sortsByNameIgnoringCase() {
        assertEquals(listOf("Bali", "Camera", "zoo"), order(listOf(camera, zoo, bali), AlbumSort.Name))
    }

    @Test fun mostItemsFirstWithTiesInDefaultOrder() {
        assertEquals(listOf("zoo", "Bali", "Camera"), order(listOf(camera, zoo, bali), AlbumSort.Count))
    }

    @Test fun recentlyUpdatedFirst() {
        assertEquals(listOf("Camera", "Bali", "zoo"), order(listOf(zoo, bali, camera), AlbumSort.Updated))
    }

    @Test fun pinnedComeFirstInSortOrder() {
        val pinnedZoo = A("zoo", 80, 100, pinned = true)
        assertEquals(listOf("Docs", "zoo", "Bali", "Camera"), order(listOf(camera, pinnedZoo, bali, docs), AlbumSort.Name))
    }

    @Test fun photosLiveOnlyUnderDcimAndPictures() {
        assertTrue(canHold("DCIM/Camera/", isVideo = false))
        assertTrue(canHold("Pictures/Trips/", isVideo = false))
        assertTrue(canHold("pictures/", isVideo = false))
        assertFalse(canHold("Movies/Clips/", isVideo = false))
        assertTrue(canHold("Movies/Clips/", isVideo = true))
        assertFalse(canHold("Download/", isVideo = true))
        assertFalse(canHold("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", isVideo = false))
        assertFalse(canHold("", isVideo = false))
    }

    @Test fun renameKeepsTheParentFolder() {
        assertEquals("Pictures/Goa/", renamedPath("Pictures/Trips/", "Goa"))
        assertEquals("DCIM/2026/Goa/", renamedPath("DCIM/2026/Trips/", "Goa"))
    }

    @Test fun systemFoldersCantBeRenamed() {
        assertNotNull(renameBlocked("DCIM/Camera/"))
        assertNotNull(renameBlocked("Pictures/Screenshots/"))
        assertNotNull(renameBlocked("pictures/screenshots/"))
        assertNotNull(renameBlocked("Movies/Screen recordings/"))
        assertNotNull(renameBlocked("Pictures/")) // top-level folders
        assertNotNull(renameBlocked("Download/Telegram/"))
        assertNotNull(renameBlocked("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertNull(renameBlocked("Pictures/Trips/"))
        assertNull(renameBlocked("DCIM/Holidays/"))
        assertNull(renameBlocked("Movies/Clips/"))
    }

    @Test fun transferMessages() {
        assertEquals("Moved 12 items to Trips", transferMessage(Transfer.Move, 12, 0, "Trips"))
        assertEquals("Copied 1 item to Trips", transferMessage(Transfer.Copy, 1, 0, "Trips"))
        assertEquals("Moved 10 items to Trips. 2 couldn't be moved.", transferMessage(Transfer.Move, 10, 2, "Trips"))
        assertEquals("Couldn't copy 3 items to Trips", transferMessage(Transfer.Copy, 0, 3, "Trips"))
        assertNull(transferMessage(Transfer.Move, 0, 0, "Trips")) // the user said no to Android's pop-up
    }

    @Test fun foldersMatchIgnoringCase() {
        assertEquals(folderKey("Pictures/Trips/"), folderKey("pictures/TRIPS/"))
    }
}
