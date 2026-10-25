package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlacesTest {
    // The real asset, so a bad regeneration of tools/geonames.py fails here
    private val places = File("src/main/assets/geonames/places.tsv").useLines { Places.parse(it) }

    private fun city(lat: Double, lng: Double) = places.at(lat, lng)?.city

    @Test fun namesEveryLevel() {
        assertEquals(Place("Bengaluru", "Karnataka", "IN", "India", locality = "Bengaluru", district = "Bangalore Urban"), places.at(12.9756, 77.6050)) // MG Road
        assertEquals(Place("Mumbai", "Maharashtra", "IN", "India", locality = "Powai", district = "Mumbai Suburban"), places.at(19.1176, 72.9060))
        assertEquals(Place("Calangute", "Goa", "IN", "India", locality = "Baga", district = "North Goa"), places.at(15.5553, 73.7517)) // Baga beach
    }

    @Test fun metrosTakeTheirSuburbs() {
        listOf(
            19.0596 to 72.8295, 18.9067 to 72.8147, 19.1136 to 72.8697, 19.2307 to 72.8567, 19.0771 to 72.9986, // Bandra, Colaba, Andheri, Borivali, Vashi
        ).forEach { (lat, lng) -> assertEquals("$lat,$lng", "Mumbai", city(lat, lng)) }
        listOf(
            12.9352 to 77.6245, 12.9698 to 77.7500, 12.8452 to 77.6602, 13.1007 to 77.5963, 12.9081 to 77.4826, // Koramangala, Whitefield, Electronic City, Yelahanka, Kengeri
        ).forEach { (lat, lng) -> assertEquals("$lat,$lng", "Bengaluru", city(lat, lng)) }
        listOf(13.0418 to 80.2341, 12.9010 to 80.2279, 13.0067 to 80.2206).forEach { (lat, lng) -> assertEquals("Chennai", city(lat, lng)) } // T Nagar, Sholinganallur, Guindy
        assertEquals("Hyderabad", city(17.4435, 78.3772)) // HITEC City
        assertEquals("Kolkata", city(22.5867, 88.4171)) // Salt Lake
        assertEquals("Ranchi", city(23.3143, 85.3217)) // the airport
    }

    @Test fun twinCitiesKeepTheirNames() {
        assertEquals("Thāne", city(19.2183, 72.9781))
        assertEquals("Bhayandar", city(19.2813, 72.8686)) // Mira Road
        assertEquals("Tambaram", city(12.9249, 80.1000))
        assertEquals("Pimpri-Chinchwad", city(18.5913, 73.7389)) // Hinjewadi
        assertEquals("Nelamangala", city(13.0998, 77.3936)) // a town past Bengaluru's reach
    }

    @Test fun aCitysReachStopsAtTheStateBorder() {
        assertEquals("Delhi", city(28.6315, 77.2167)) // Connaught Place
        assertEquals("Noida" to "Uttar Pradesh", places.at(28.5708, 77.3261)?.run { city to admin1 }) // inside Delhi's reach
        assertEquals("Ghāziābād", city(28.6692, 77.4538))
        assertEquals("Gurugram", city(28.4687, 77.0628)) // Sector 29
        assertEquals("Faridabad", city(28.4089, 77.3178))
        assertEquals("Hosūr", city(12.7409, 77.8253)) // Tamil Nadu, 30 km from Bengaluru
        assertEquals("Mohali", city(30.7046, 76.7179)) // Punjab, next to Chandigarh
        assertEquals("Panchkula", city(30.6942, 76.8606)) // Haryana, on its other side
        assertEquals(Place("Jersey City", "New Jersey", "US", "United States", locality = "Hoboken", district = "Hudson County"), places.at(40.7440, -74.0324))
        assertEquals("New York City", city(40.7580, -73.9855)) // Times Square, with New Jersey across the river
    }

    @Test fun oneMisfiledVillageDoesntMoveABeach() {
        assertEquals("Goa", places.at(15.0100, 74.0233)?.admin1) // Palolem: GeoNames files Canacona, next door, in Karnataka
    }

    @Test fun touristTownsAreThemselves() {
        assertEquals("Mahabalipuram", city(12.6208, 80.1945)) // not Chengalpattu, the district seat 24 km off
        assertEquals("Hampi", city(15.3350, 76.4600)) // not Hosapete
        assertEquals("Pushkar", city(26.4897, 74.5511)) // not Ajmer
        assertEquals("Manāli", city(32.2432, 77.1892))
        assertEquals("Munnar", city(10.0889, 77.0595))
        assertEquals("Ooty", city(11.4102, 76.6950))
        assertEquals("Kochi", city(9.9658, 76.2421)) // Fort Kochi: the taluk GeoNames gives a city's population is left out
        assertEquals("Agra", city(27.1751, 78.0421)) // the Taj Mahal
        assertEquals("Versailles", city(48.8049, 2.1204))
        assertEquals("Paris", city(48.8584, 2.2945))
    }

    @Test fun aVillageFarFromTownsNamesItself() {
        assertEquals("Neturhāt", city(23.4850, 84.2650)) // Netarhat hill station, 30 km from the nearest town
    }

    @Test fun farFromEveryPlaceTheStateNamesIt() {
        assertEquals(Place("Andaman and Nicobar", "Andaman and Nicobar", "IN", "India"), places.at(11.9761, 92.9876)) // Havelock, not Port Blair 43 km off
        assertEquals(Place("Himachal Pradesh", "Himachal Pradesh", "IN", "India"), places.at(32.4720, 77.62)) // Chandratal in Spiti: no guessed district either
    }

    @Test fun seaNearTheCoastGetsTheCoastalCity() {
        assertEquals("Mumbai", city(18.95, 72.6)) // about 30 km out in the Arabian Sea
    }

    @Test fun openOceanAndEmptyLandGetNothing() {
        assertNull(places.at(10.0, 65.0)) // middle of the Arabian Sea
        assertNull(places.at(33.75, 78.65)) // Pangong lake, 99 km from the nearest place GeoNames knows
    }

    @Test fun searchWrapsAcrossTheDateLineAndPoles() {
        val towns = Places.parse(sequenceOf("=XX\tX", ">", ">>", "0.0\t179.9\tEast\t5000\t", "89.9\t10.0\tNorth\t5000\t"))
        assertEquals("East", towns.at(0.0, -179.95)?.city) // 17 km away, on the other side of the 180th meridian
        assertEquals("North", towns.at(89.95, -170.0)?.city) // 17 km away, across the pole
        assertNull(towns.at(0.0, -179.0))
    }

    @Test fun parsesTheAssetFormat() {
        val towns = Places.parse(sequenceOf("=IN\tIndia", ">Goa", ">>North Goa", "15.496\t73.826\tPanjim\t70991\tA", "15.565\t73.755\tBaga", "=FR\tFrance", ">", ">>", "-8.5\t115.3\tSouth"))
        assertEquals(Place("Panjim", "Goa", "IN", "India", locality = "Panjim", district = "North Goa"), towns.at(15.496, 73.826))
        assertEquals("France", towns.at(-8.5, 115.3)?.country) // a negative latitude isn't a header
    }

    @Test fun distanceIsGreatCircle() {
        assertEquals(1.0, Places.distanceKm(12.9716, 77.5946, 19.0760, 72.8777) / 845.0, 0.01) // Bengaluru to Mumbai
    }

    @Test fun parsesTheWholeAssetQuickly() {
        val started = System.nanoTime()
        val all = File("src/main/assets/geonames/places.tsv").useLines { Places.parse(it) }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000) // a phone is slower, but it's once per indexing pass with GPS photos
        val lookups = System.nanoTime()
        repeat(1000) { all.at(12.9 + it * 0.001, 77.6) }
        assertTrue("1000 lookups took ${(System.nanoTime() - lookups) / 1_000_000} ms", System.nanoTime() - lookups < 2_000_000_000L)
    }
}
