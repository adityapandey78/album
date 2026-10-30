package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun onlyShortClipsLoop() {
        assertTrue(loopsShort(true, 14_999))
        assertFalse(loopsShort(true, 15_000))
        assertFalse(loopsShort(true, 0)) // length unknown
        assertFalse(loopsShort(false, 5_000))
    }

    @Test
    fun screenshotsSwitchFollowsTheirFolders() {
        assertTrue(screenshotsShown(setOf("pictures/whatsapp video/")))
        assertFalse(screenshotsShown(setOf(folderKey("Pictures/Screenshots/"))))
        assertFalse(screenshotsShown(setOf(folderKey("DCIM/Screenshots/"))))
    }

    @Test
    fun hiddenFoldersLeaveOutScreenshotsAndHiddenItems() {
        val hidden = setOf("pictures/whatsapp video/", "dcim/screenshots/", "pictures/secret/")
        assertEquals(setOf("pictures/whatsapp video/"), listedHidden(hidden, locked = setOf("pictures/secret/")))
    }

    @Test
    fun labResetKeepsEverythingElse() {
        val mine = Appearance(
            theme = ThemeMode.Dark, accent = Accent.Sky, haptics = false, messageSeconds = 2f, still = true,
            bar = BarStyle.Pill, barScroll = BarScroll.Stay, messageStyle = MessageStyle.Island, forYouStyle = ForYouStyle.Stories,
            videoControls = VideoControls.Filmstrip, settingsStyle = SettingsStyle.Plain, tabMotion = TabMotion.Blur,
        )
        assertEquals(
            Appearance(theme = ThemeMode.Dark, accent = Accent.Sky, haptics = false, messageSeconds = 2f, still = true),
            labDefaults(mine),
        )
    }
}
