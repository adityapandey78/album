package com.aditya.album

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextLensTest {
    // Two lines on a 100 x 100 photo: "Total due" over "₹ 1,250"
    private val words = listOf(
        LensWord("Total", Rect(0.1f, 0.1f, 0.3f, 0.2f), line = 0),
        LensWord("due", Rect(0.35f, 0.1f, 0.5f, 0.2f), line = 0),
        LensWord("₹", Rect(0.1f, 0.3f, 0.15f, 0.4f), line = 1),
        LensWord("1,250", Rect(0.2f, 0.3f, 0.4f, 0.4f), line = 1),
    )
    private val photo = Rect(0f, 0f, 100f, 100f)

    private fun assertNear(expected: Rect, actual: Rect) =
        listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .forEach { (e, a) -> assertEquals(e, a, 0.001f) }

    @Test fun boxesFollowThePhotoAsItIsDrawn() {
        // fitted lower down, then zoomed in two times
        assertNear(Rect(10f, 60f, 30f, 70f), Rect(0.1f, 0.1f, 0.3f, 0.2f).onPhoto(Rect(0f, 50f, 100f, 150f)))
        assertNear(Rect(20f, 20f, 60f, 40f), Rect(0.1f, 0.1f, 0.3f, 0.2f).onPhoto(Rect(0f, 0f, 200f, 200f)))
    }

    @Test fun selectedTextKeepsLinesApart() {
        assertEquals("Total due\n₹ 1,250", selectedText(words, words.indices))
        assertEquals("due\n₹", selectedText(words, 1..2))
        assertEquals("1,250", selectedText(words, 3..3))
    }

    @Test fun tappingAWordSelectsItsLine() {
        assertEquals(0..1, lineOf(words, 1))
        assertEquals(2..3, lineOf(words, 2))
    }

    @Test fun theNearestWordIsTheOneUnderTheFinger() {
        assertEquals(1, wordNear(words, photo, Offset(40f, 15f))) // inside "due"
        assertEquals(3, wordNear(words, photo, Offset(45f, 35f))) // just right of "1,250"
        assertEquals(0, wordNear(words, photo, Offset(5f, 5f), reach = 8f)) // a little outside "Total"
        assertNull(wordNear(words, photo, Offset(90f, 90f), reach = 8f)) // nowhere near text
        assertNull(wordNear(emptyList(), photo, Offset(0f, 0f)))
    }
}
