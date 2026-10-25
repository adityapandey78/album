package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pruneKeepsNewestWithinLimits() {
        val dir = tmp.newFolder()
        (1..5).forEach { File(dir, "2026010$it-crash.txt").writeText("x".repeat(10)) }

        CrashLog.prune(dir, maxFiles = 3, maxBytes = 1000)
        assertEquals(listOf("20260105-crash.txt", "20260104-crash.txt", "20260103-crash.txt"), dir.list()!!.sortedDescending())

        CrashLog.prune(dir, maxFiles = 10, maxBytes = 15)
        assertEquals(listOf("20260105-crash.txt"), dir.list()!!.toList())

        CrashLog.prune(dir, maxFiles = 10, maxBytes = 1) // newest survives even when it alone is over the cap
        assertEquals(listOf("20260105-crash.txt"), dir.list()!!.toList())
    }
}
