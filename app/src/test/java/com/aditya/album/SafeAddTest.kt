package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SafeAddTest {
    private val saving = SaveLocation("1234-abcd", "DCIM/Album/", "SD card")
    private val goa = SaveLocation("1234-abcd", "Pictures/Goa/", "SD card")

    @Test fun movesOutToTheChosenFolderOnItsStorage() {
        assertEquals(goa, restoreTarget("DCIM/Camera/", isVideo = false, goa) { saving })
        assertEquals(goa, restoreTarget("Download/", isVideo = true, goa) { saving })
    }

    @Test fun originalFolderIsTheOneItCameFromOnThePhone() {
        assertEquals(SaveLocation(folder = "DCIM/Camera/"), restoreTarget("DCIM/Camera/", isVideo = false, null) { saving })
        assertEquals(SaveLocation(folder = "Movies/Clips/"), restoreTarget("Movies/Clips", isVideo = true, null) { saving })
    }

    @Test fun elsewhereGoesToRestoredWhereAlbumSaves() {
        assertEquals(SaveLocation("1234-abcd", "DCIM/Album/Restored/", "SD card"), restoreTarget("Download/", isVideo = false, null) { saving })
        // A photo never goes into a Movies folder, even when one is chosen: MediaStore wouldn't take it
        assertEquals(SaveLocation(folder = "DCIM/Camera/"), restoreTarget("DCIM/Camera/", isVideo = false, SaveLocation(folder = "Movies/Clips/")) { saving })
    }

    @Test fun onlyFoldersThatTakeEveryItemAreOffered() {
        val folders = listOf("DCIM/Camera/", "Pictures/Goa/", "Movies/Clips/", "Download/", "WhatsApp/Media/")
        assertEquals(listOf("DCIM/Camera/", "Pictures/Goa/"), foldersFor(folders, { it }, photos = true, videos = false))
        assertEquals(listOf("DCIM/Camera/", "Pictures/Goa/", "Movies/Clips/"), foldersFor(folders, { it }, photos = false, videos = true))
        assertEquals(listOf("DCIM/Camera/", "Pictures/Goa/"), foldersFor(folders, { it }, photos = true, videos = true))
    }

    @Test fun newFolderNames() {
        assertNull(newFolderError("Pictures/", "Goa"))
        assertEquals("Pictures/Goa/", SaveLocation().inside("Goa").folder)
        assertNotNull(newFolderError("Pictures/", " "))
        assertNotNull(newFolderError("Pictures/", "a/b"))
        assertNotNull(newFolderError("Pictures/", ".private"))
        assertNotNull(newFolderError("DCIM/", "Camera")) // the camera's own folder
        assertNotNull(newFolderError("Pictures/", "Screenshots"))
    }

    @Test fun peoplesPhotosCountOnceAndSayWhoElseIsIn() {
        val asha = listOf(1L, 2L, 3L)
        val ravi = listOf(3L, 4L)
        val meera = listOf(2L, 9L)
        val both = photosOfPeople(listOf(asha, ravi), others = listOf(meera)) { it }
        assertEquals(listOf(1L, 2L, 3L, 4L), both.items) // 3 shows both, once
        assertEquals(1, both.shared) // 2 also shows Meera; 3 shows only people picked
        val alone = photosOfPeople(listOf(asha), others = listOf(ravi, meera)) { it }
        assertEquals(2, alone.shared)
        assertEquals(0, photosOfPeople(listOf(ravi), others = emptyList<List<Long>>()) { it }.shared)
    }

    @Test fun sharedNotes() {
        assertNull(sharedNote(0))
        assertEquals("1 photo also shows someone else. It moves too.", sharedNote(1))
        assertEquals("1,200 photos also show other people. They move too.", sharedNote(1200))
    }

    @Test fun movedOutMessagesNameTheFolder() {
        assertEquals("Moved 3 items to Pictures/Goa", movedOutMessage(3, 0, into = placeName(SaveLocation(folder = "Pictures/Goa/"))))
        assertEquals("Moved 1 item to Pictures/Goa on SD card", movedOutMessage(1, 0, into = placeName(goa)))
        assertEquals("Moved 3 items back to the Library", movedOutMessage(3, 0))
    }
}
