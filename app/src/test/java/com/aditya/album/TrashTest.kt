package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test

class TrashTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test fun freshlyTrashedShowsThirtyDays() {
        assertEquals(30, daysLeft(now + 30 * day, now))
        assertEquals(30, daysLeft(now + 30 * day - 1000, now)) // a second later still rounds up
    }

    @Test fun partDaysRoundUp() {
        assertEquals(1, daysLeft(now + day, now))
        assertEquals(1, daysLeft(now + 60_000, now))
        assertEquals(2, daysLeft(now + day + 1, now))
    }

    @Test fun overdueItemsNeverShowZero() {
        assertEquals(1, daysLeft(now, now))
        assertEquals(1, daysLeft(now - 3 * day, now)) // Android's daily cleanup hasn't run yet
    }

    @Test fun countsItems() {
        assertEquals("1 item", itemCount(1))
        assertEquals("3 items", itemCount(3))
        assertEquals("0 items", itemCount(0))
        assertEquals("1 day", counted(1, "day", "days"))
        assertEquals("%,d people".format(2000), counted(2000, "person", "people")) // grouped the phone's way
    }
}
