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
}
