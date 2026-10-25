package com.aditya.album

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("0.3.0", "0.2.0"))
        assertTrue(Updater.isNewer("v0.10.0", "0.9.1")) // 10 > 9, not text order
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertTrue(Updater.isNewer("0.2.1", "0.2"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.9", "0.2.0"))
        assertFalse(Updater.isNewer("0.2", "0.2.0"))
    }
}
