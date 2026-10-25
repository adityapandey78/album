package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TripsTest {
    private class Photo(val name: String, val day: Long, val place: Place?)

    private val home = Place("Bengaluru", "Karnataka", "IN", "India")
    private val calangute = Place("Calangute", "Goa", "IN", "India")
    private val panaji = Place("Panaji", "Goa", "IN", "India")
    private val mumbai = Place("Mumbai", "Maharashtra", "IN", "India")
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    /** [n] photos on a day at a place, named after the day so tests can say which days a trip took in. */
    private fun on(date: Long, place: Place?, n: Int = 3) = List(n) { Photo("${LocalDate.ofEpochDay(date).dayOfMonth}", date, place) }

    /** Plenty of photos at home through 2026, so home is clear, plus [others]; newest first like the Library. */
    private fun library(vararg others: List<Photo>) =
        ((1..200 step 4).flatMap { on(day(2026, 1, 1) + it, home, 5) } + others.flatMap { it }).sortedByDescending { it.day }

    private fun trips(photos: List<Photo>) = trips(photos, Photo::day, Photo::place)

    @Test fun daysInARowAwayAreOneTripNewestFirst() {
        val photos = library(
            on(day(2026, 10, 8), calangute), on(day(2026, 10, 9), calangute), on(day(2026, 10, 10), panaji),
            on(day(2026, 8, 14), mumbai), on(day(2026, 8, 15), mumbai),
        )
        val t = trips(photos)
        assertEquals(listOf("Goa, Oct ’26", "Mumbai, Aug ’26"), t.map { it.title })
        assertEquals("Trip · 3 days", t[0].eyebrow)
        assertEquals(listOf("8", "9", "10"), t[0].items.map { it.name }.distinct()) // oldest first
    }

    @Test fun aDayWithoutLocatedPhotosDoesNotSplitATripButAHomeDayDoes() {
        val bridged = library(on(day(2026, 10, 8), calangute), on(day(2026, 10, 9), null), on(day(2026, 10, 10), calangute))
        assertEquals(listOf("Trip · 3 days"), trips(bridged).map { it.eyebrow })
        val split = library(on(day(2026, 10, 8), calangute, 15), on(day(2026, 10, 9), home), on(day(2026, 10, 10), calangute, 15))
        assertEquals(listOf("Trip · 1 day", "Trip · 1 day"), trips(split).map { it.eyebrow })
        val apart = library(on(day(2026, 10, 8), calangute), on(day(2026, 10, 11), calangute))
        assertEquals(emptyList<String>(), trips(apart).map { it.eyebrow }) // two lone days out, 3 days apart
    }

    @Test fun oneDayNeedsFifteenPhotosAndTwoDaysNeedFive() {
        assertEquals(emptyList<String>(), trips(library(on(day(2026, 10, 8), mumbai, 14))).map { it.title })
        assertEquals(listOf("Mumbai, Oct ’26"), trips(library(on(day(2026, 10, 8), mumbai, 15))).map { it.title })
        assertEquals(emptyList<String>(), trips(library(on(day(2026, 10, 8), mumbai, 2), on(day(2026, 10, 9), mumbai, 2))).map { it.title })
    }

    @Test fun aMonthAwayIsNotATrip() {
        val away = (0L..30L).map { on(day(2026, 8, 1) + it, mumbai, 2) }
        assertEquals(emptyList<String>(), trips(library(*away.toTypedArray())).map { it.title })
    }

    @Test fun homePhotosOnTheWayOutStayOutOfTheTrip() {
        val leaving = on(day(2026, 10, 8), home, 2) + on(day(2026, 10, 8), mumbai, 3)
        val t = trips(library(leaving, on(day(2026, 10, 9), mumbai)))
        assertEquals(6, t.single().items.size)
        assertEquals(true, t.single().items.all { it.place == mumbai })
    }

    @Test fun aTownWithHalfTheTopTownsPhotosIsHomeToo() {
        // Weekdays at the office in Mumbai, weekends at home in Bengaluru: neither is a trip
        val monday = day(2026, 3, 2)
        val weeks = (0 until 20).flatMap { w -> (0 until 7).flatMap { d -> if (d < 5) on(monday + 7 * w + d, mumbai) else on(monday + 7 * w + d, home, 4) } }
        assertEquals(emptyList<String>(), trips(weeks.sortedByDescending { it.day }).map { it.title })
    }

    @Test fun namesUseTheStateWhenATripTookInSeveralOfItsTowns() {
        assertEquals(listOf("Goa"), tripNames(listOf(calangute, panaji, calangute)))
        assertEquals(listOf("Goa", "Mumbai"), tripNames(listOf(mumbai, calangute, panaji)))
        assertEquals(listOf("Mumbai", "Calangute"), tripNames(listOf(mumbai, mumbai, calangute)))
        val t = trips(library(on(day(2026, 10, 8), mumbai, 5), on(day(2026, 10, 9), calangute), on(day(2026, 10, 9), panaji)))
        assertEquals("Goa and Mumbai", t.single().title)
    }

    @Test fun noPlacesNoTrips() {
        assertEquals(emptyList<String>(), trips(on(day(2026, 10, 8), null, 30)).map { it.title })
    }

    @Test fun widgetShowsOnThisDayElseARecentTripElseADailyFavourite() {
        val today = LocalDate.of(2026, 10, 25)
        val favourites = setOf("fav1", "fav2")
        fun pick(photos: List<Photo>) = widgetPick(photos, today, Photo::day, { it.name in favourites }, Photo::place)
        val lastYear = listOf(Photo("cover", day(2025, 10, 25), null), Photo("fav1", day(2025, 10, 25), null))
        val trip = on(day(2026, 8, 14), mumbai) + on(day(2026, 8, 15), mumbai)

        val (memory, photo) = pick(library(lastYear, trip))!!
        assertEquals("On this day · 2025", memory.eyebrow)
        assertEquals("fav1", photo.name) // a favourite beats the cover

        assertEquals("Mumbai, Aug ’26", pick(library(trip))!!.first.title)

        val oldTrip = on(day(2025, 8, 14), mumbai) + on(day(2025, 8, 15), mumbai) // over a year ago
        val favs = listOf(Photo("fav1", day(2026, 2, 3), home), Photo("fav2", day(2026, 2, 4), home))
        val (favourite, shown) = pick(library(oldTrip, favs))!!
        assertEquals("fav${2 - today.toEpochDay() % 2}", shown.name) // newest first, one per day in turn
        assertEquals("Favourite · 2026", favourite.eyebrow)

        assertEquals(null, pick(library()))
    }
}
