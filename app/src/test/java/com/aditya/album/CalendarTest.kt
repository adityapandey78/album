package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarTest {
    private fun dates(week: List<Long?>) = week.map { it?.let { day -> LocalDate.ofEpochDay(day).dayOfMonth } }

    @Test fun groupsNewestFirstMediaPerDay() {
        val days = photoDays(listOf("a" to 9L, "b" to 9L, "c" to 9L, "d" to 7L, "e" to 3L, "f" to 3L)) { it.second }
        assertEquals(listOf(9L, 7L, 3L), days.keys.toList())
        assertEquals(listOf(3, 1, 2), days.values.map { it.count })
        assertEquals(listOf("a", "d", "e"), days.values.map { it.first.first }) // the newest photo stands for the day
        assertTrue(photoDays(emptyList<Long>()) { it }.isEmpty())
    }

    @Test fun monthStartsAfterBlanksForTheLocalesFirstDayOfWeek() {
        val october = YearMonth.of(2026, 10) // 1 October 2026 is a Thursday
        assertEquals(listOf(null, null, null, 1, 2, 3, 4), dates(weeksOf(october, DayOfWeek.MONDAY)[0]))
        assertEquals(listOf(null, null, null, null, 1, 2, 3), dates(weeksOf(october, DayOfWeek.SUNDAY)[0]))
        assertEquals(listOf(null, null, null, null, null, 1, 2), dates(weeksOf(october, DayOfWeek.SATURDAY)[0]))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), dates(weeksOf(october, DayOfWeek.THURSDAY)[0]))
    }

    @Test fun weeksCoverTheWholeMonthAndPadTheLastOne() {
        val weeks = weeksOf(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(5, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(listOf(26, 27, 28, 29, 30, 31, null), dates(weeks.last()))
        assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), weeks[2][0])
    }

    @Test fun monthLengthsAndLeapYears() {
        fun daysIn(year: Int, month: Int) = weeksOf(YearMonth.of(year, month), DayOfWeek.MONDAY).flatten().count { it != null }
        assertEquals(29, daysIn(2024, 2))
        assertEquals(28, daysIn(2026, 2))
        assertEquals(28, daysIn(2100, 2)) // centuries are not leap years
        assertEquals(29, daysIn(2000, 2)) // unless divisible by 400
        assertEquals(30, daysIn(2026, 4))
        assertEquals(31, daysIn(2026, 12))
        // February 2026 starts on a Sunday: exactly 4 rows from Sunday, 5 from Monday
        assertEquals(4, weeksOf(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size)
        assertEquals(5, weeksOf(YearMonth.of(2026, 2), DayOfWeek.MONDAY).size)
    }

    @Test fun describesADayForTalkBack() {
        val day = LocalDate.of(2026, 10, 24).toEpochDay()
        assertTrue(dayDescription(day, 12).startsWith("24 "))
        assertTrue(dayDescription(day, 12).endsWith(", 12 photos"))
        assertTrue(dayDescription(day, 1).endsWith(", 1 photo"))
        assertTrue(!dayDescription(day, 0).contains(","))
    }
}
