package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveLocationTest {
    @Test fun theDefaultIsPicturesOnThePhoneLikeBefore() {
        assertEquals(SaveLocation(PHONE_STORAGE, "Pictures/", "Phone storage"), SaveLocation())
        assertEquals("Pictures/Goa/", SaveLocation().inside("Goa").folder) // what 1.1 always did
    }

    @Test fun foldersAreBuiltUnderPicturesOrDcim() {
        assertEquals("Pictures/", saveFolder("Pictures"))
        assertEquals("DCIM/", saveFolder("DCIM", " "))
        assertEquals("Pictures/Album/", saveFolder("Pictures", " Album "))
        assertEquals("DCIM/Album/Goa/", albumFolder(saveFolder("DCIM", "Album"), "Goa"))
        assertEquals("Pictures/Album/Rotated/", SaveLocation("1234-abcd", "Pictures/Album/", "SD card").inside("Rotated").folder)
        assertEquals("1234-abcd", SaveLocation("1234-abcd", "Pictures/", "SD card").inside("Rotated").volume) // same storage
        // Every folder Album can save to is one MediaStore lets photos and videos into
        for (top in saveTops) assertTrue(canHold(saveFolder(top, "Album"), isVideo = false))
    }

    @Test fun foldersReadBackIntoTheirParts() {
        assertEquals("Pictures", topOf("Pictures/Album/"))
        assertEquals("DCIM", topOf("DCIM/"))
        assertEquals("Album", ownOf("Pictures/Album/"))
        assertNull(ownOf("Pictures/"))
        assertNull(ownOf("DCIM"))
    }

    @Test fun aFolderOfYourOwnFollowsAlbumNameRules() {
        assertNull(ownFolderError("Pictures", "Album"))
        assertNull(ownFolderError("Pictures", "Camera")) // only DCIM/Camera is the camera's
        assertEquals("Enter a name", ownFolderError("Pictures", "  "))
        assertNotNull(ownFolderError("Pictures", "Album/Goa")) // one folder, not a path
        assertNotNull(ownFolderError("DCIM", ".thumbnails"))
    }

    @Test fun systemFoldersAreRefusedWhateverTheCase() {
        assertEquals("Camera is a system folder. Pick another name.", ownFolderError("DCIM", "Camera"))
        assertNotNull(ownFolderError("DCIM", "camera"))
        assertNotNull(ownFolderError("Pictures", "Screenshots"))
        assertNotNull(ownFolderError("DCIM", "Screen recordings"))
    }

    @Test fun anSdCardThatsOutFallsBackToThePhone() {
        val mounted = setOf(PHONE_STORAGE, "1234-abcd")
        assertEquals("1234-abcd", volumeNow("1234-abcd", mounted))
        assertEquals(PHONE_STORAGE, volumeNow("9999-0000", mounted))
        assertEquals(PHONE_STORAGE, volumeNow(PHONE_STORAGE, mounted))
    }

    @Test fun placesReadPlainly() {
        assertEquals("Pictures", placeName(SaveLocation()))
        assertEquals("DCIM/Album on SD card", placeName(SaveLocation("1234-abcd", "DCIM/Album/", "SD card")))
    }
}
