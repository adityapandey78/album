package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexStatusTest {
    @Test fun runningJobsShowAsOneUntilEachIsDone() {
        // Both count over the whole library: 40% and 80% average to 60%, not 3,600 of 6,000 photos
        IndexStatus.report("Places and things", 1200, 3000)
        IndexStatus.report("People", 2400, 3000)
        assertEquals("60% done · People, places and things", indexingMeta(IndexStatus.running.value))
        IndexStatus.report("People", 3000, 3000)
        assertEquals("40% done · Places and things", indexingMeta(IndexStatus.running.value))
        IndexStatus.report("Places and things", 2999, 3000)
        assertEquals("99% done · Places and things", indexingMeta(IndexStatus.running.value)) // never 100% while running
        IndexStatus.report("Places and things", 0, 0) // stopped early
        assertTrue(IndexStatus.running.value.isEmpty())
    }
}
