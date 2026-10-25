package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartIndexTest {
    private fun media(id: Long, modified: Long = 100, size: Long = 10, isVideo: Boolean = false) = MediaStamp(id, isVideo, modified, size)
    private fun row(id: Long, modified: Long = 100, size: Long = 10, version: Int = 1, located: Boolean = true) =
        IndexStamp(id, modified, size, version, located)

    @Test fun analysesOnlyNewAndChangedItems() {
        val found = listOf(media(5), media(4), media(3, modified = 200), media(2, size = 11), media(1))
        val plan = planIndex(found, listOf(row(4), row(3), row(2), row(1)), version = 1, canLocate = true)
        assertEquals(listOf(5L, 3L, 2L), plan.analyse.map { it.id }) // new, edited, resized; order kept (newest first)
        assertEquals(emptyList<Long>(), plan.remove)
    }

    @Test fun redoesOlderVersions() {
        val plan = planIndex(listOf(media(1), media(2)), listOf(row(1, version = 1), row(2, version = 2)), version = 2, canLocate = true)
        assertEquals(listOf(1L), plan.analyse.map { it.id })
    }

    @Test fun relocatesOnceLocationAccessArrives() {
        val indexed = listOf(row(1, located = false), row(2))
        assertEquals(listOf(1L), planIndex(listOf(media(1), media(2)), indexed, 1, canLocate = true).analyse.map { it.id })
        assertEquals(emptyList<Long>(), planIndex(listOf(media(1), media(2)), indexed, 1, canLocate = false).analyse.map { it.id })
    }

    @Test fun removesRowsOfDeletedMedia() {
        val plan = planIndex(listOf(media(2)), listOf(row(1), row(2), row(3)), version = 1, canLocate = true)
        assertEquals(listOf(1L, 3L), plan.remove)
        assertEquals(emptyList<Long>(), plan.analyse.map { it.id })
    }

    @Test fun readsVideoLocationInIso6709() {
        assertArrayEquals(doubleArrayOf(12.9716, 77.5946), iso6709("+12.9716+077.5946/"), 1e-9)
        assertArrayEquals(doubleArrayOf(37.422, -122.0841), iso6709("+37.4220-122.0841+012.000/"), 1e-9) // with altitude
        assertArrayEquals(doubleArrayOf(-33.8688, 151.2093), iso6709("-33.8688+151.2093/"), 1e-9)
        assertNull(iso6709("+00.0000+000.0000/")) // a camera without a fix
        assertNull(iso6709("+95.0000+010.0000/"))
        assertNull(iso6709(""))
    }
}
