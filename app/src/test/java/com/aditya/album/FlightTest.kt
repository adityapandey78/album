package com.aditya.album

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class FlightTest {
    private val phone = Size(1080f, 2400f)

    @Test fun landscapePhotoFitsTheWidthAndIsCentredVertically() {
        assertEquals(Rect(0f, 795f, 1080f, 1605f), fitRect(4000, 3000, phone)) // 1080 x 810, (2400 - 810) / 2 = 795
    }

    @Test fun tallPhotoFitsTheHeightAndIsCentredHorizontally() {
        assertEquals(Rect(240f, 0f, 840f, 2400f), fitRect(1000, 4000, phone)) // 600 x 2400
    }

    @Test fun unknownSizeFillsTheScreen() {
        assertEquals(Rect(0f, 0f, 1080f, 2400f), fitRect(0, 0, phone))
    }

    @Test fun pulledShrinksAroundTheCentreThenMovesDown() {
        assertEquals(Rect(25f, 125f, 75f, 175f), Rect(0f, 0f, 100f, 100f).pulled(dy = 100f, scale = 0.5f))
    }

    @Test fun pulledFollowsTheFingerSideways() {
        assertEquals(Rect(35f, 125f, 85f, 175f), Rect(0f, 0f, 100f, 100f).pulled(dy = 100f, scale = 0.5f, dx = 10f))
    }

    // The design's 390 x 844 phone: Details' band is the top 40% (337.6 high), from 56 down
    private val design = Size(390f, 844f)

    private fun assertRect(expected: Rect, actual: Rect) {
        listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .forEach { (e, a) -> assertEquals(e, a, 0.01f) }
    }

    @Test fun detailsFitsASquarePhotoIntoTheBandsHeight() {
        assertRect(Rect(26.2f, 56f, 363.8f, 393.6f), bandRect(4000, 4000, design, 56f))
    }

    @Test fun detailsFitsAWidePhotoToTheWidthAndCentresItInTheBand() {
        assertRect(Rect(0f, 78.55f, 390f, 371.05f), bandRect(4000, 3000, design, 56f)) // 390 x 292.5
    }
}
