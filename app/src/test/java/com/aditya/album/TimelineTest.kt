package com.aditya.album

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val today = LocalDate.of(2026, 10, 25).toEpochDay()

    @Test fun groupsByDayWithFriendlyLabels() {
        val s = sections(listOf(today, today, today - 1, today - 2, today - 400), byMonth = false, today = today) { it }
        assertEquals(listOf(2, 1, 1, 1), s.map { it.items.size })
        assertEquals("Today", s[0].label)
        assertEquals("Yesterday", s[1].label)
        assertTrue(s[2].label.contains("23") && !s[2].label.contains("2026")) // "Fri, 23 Oct": no year this year
        assertTrue(s[3].label.endsWith("2025")) // older years show the year
    }

    @Test fun groupsByMonth() {
        val s = sections(listOf(today, today - 20, today - 30), byMonth = true, today = today) { it }
        assertEquals(listOf(2, 1), s.map { it.items.size }) // Oct, Oct, Sep
    }

    @Test fun emptyListHasNoSections() {
        assertEquals(0, sections(emptyList<Long>(), byMonth = false, today = today) { it }.size)
    }

    @Test fun pinchStepsThroughThreeFourFiveSevenThenMonths() {
        assertEquals(3, stepColumns(4, zoomIn = true))
        assertEquals(3, stepColumns(3, zoomIn = true)) // stays at the closest zoom
        assertEquals(5, stepColumns(4, zoomIn = false))
        assertEquals(7, stepColumns(5, zoomIn = false))
        assertEquals(MONTH_COLUMNS, stepColumns(7, zoomIn = false))
        assertEquals(MONTH_COLUMNS, stepColumns(MONTH_COLUMNS, zoomIn = false))
        assertEquals(7, stepColumns(MONTH_COLUMNS, zoomIn = true))
    }

    @Test fun oldColumnCountsSnapToAStep() {
        assertEquals(3, snapColumns(2)) // older versions had a 2 column step
        assertEquals(5, snapColumns(6))
        assertEquals(4, snapColumns(4))
        assertEquals(MONTH_COLUMNS, snapColumns(20))
    }

    // Two sections, newest first: today (3 photos) and yesterday (2)
    private val twoDays = sections(listOf(today, today, today, today - 1, today - 1), byMonth = false, today = today) { it }
    private val square = { _: Long -> 1f }

    @Test fun gridHasAHeaderThenOneCellPerPhoto() {
        val layout = gridLayout(twoDays, 4, GalleryStyle.Grid, top = false, square)
        assertEquals(listOf(Slot.Header, Slot.Cell, Slot.Cell, Slot.Cell, Slot.Header, Slot.Cell, Slot.Cell), layout.items.map { it.slot })
        assertEquals(listOf(0, 0, 1, 2, 3, 3, 4), layout.items.map { it.from })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), layout.items.map { it.section })
    }

    @Test fun theCallersHeaderComesFirst() {
        val layout = gridLayout(twoDays, 4, GalleryStyle.Grid, top = true, square)
        assertEquals(Slot.Top, layout.items[0].slot)
        assertEquals(Slot.Header, layout.items[1].slot)
        assertEquals(1, layout.headerOf(today))
        assertEquals(5, layout.headerOf(today - 1))
    }

    @Test fun headersAreFoundByDayOrTheNextOlderOne() {
        // Today, yesterday and a day last year: H c | H c | H c
        val layout = gridLayout(sections(listOf(today, today - 1, today - 400), byMonth = false, today = today) { it }, 4, GalleryStyle.Grid, top = false, square)
        assertEquals(0, layout.headerOf(today))
        assertEquals(2, layout.headerOf(today - 1))
        assertEquals(4, layout.headerOf(today - 2)) // no photos that day: the photos before it
        assertEquals(4, layout.headerOf(today - 900)) // before every photo: the oldest
        assertEquals(0, layout.headerOf(today + 3)) // a future day: the newest
        assertEquals(null, gridLayout(emptyList<Section<Long>>(), 4, GalleryStyle.Grid, top = true, square).headerOf(today))
    }

    @Test fun mosaicPutsEachSectionsFirstPhotoInABlock() {
        // 4 columns: the 2x2 photo plus two rows of two beside it, so 5 photos a block
        val ten = sections(List(10) { today }, byMonth = false, today = today) { it }
        val layout = gridLayout(ten, 4, GalleryStyle.Mosaic, top = false, square)
        assertEquals(listOf(Slot.Header, Slot.Feature) + List(5) { Slot.Cell }, layout.items.map { it.slot })
        assertEquals(0 until 5, layout.items[1].let { it.from until it.to })
        // A small day is all block
        val small = gridLayout(twoDays, 3, GalleryStyle.Mosaic, top = false, square)
        assertEquals(listOf(Slot.Header, Slot.Feature, Slot.Header, Slot.Feature), small.items.map { it.slot })
        assertEquals(listOf(0 until 0, 0 until 3, 3 until 3, 3 until 5), small.items.map { if (it.slot == Slot.Feature) it.from until it.to else it.from until it.from })
    }

    @Test fun justifiedRowsFillAboutColumnsSquaresAndKeepAShortLastRowShort() {
        val ten = sections(List(10) { today }, byMonth = false, today = today) { it }
        val rows = gridLayout(ten, 4, GalleryStyle.Justified, top = false, square).items.filter { it.slot == Slot.Row }
        assertEquals(listOf(0 until 4, 4 until 8, 8 until 10), rows.map { it.from until it.to }) // a fifth square would pass 4.6
        assertEquals(listOf(0f, 0f, 2f), rows.map { it.fill }) // two squares: room for two more stays empty
    }

    @Test fun justifiedRowsFollowPhotoShapes() {
        val shapes = listOf(3f, 0.5f, 0.5f, 1.5f, 1.5f, 1f) // a panorama, two portraits, two landscapes, a square
        val section = sections(List(shapes.size) { today }, byMonth = false, today = today) { it }
        var i = 0
        val rows = gridLayout(section, 4, GalleryStyle.Justified, top = false) { shapes[i++] }.items.filter { it.slot == Slot.Row }
        // 3 + 0.5 + 0.5 = 4 fits in 4.6, adding 1.5 would not; then 1.5 + 1.5 + 1 = 4, nearly full, so not short
        assertEquals(listOf(0 until 3, 3 until 6), rows.map { it.from until it.to })
        assertEquals(listOf(0f, 0f), rows.map { it.fill })
    }

    @Test fun keysStayStableAndDistinct() {
        val ids = { i: Int -> 100L + i }
        val grid = gridLayout(twoDays, 4, GalleryStyle.Grid, top = true, square)
        assertEquals(listOf("top", "h$today", 100L, 101L, 102L, "h${today - 1}", 103L, 104L), grid.items.indices.map { grid.keyOf(it, ids) })
        val mosaic = gridLayout(twoDays, 4, GalleryStyle.Mosaic, top = false, square)
        assertEquals("f100", mosaic.keyOf(1, ids))
        val justified = gridLayout(twoDays, 4, GalleryStyle.Justified, top = false, square)
        assertEquals("r103", justified.keyOf(3, ids))
    }

    @Test fun cellWidthsSplitLikeTheLazyGrid() {
        assertEquals(listOf(243, 243, 242, 242), cellWidths(1000, 4, 10).toList()) // 970 px of cells: 2 spare go first
        assertEquals(1000, cellWidths(1000, 4, 10).sum() + 3 * 10)
        assertEquals(listOf(0, 0, 0), cellWidths(0, 3, 2).toList())
    }

    @Test fun justifiedRowFramesShareAHeightAndReachTheEdge() {
        val shapes = floatArrayOf(1f, 2f, 1f)
        val row = GridItem(Slot.Row, 0, 0, 3)
        // (1000 - 2 gaps of 10) / 4 = 245 high; widths follow the shapes
        assertEquals(
            listOf(IntRect(0, 0, 245, 245), IntRect(255, 0, 745, 245), IntRect(755, 0, 1000, 245)),
            tileFrames(row, 1000, 10, 4) { shapes[it] },
        )
        assertEquals(245, itemHeight(row, 1000, 10, 4) { shapes[it] })
    }

    @Test fun aShortJustifiedRowKeepsANormalHeight() {
        val row = GridItem(Slot.Row, 0, 0, 1, fill = 3f) // one square where four would fit
        assertEquals(listOf(IntRect(0, 0, 250, 250)), tileFrames(row, 1000, 10, 4) { 1f })
    }

    @Test fun mosaicBlockFramesLineUpWithTheCellsBelow() {
        val block = GridItem(Slot.Feature, 0, 0, 5)
        // Cells 243, 243, 242, 242 wide at x 0, 253, 506, 758
        assertEquals(
            listOf(
                IntRect(0, 0, 496, 496), // 2x2: two cells and the gap between, both ways
                IntRect(506, 0, 748, 243), IntRect(758, 0, 1000, 243),
                IntRect(506, 253, 748, 496), IntRect(758, 253, 1000, 496),
            ),
            tileFrames(block, 1000, 10, 4) { 1f },
        )
        assertEquals(496, itemHeight(block, 1000, 10, 4) { 1f })
        assertEquals(2, tileFrames(GridItem(Slot.Feature, 0, 0, 2), 1000, 10, 4) { 1f }.size) // a small day
    }

    @Test fun estimatedHeightsCountEachLineOfCellsOnce() {
        // Top | H(today) c c c | H(yesterday) c c at 2 columns, 1000 px wide with 10 px gaps: 495 px cells
        val layout = gridLayout(twoDays, 2, GalleryStyle.Grid, top = true, square)
        val heights = estimateHeights(layout, 1000, 10, top = 100, header = 50, firstHeader = 30, shapeAt = { 1f })
        assertEquals(listOf(110, 40, 0, 505, 505, 60, 0, 505), heights.toList())
    }

    @Test fun aPlaceLineAddsToItsDaysHeaderOnly() {
        // As above, with a place under yesterday's date (16 px)
        val layout = gridLayout(twoDays, 2, GalleryStyle.Grid, top = true, square)
        val heights = estimateHeights(layout, 1000, 10, top = 100, header = 50, firstHeader = 30, shapeAt = { 1f }) { if (it == 1) 16 else 0 }
        assertEquals(listOf(110, 40, 0, 505, 505, 76, 0, 505), heights.toList())
    }

    @Test fun placeLineIsTownAndState() {
        assertEquals("Bengaluru, Karnataka", placeLine(Place("Bengaluru", "Karnataka", "IN", "India")))
        assertEquals("Monaco", placeLine(Place("Monaco", "", "MC", "Monaco"))) // no state: the town alone
        assertEquals("Delhi", placeLine(Place("Delhi", "Delhi", "IN", "India"))) // not "Delhi, Delhi"
        assertEquals(null, placeLine(Place("", "", "", ""))) // too far from anywhere: no line
    }

    @Test fun everyItemKnowsItsMonth() {
        val days = listOf(today, today - 30) // 25 Oct, 25 Sep 2026
        val s = sections(days, byMonth = false, today = today) { it }
        val months = itemMonths(gridLayout(s, 4, GalleryStyle.Grid, top = true, square), s) { it }
        assertEquals(listOf(2026 * 12 + 9, 2026 * 12 + 9, 2026 * 12 + 9, 2026 * 12 + 8, 2026 * 12 + 8), months.toList())
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

    @Test fun spokenDurations() {
        assertEquals("42 seconds", spokenDuration(42_000))
        assertEquals("1 second", spokenDuration(1_500))
        assertEquals("1 minute", spokenDuration(60_000))
        assertEquals("3 minutes 5 seconds", spokenDuration(185_000))
        assertEquals("1 hour 2 minutes", spokenDuration(3_725_000))
        assertFalse(spokenDuration(7_200_000).contains("minute")) // "2 hours"
    }
}
