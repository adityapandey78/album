package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTest {
    @Test fun speedsReadLikeTheDesign() {
        assertEquals("0.5×", speedLabel(0.5f))
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2×", speedLabel(2f))
    }

    @Test fun doubleTapSkipsOnTheSidesAndZoomsInTheMiddle() {
        assertEquals(-1, skipSide(0f))
        assertEquals(-1, skipSide(0.39f))
        assertEquals(0, skipSide(0.4f))
        assertEquals(0, skipSide(0.5f))
        assertEquals(0, skipSide(0.6f))
        assertEquals(1, skipSide(0.61f))
        assertEquals(1, skipSide(1f))
    }

    @Test fun draggingTheFilmstripLeftMovesLater() {
        assertEquals(0.5f, stripFraction(0.5f, 0f, 672f), 0f)
        assertEquals(0.75f, stripFraction(0.5f, -168f, 672f), 1e-6f) // a quarter of the strip to the left
        assertEquals(0.25f, stripFraction(0.5f, 168f, 672f), 1e-6f)
        assertEquals(0f, stripFraction(0.1f, 500f, 672f), 0f) // stops at the start
        assertEquals(1f, stripFraction(0.9f, -500f, 672f), 0f) // and at the end
        assertEquals(0.3f, stripFraction(0.3f, 50f, 0f), 0f) // not laid out yet
    }

    @Test fun eachFilmstripFrameShowsTheMiddleOfItsShare() {
        assertEquals(500L, stripFrameAt(0, 16_000))
        assertEquals(15_500L, stripFrameAt(STRIP_FRAMES - 1, 16_000))
        assertEquals(0L, stripFrameAt(0, 0))
    }
}
