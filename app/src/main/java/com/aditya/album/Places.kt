package com.aditya.album

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a photo was taken, at GeoNames' levels. [city] is what Places and trips call it: the city whose area it's in, else
 * the nearest town, the place itself, its district or its state. [locality] is the named place it's in (a suburb, a
 * village, a beach), [district] the area under the state (GeoNames admin2) and [admin1] the state or province. Each is
 * empty where GeoNames has none, or the photo is too far from places to say.
 */
data class Place(
    val city: String,
    val admin1: String,
    val countryCode: String,
    val country: String,
    val locality: String = "",
    val district: String = "",
)

/**
 * Offline reverse geocoder over GeoNames' populated places of 500+ people (see tools/geonames.py). Places are bucketed
 * into a 1 degree grid, so a lookup only measures the few hundred around the point.
 */
class Places private constructor(private val spots: List<Spot>, private val cellStart: IntArray) {
    private class Area(val countryCode: String, val country: String, val state: String, val district: String)

    /** A GeoNames place. [kind] is C (capital), A (state seat), D (district seat), N (neighbourhood) or a space. */
    private class Spot(val lat: Double, val lng: Double, val name: String, val people: Int, val kind: Char, val area: Area) {
        // ponytail: how far a place's area reaches is guessed from its population (Mumbai 25 km, 5000 people 0.6 km).
        // Tuned on Indian metros and their twin towns; real boundaries would need polygon data.
        val reach get() = min(KM_PER_SQRT_PERSON * sqrt(people.toDouble()), MAX_REACH_KM)

        // Neighbourhoods are never cities or towns: GeoNames puts whole cities' populations on some (Navi Mumbai)
        val isCity get() = kind == 'C' || kind == 'A' || kind == 'D' || (people >= CITY_PEOPLE && kind != 'N')
        val isTown get() = people >= TOWN_PEOPLE && kind != 'N'
    }

    private class Near(val spot: Spot, val km: Double) {
        // A big town says more about the area around it than a village the same distance away
        val weight get() = sqrt(max(spot.people, MIN_PEOPLE).toDouble()) / (km + 1)
        val depth get() = km - spot.reach // below 0 inside the place's reach
    }

    /**
     * The place a GPS point is in, or null when no place is within [MAX_KM] (open sea, empty land).
     * The state comes first, from the places nearest the point, so a big city's reach never crosses a border: Noida stays
     * Uttar Pradesh and Hoboken New Jersey. The city is then the one of that state whose area the point is deepest inside
     * (Bandra and Powai are Mumbai, Thane is Thane), else the town whose reach it's nearest, within [NEAR_KM] (Baga beach
     * is Calangute), else the place it's in, its district or its state (Havelock is Andaman and Nicobar, not Port Blair
     * 43 km across the sea).
     */
    fun at(lat: Double, lng: Double): Place? {
        val near = around(lat, lng).ifEmpty { return null }
        // A vote of the nearest few, so one village GeoNames files under the wrong state (it has Canacona, next to Kānkon
        // in Goa, in Karnataka) doesn't move a beach across the border
        val voters = near.takeWhile { it.km <= near[0].km + VOTE_KM }.take(VOTERS)
        val area = voters.groupBy { it.spot.area.countryCode to it.spot.area.state }.maxBy { (_, v) -> v.sumOf { it.weight } }.value[0].spot.area
        val inState = near.filter { it.spot.area.countryCode == area.countryCode && it.spot.area.state == area.state }
        // Far from every place, the nearest one's district is a guess (Chandratal isn't in Manali's Kullu)
        val district = voters.filter { it in inState && it.spot.area.district.isNotEmpty() && it.km <= DISTRICT_KM }
            .groupBy { it.spot.area.district }.maxByOrNull { (_, v) -> v.sumOf { it.weight } }?.key.orEmpty()
        val locality = inState.firstOrNull { it.km <= LOCALITY_KM }?.spot?.name.orEmpty()
        val city = inState.filter { it.spot.isCity && it.km <= min(it.spot.reach * CITY_STRETCH, CITY_KM) }.minByOrNull { it.depth }
            ?: inState.filter { it.spot.isTown && it.depth <= NEAR_KM }.minByOrNull { it.depth }
        return Place(
            city = city?.spot?.name ?: locality.ifEmpty { district.ifEmpty { area.state.ifEmpty { area.country } } },
            admin1 = area.state,
            countryCode = area.countryCode,
            country = area.country,
            locality = locality,
            district = district,
        )
    }

    /** Every place within [MAX_KM] of the point, nearest first. */
    private fun around(lat: Double, lng: Double): List<Near> {
        val dLat = MAX_KM / KM_PER_DEGREE
        // A degree of longitude shrinks towards the poles, so widen the search there (to the whole ring at the pole).
        val shrink = cos(Math.toRadians(min(abs(lat) + dLat, 90.0)))
        val dLng = if (shrink < 1e-3) 180.0 else min(180.0, dLat / shrink)
        val cols = if (dLng >= 180.0) -180..179 else floor(lng - dLng).toInt()..floor(lng + dLng).toInt()
        val near = ArrayList<Near>()
        for (row in floor(lat - dLat).toInt().coerceAtLeast(-90)..floor(lat + dLat).toInt().coerceAtMost(89)) {
            for (col in cols) {
                val cell = (row + 90) * 360 + Math.floorMod(col + 180, 360) // floorMod wraps across the 180th meridian
                for (i in cellStart[cell] until cellStart[cell + 1]) {
                    val km = distanceKm(lat, lng, spots[i].lat, spots[i].lng)
                    if (km <= MAX_KM) near += Near(spots[i], km)
                }
            }
        }
        near.sortBy { it.km }
        return near
    }

    companion object {
        /** Beyond this a point gets no place rather than a misleading one. */
        const val MAX_KM = 50.0

        /** The place a photo is in: GeoNames points sit in a place's centre, and most places of 500+ people are this small. */
        private const val LOCALITY_KM = 3.0

        /** A city covers its reach and half again, up to this far from its centre. */
        private const val CITY_STRETCH = 1.5
        private const val CITY_KM = 25.0
        private const val CITY_PEOPLE = 100_000

        /** Further than this past the nearest town's reach, a point is named by its place, district or state. */
        private const val NEAR_KM = 20.0
        private const val TOWN_PEOPLE = 5_000

        // The state vote: the nearest places up to this much further than the nearest one, at most this many
        private const val VOTE_KM = 5.0
        private const val VOTERS = 5
        private const val DISTRICT_KM = 20.0
        private const val MIN_PEOPLE = 500 // places.tsv leaves out populations under 5,000; cities500 starts here

        private const val KM_PER_SQRT_PERSON = 0.008
        private const val MAX_REACH_KM = 25.0
        private const val KM_PER_DEGREE = 111.2
        private const val EARTH_RADIUS_KM = 6371.0
        private const val CELLS = 180 * 360

        /** Reads places.tsv (its format is in tools/geonames.py). Call off the main thread: 236k places take a moment. */
        fun parse(lines: Sequence<String>): Places {
            var countryCode = ""
            var country = ""
            var state = ""
            var area = Area("", "", "", "")
            val spots = ArrayList<Spot>()
            for (line in lines) {
                when {
                    line.isEmpty() -> {}
                    line[0] == '=' -> {
                        countryCode = line.substring(1).substringBefore('\t')
                        country = line.substringAfter('\t')
                        state = ""
                        area = Area(countryCode, country, "", "")
                    }
                    line.startsWith(">>") -> area = Area(countryCode, country, state, line.substring(2))
                    line[0] == '>' -> {
                        state = line.substring(1)
                        area = Area(countryCode, country, state, "")
                    }
                    else -> {
                        val f = line.split('\t')
                        spots += Spot(f[0].toDouble(), f[1].toDouble(), f[2], f.getOrNull(3)?.toInt() ?: 0, f.getOrNull(4)?.firstOrNull() ?: ' ', area)
                    }
                }
            }
            spots.sortBy { cellOf(it.lat, it.lng) }
            // cellStart[c] until cellStart[c + 1] are the places in cell c
            val cellStart = IntArray(CELLS + 1)
            spots.forEach { cellStart[cellOf(it.lat, it.lng) + 1]++ }
            for (c in 1..CELLS) cellStart[c] += cellStart[c - 1]
            return Places(spots, cellStart)
        }

        private fun cellOf(lat: Double, lng: Double) =
            (floor(lat).toInt().coerceIn(-90, 89) + 90) * 360 + Math.floorMod(floor(lng).toInt() + 180, 360)

        /** Great-circle (haversine) distance. */
        internal fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val h = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
            return 2 * EARTH_RADIUS_KM * asin(sqrt(min(h, 1.0)))
        }
    }
}

// ---------- Places the user named ----------

/** A place the user pinned on the map and named ("Nani's house"), for the photos taken there. */
data class CustomPlace(val name: String, val lat: Double, val lng: Double)

/** Within this of a custom place a photo was taken there: a house and its garden, with a phone's GPS drifting a little. */
const val CUSTOM_PLACE_KM = 0.15

/** The custom place a GPS point is at: the nearest one within [CUSTOM_PLACE_KM], or null. */
fun customPlaceAt(lat: Double, lng: Double, custom: List<CustomPlace>): CustomPlace? =
    custom.map { it to Places.distanceKm(lat, lng, it.lat, it.lng) }.filter { it.second <= CUSTOM_PLACE_KM }.minByOrNull { it.second }?.first

/**
 * GeoNames' place for a point ([geo], null at sea or far from towns) as a custom place: called by its name at the city
 * and locality levels, so Places, search and the details show it, and kept in GeoNames' district, state and country.
 */
fun namedPlace(geo: Place?, custom: CustomPlace): Place = (geo ?: Place("", "", "", "")).copy(city = custom.name, locality = custom.name)

/** A name as it's kept: on one line, with spaces in place of tabs and line breaks, at most 60 letters. */
fun customPlaceName(text: String) = text.replace(Regex("\\s+"), " ").trim().take(60)

/** Custom places as one line each, "lat, tab, lng, tab, name", for preferences. */
fun encodeCustomPlaces(places: List<CustomPlace>) = places.joinToString("\n") { "${it.lat}\t${it.lng}\t${customPlaceName(it.name)}" }

fun decodeCustomPlaces(text: String?): List<CustomPlace> = text.orEmpty().lines().mapNotNull { line ->
    val f = line.split('\t', limit = 3)
    val lat = f.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
    val lng = f.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
    f.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { CustomPlace(it, lat, lng) }
}

/** [custom] with [place] added; one already called the same (whatever the case) is replaced, so pinning a name again moves it. */
fun withCustomPlace(custom: List<CustomPlace>, place: CustomPlace) = custom.filterNot { it.name.equals(place.name, ignoreCase = true) } + place

/** The places the user named, kept in their own small preferences file. Nothing leaves the phone. */
object CustomPlaces {
    private var prefs: SharedPreferences? = null
    private val all = MutableStateFlow<List<CustomPlace>>(emptyList())

    /** Every custom place, oldest first. Loads them the first time. */
    @Synchronized
    fun load(context: Context): StateFlow<List<CustomPlace>> {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("custom-places", Context.MODE_PRIVATE).also {
                all.value = decodeCustomPlaces(it.getString(KEY, null))
            }
        }
        return all.asStateFlow()
    }

    /** Saves [place], and has the smart index name the photos taken there after it on its next pass. */
    @Synchronized
    fun add(context: Context, place: CustomPlace) {
        load(context)
        all.value = withCustomPlace(all.value, place)
        prefs!!.edit(commit = true) { putString(KEY, encodeCustomPlaces(all.value)) }
        SmartIndex.renamePlaces()
    }

    private const val KEY = "places"
}
