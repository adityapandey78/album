package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerTest {
    private fun swipe(offset: Float, velocity: Float) = swipeOf(offset, velocity, distance = 100f, speed = 1000f)

    @Test fun shortSlowDragSpringsBack() {
        assertEquals(Swipe.None, swipe(50f, 200f))
        assertEquals(Swipe.None, swipe(-50f, -200f))
    }

    @Test fun longPullDownClosesAndUpOpensDetails() {
        assertEquals(Swipe.Close, swipe(150f, 0f))
        assertEquals(Swipe.Details, swipe(-150f, 0f))
    }

    @Test fun quickFlickIsEnoughEvenWhenShort() {
        assertEquals(Swipe.Close, swipe(20f, 1500f))
        assertEquals(Swipe.Details, swipe(-20f, -1500f))
    }

    @Test fun flingingBackCancels() {
        assertEquals(Swipe.None, swipe(150f, -1500f)) // pulled down far, then flicked up
        assertEquals(Swipe.None, swipe(-150f, 1500f))
        assertEquals(Swipe.None, swipe(-20f, 1500f)) // a downward flick never opens details
    }
}
