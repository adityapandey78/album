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

    @Test fun aCitysReachStopsAtTheStateBorder() {
        assertEquals("Delhi", city(28.6315, 77.2167)) // Connaught Place
        assertEquals("Noida", city(28.5708, 77.3261)) // Uttar Pradesh, inside Delhi's reach
        assertEquals("Ghāziābād", city(28.6692, 77.4538)) // also inside it, from the other side
        assertEquals("Mohali", city(30.7046, 76.7179)) // Punjab, inside Chandigarh's reach
        assertEquals("Hoboken", city(40.7440, -74.0324)) // New Jersey, across the river from New York
        assertEquals("New York City", city(40.7580, -73.9855)) // Times Square, with Hoboken's state across that river
    }

    @Test fun farFromEveryTownTheStateNamesIt() {
        assertEquals(Place("Andaman and Nicobar", "Andaman and Nicobar", "IN", "India"), places.nearest(11.9761, 92.9876)) // Havelock, not Port Blair 43 km off
        assertEquals("Himachal Pradesh", city(32.4720, 77.6200)) // Chandratal in Spiti, 45 km from Manali
        assertEquals("Calangute", city(15.5553, 73.7517)) // Baga beach: a town nearby is still the town
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
        assertEquals("East", towns.nearest(0.0, -179.95)?.city) // 17 km away, on the other side of the 180th meridian
        assertEquals("North", towns.nearest(89.95, -170.0)?.city) // 17 km away, across the pole
        assertNull(towns.nearest(0.0, -179.0))
    }

    @Test fun distanceIsGreatCircle() {
        assertEquals(1.0, Places.distanceKm(12.9716, 77.5946, 19.0760, 72.8777) / 845.0, 0.01) // Bengaluru to Mumbai
    }
}
