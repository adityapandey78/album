package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollerTest {
    private val oct = 2026 * 12 + 9
    private val sep = 2026 * 12 + 8
    private val dec25 = 2025 * 12 + 11

    // H(Oct) c c | H(Sep) c | H(Dec 2025) c, two columns: a line of cells counts on its last cell
    private val map = ScrollMap(
        intArrayOf(40, 0, 100, 40, 100, 40, 100),
        intArrayOf(oct, oct, oct, sep, sep, dec25, dec25),
    )

    @Test fun itemsStartWhereTheOnesAboveEnd() {
        assertEquals(420, map.total)
        assertEquals(listOf(0, 40, 40, 140, 180, 280, 320), (0..6).map { map.topOf(it) })
        assertEquals(420, map.topOf(99)) // past the end: the bottom
    }

    @Test fun aPositionFindsItsItem() {
        assertEquals(0, map.indexAt(0))
        assertEquals(0, map.indexAt(39))
        assertEquals(2, map.indexAt(40)) // the line's last cell: scrolling to it shows the whole line
        assertEquals(2, map.indexAt(139))
        assertEquals(3, map.indexAt(140))
        assertEquals(6, map.indexAt(10_000))
        assertEquals(0, map.indexAt(-5))
    }

    @Test fun monthsAndYearsStartWhereTheyChange() {
        assertEquals(listOf(0, 3, 5), map.monthStarts.toList())
        assertEquals(listOf(0, 5), map.yearStarts.toList())
        assertEquals(sep, map.monthOf(4))
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2), (0..6).map { map.monthIndexOf(it) })
    }

    @Test fun anEmptyGridIsHarmless() {
        val empty = ScrollMap(IntArray(0), IntArray(0))
        assertEquals(0, empty.total)
        assertEquals(0, empty.indexAt(50))
        assertEquals(0, empty.monthOf(3))
        assertEquals(0, empty.monthStarts.size)
        assertEquals(0, empty.monthIndexOf(2))
    }
}
