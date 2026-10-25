package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlacesTest {
    // The real asset, so a bad regeneration of tools/geonames.py fails here
    private val places = File("src/main/assets/geonames/cities.tsv").useLines { Places.parse(it) }

    private fun city(lat: Double, lng: Double) = places.nearest(lat, lng)?.city

    @Test fun findsIndianCities() {
        assertEquals(Place("Bengaluru", "Karnataka", "IN", "India"), places.nearest(12.9756, 77.6050)) // MG Road
        assertEquals("Mumbai", city(19.0596, 72.8295)) // Bandra
        assertEquals("Ooty", city(11.4102, 76.6950))
    }

    @Test fun bigCitiesWinOverSuburbsButNotTwinCities() {
        assertEquals("Mumbai", city(19.1176, 72.9060)) // Powai has its own GeoNames entry
        assertEquals("Mumbai", city(18.9067, 72.8147)) // Colaba: a small town across the harbour is nearer
        assertEquals("Thāne", city(19.2183, 72.9781))
        assertEquals("Hosūr", city(12.7409, 77.8253)) // Tamil Nadu, 30 km from Bengaluru
    }

    @Test fun seaNearTheCoastGetsTheCoastalCity() {
        assertEquals("Mumbai", city(18.95, 72.6)) // about 30 km out in the Arabian Sea
    }

    @Test fun openOceanAndEmptyLandGetNothing() {
        assertNull(places.nearest(10.0, 65.0)) // middle of the Arabian Sea
        assertNull(places.nearest(33.75, 78.65)) // Pangong lake, over 100 km from the nearest 5000 person town
    }

    @Test fun searchWrapsAcrossTheDateLineAndPoles() {
        val towns = Places.parse(sequenceOf("0.0\t179.9\tEast\t\tXX\tX\t5000", "89.9\t10.0\tNorth\t\tXX\tX\t5000"))
        assertEquals("East", towns.nearest(0.0, -179.9)?.city) // 22 km away, on the other side of the 180th meridian
        assertEquals("North", towns.nearest(89.95, -170.0)?.city) // 17 km away, across the pole
        assertNull(towns.nearest(0.0, -179.0))
    }

    @Test fun distanceIsGreatCircle() {
        assertEquals(1.0, Places.distanceKm(12.9716, 77.5946, 19.0760, 72.8777) / 845.0, 0.01) // Bengaluru to Mumbai
    }
}
