package com.aditya.album

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a photo was taken, at town level. [admin1] is the state or province, empty when GeoNames has none. */
data class Place(val city: String, val admin1: String, val countryCode: String, val country: String)

/**
 * Offline reverse geocoder over GeoNames towns (population 5000+, see tools/geonames.py).
 * Towns are bucketed into a 1 degree grid, so a lookup only measures the few hundred towns around the point.
 */
class Places private constructor(private val towns: List<Town>, private val cellStart: IntArray) {
    private class Town(val lat: Double, val lng: Double, val radius: Double, val place: Place)

    /**
     * The town a GPS point belongs to; its state or region when every town is far off (a trek, a beach on an island);
     * null when no town is within [MAX_KM] (open sea). Big cities cover more ground, so Bandra and Powai are Mumbai, not
     * the small suburb whose centre is nearest. That reach stops at the state border, though: where the point is also
     * inside a town of another state, the state is the one whose town it is deeper inside, so Noida stays Uttar Pradesh
     * instead of becoming Delhi while Ghaziabad's edge stays Ghaziabad.
     */
    fun nearest(lat: Double, lng: Double): Place? {
        val dLat = MAX_KM / KM_PER_DEGREE
        // A degree of longitude shrinks towards the poles, so widen the search there (to the whole ring at the pole).
        val shrink = cos(Math.toRadians(min(abs(lat) + dLat, 90.0)))
        val dLng = if (shrink < 1e-3) 180.0 else min(180.0, dLat / shrink)
        val cols = if (dLng >= 180.0) -180..179 else floor(lng - dLng).toInt()..floor(lng + dLng).toInt()
        val near = ArrayList<Pair<Town, Double>>()
        for (row in floor(lat - dLat).toInt().coerceAtLeast(-90)..floor(lat + dLat).toInt().coerceAtMost(89)) {
            for (col in cols) {
                val cell = (row + 90) * 360 + Math.floorMod(col + 180, 360) // floorMod wraps across the 180th meridian
                for (i in cellStart[cell] until cellStart[cell + 1]) {
                    val km = distanceKm(lat, lng, towns[i].lat, towns[i].lng)
                    if (km <= MAX_KM) near += towns[i] to km
                }
            }
        }
        // Inside towns of two states (a city's reach spilling over the border): the state of the town it's deeper inside
        val inside = near.filter { (town, km) -> km <= town.radius }
        val state = inside.takeIf { it.distinctBy { (town) -> town.place.admin1 }.size > 1 }?.minBy { (town, km) -> km / town.radius }?.first?.place?.admin1
        val (best, km) = near.filter { (town) -> state == null || town.place.admin1 == state }.minByOrNull { (town, km) -> km - town.radius } ?: return null
        // Far from every town, the state says more than a town 40 km away
        return if (km - best.radius <= NEAR_KM) best.place else best.place.run { copy(city = admin1.ifEmpty { country }) }
    }

    companion object {
        /** Beyond this a point gets no place rather than a misleading one. */
        const val MAX_KM = 50.0

        /** Further than this past the nearest town's reach, a point is named by its state: Havelock isn't Port Blair. */
        private const val NEAR_KM = 20.0
        private const val KM_PER_DEGREE = 111.2
        private const val EARTH_RADIUS_KM = 6371.0
        private const val CELLS = 180 * 360

        // ponytail: a city's reach is guessed from its population (Mumbai about 25 km, a 5000 person town about 0.6 km).
        // Tuned on Indian metros and their twin towns; real city boundaries would need polygon data.
        private const val KM_PER_SQRT_PERSON = 0.008
        private const val MAX_RADIUS_KM = 25.0

        /** Reads the tab-separated asset: lat, lng, name, state, country code, country, population. Call off the main thread. */
        fun parse(lines: Sequence<String>): Places {
            val shared = HashMap<String, String>() // state and country names repeat thousands of times
            fun String.shared() = shared.getOrPut(this) { this }
            val towns = lines.filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t')
                Town(
                    lat = f[0].toDouble(),
                    lng = f[1].toDouble(),
                    radius = min(KM_PER_SQRT_PERSON * sqrt(f[6].toDouble()), MAX_RADIUS_KM),
                    place = Place(f[2], f[3].shared(), f[4].shared(), f[5].shared()),
                )
            }.sortedBy { cellOf(it.lat, it.lng) }.toList()
            // cellStart[c] until cellStart[c + 1] are the towns in cell c
            val cellStart = IntArray(CELLS + 1)
            towns.forEach { cellStart[cellOf(it.lat, it.lng) + 1]++ }
            for (c in 1..CELLS) cellStart[c] += cellStart[c - 1]
            return Places(towns, cellStart)
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
