package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexStatusTest {
    @Test fun runningJobsShowAsOneUntilEachIsDone() {
        IndexStatus.report("Places and things", 1200, 3000)
        IndexStatus.report("People", 34, 490)
        assertEquals("1,234 of 3,490 · People, places and things", indexingMeta(IndexStatus.running.value))
        IndexStatus.report("People", 490, 490)
        assertEquals("1,200 of 3,000 · Places and things", indexingMeta(IndexStatus.running.value))
        IndexStatus.report("Places and things", 0, 0) // stopped early
        assertTrue(IndexStatus.running.value.isEmpty())
    }
}
