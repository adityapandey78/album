package com.aditya.album

import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Trips, found from where photos were taken: days in a row away from home. Worked out on the phone from the smart index's
// towns (see Explore.places); nothing is uploaded.

// Home is where most photos of the last half year were taken
private const val HOME_DAYS = 180

// A town with at least half the photos of the top one is home too: the office in the next town, or twin cities
private const val HOME_SHARE = 2

// A day without located photos (GPS off, nothing taken) doesn't split a trip
private const val MAX_GAP_DAYS = 1

// A trip is at least 2 days or a busy day out, with enough photos for a story. Over a month away is living somewhere.
private const val MIN_DAYS = 2
private const val BUSY_DAY = 15
private const val MIN_PHOTOS = 5
private const val MAX_DAYS = 30

private val tripMonth = DateTimeFormatter.ofPattern("MMM ’yy")

/**
 * Trips in [photos] (newest first, as the Library holds them), newest first, each played oldest first. A trip is a run of
 * days whose located photos were mostly taken away from home; [placeOf] is a photo's town, null when it isn't known.
 */
fun <T> trips(photos: List<T>, dayOf: (T) -> Long, placeOf: (T) -> Place?): List<Memory<T>> {
    val located = photos.mapNotNull { p -> placeOf(p)?.let { p to it } }
    if (located.isEmpty()) return emptyList()
    val newest = dayOf(located.first().first)
    val recent = located.filter { newest - dayOf(it.first) < HOME_DAYS }.groupingBy { it.second }.eachCount()
    val top = recent.values.max()
    // ponytail: one home for the whole Library, so after a move the old city's visits show up as trips. A home per half
    // year would fix that if it comes up.
    val homes = recent.filterValues { it * HOME_SHARE >= top }.keys

    val out = ArrayList<Memory<T>>()
    var run = ArrayList<Pair<T, Place>>()
    var last = 0L
    fun close() {
        if (run.isNotEmpty()) tripOf(run, dayOf)?.let(out::add)
        run = ArrayList()
    }
    // Oldest day first (groupBy keeps the order it meets them in), photos oldest first within each
    for ((day, items) in located.asReversed().groupBy { dayOf(it.first) }) {
        val away = items.filter { it.second !in homes }
        // A day mostly at home ends the trip; the photos from home that day were taken before leaving or after coming back
        if (away.isEmpty() || away.size * 2 < items.size) {
            close()
            continue
        }
        if (run.isNotEmpty() && day - last > MAX_GAP_DAYS + 1) close()
        run += away
        last = day
    }
    close()
    return out.asReversed()
}

/** A run of days away as a trip memory, or null when it's too small or too long to be one. */
private fun <T> tripOf(run: List<Pair<T, Place>>, dayOf: (T) -> Long): Memory<T>? {
    val first = dayOf(run.first().first)
    val days = (dayOf(run.last().first) - first + 1).toInt()
    if (days > MAX_DAYS || run.size < MIN_PHOTOS || (days < MIN_DAYS && run.size < BUSY_DAY)) return null
    val names = tripNames(run.map { it.second })
    val title = if (names.size == 1) "${names[0]}, ${LocalDate.ofEpochDay(first).format(tripMonth)}" else "${names[0]} and ${names[1]}"
    return Memory("Trip · ${counted(days, "day", "days")}", title, run.map { it.first })
}

/**
 * What a trip's places are called, most photos first: each town, or its state when the trip took in several towns of it
 * (Calangute and Panaji are Goa). [places] has one entry per photo.
 */
fun tripNames(places: List<Place>): List<String> {
    val townsPerState = places.distinct().groupingBy { it.countryCode to it.admin1 }.eachCount()
    fun nameOf(p: Place) = if (p.admin1.isNotEmpty() && townsPerState.getValue(p.countryCode to p.admin1) > 1) p.admin1 else p.city
    return places.groupingBy(::nameOf).eachCount().entries.sortedByDescending { it.value }.map { it.key }
}

/** Each photo's town from [Explore.places], by MediaStore id. */
fun placesById(places: List<Group<Place, Media>>): Map<Long, Place> {
    val out = HashMap<Long, Place>()
    places.forEach { group -> group.items.forEach { out[it.id] = group.key } }
    return out
}
