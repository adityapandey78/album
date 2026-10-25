package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val today = LocalDate.of(2026, 10, 25).toEpochDay()

    @Test fun groupsByDayWithFriendlyLabels() {
        val s = sections(listOf(today, today, today - 1, today - 400), byMonth = false, today = today) { it }
        assertEquals(listOf(2, 1, 1), s.map { it.items.size })
        assertEquals("Today", s[0].label)
        assertEquals("Yesterday", s[1].label)
        assertTrue(s[2].label.endsWith("2025")) // older years show the year
    }

    @Test fun groupsByMonth() {
        val s = sections(listOf(today, today - 20, today - 30), byMonth = true, today = today) { it }
        assertEquals(listOf(2, 1), s.map { it.items.size }) // Oct, Oct, Sep
    }

    @Test fun emptyListHasNoSections() {
        assertEquals(0, sections(emptyList<Long>(), byMonth = false, today = today) { it }.size)
    }

    // Sections of 2, 1 and 3 photos: H0 c0 c1 | H1 c2 | H2 c3 c4 c5 at grid positions 0..8
    private val index = GridIndex(listOf(2, 1, 3))

    @Test fun gridPositionsMapToSections() {
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2, 2, 2), (0..8).map { index.sectionAt(it) })
        assertEquals(2, index.sectionAt(100)) // past the end stays in the last section
    }

    @Test fun cellPositionsMapToPhotos() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf(1, 2, 4, 6, 7, 8).map { index.mediaAt(it) })
    }

    private val ids = (0L..9L).toList()

    @Test fun dragSelectsTheRangeEitherWayAndShrinksBack() {
        assertEquals((3L..7L).toSet(), rangeSelection(emptySet(), ids, 7, 3, adding = true) { it })
        assertEquals((5L..7L).toSet(), rangeSelection(emptySet(), ids, 7, 5, adding = true) { it }) // dragged back
        assertEquals(setOf(0L, 4L, 5L), rangeSelection(setOf(0L), ids, 4, 5, adding = true) { it }) // keeps earlier picks
    }

    @Test fun dragFromASelectedPhotoClearsTheRange() {
        assertEquals(setOf(1L, 5L, 6L), rangeSelection((1L..6L).toSet(), ids, 2, 4, adding = false) { it })
    }

    @Test fun toggleAllSelectsTheRestOrClearsAll() {
        assertEquals(setOf(1L, 2L, 3L, 9L), toggleAll(setOf(2L, 9L), listOf(1L, 2L, 3L)))
        assertEquals(setOf(9L), toggleAll(setOf(1L, 2L, 3L, 9L), listOf(1L, 2L, 3L)))
    }

    @Test fun autoScrollRampsUpTowardsTheEdges() {
        assertEquals(0f, edgeScroll(500f, top = 100f, bottom = 1000f, zone = 50f), 0f)
        assertEquals(-0.5f, edgeScroll(125f, top = 100f, bottom = 1000f, zone = 50f), 0.001f)
        assertEquals(-1f, edgeScroll(20f, top = 100f, bottom = 1000f, zone = 50f), 0f) // over the top bar
        assertEquals(0.5f, edgeScroll(975f, top = 100f, bottom = 1000f, zone = 50f), 0.001f)
        assertEquals(1f, edgeScroll(1200f, top = 100f, bottom = 1000f, zone = 50f), 0f)
    }
}
