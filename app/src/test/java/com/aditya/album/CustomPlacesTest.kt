package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomPlacesTest {
    private val nani = CustomPlace("Nani's house", 15.4909, 73.8278) // Panaji
    private val office = CustomPlace("Office", 15.4920, 73.8278) // about 120 m north of it

    @Test fun aPhotoAFewStepsAwayIsAtTheCustomPlace() {
        assertEquals(nani, customPlaceAt(15.4912, 73.8280, listOf(nani))) // about 40 m
    }

    @Test fun aPhotoFurtherThanTheRadiusIsNot() {
        assertNull(customPlaceAt(15.4909, 73.8300, listOf(nani))) // about 240 m east
        assertNull(customPlaceAt(15.4909, 73.8278, emptyList()))
    }

    @Test fun theNearestOfTwoCloseCustomPlacesWins() {
        assertEquals(office, customPlaceAt(15.4918, 73.8278, listOf(nani, office)))
        assertEquals(nani, customPlaceAt(15.4910, 73.8278, listOf(office, nani)))
    }

    @Test fun aCustomPlaceKeepsGeoNamesStateAndCountry() {
        val goa = Place("Panaji", "Goa", "IN", "India", locality = "Panaji", district = "North Goa")
        assertEquals(Place("Nani's house", "Goa", "IN", "India", locality = "Nani's house", district = "North Goa"), namedPlace(goa, nani))
        assertEquals(Place("Nani's house", "", "", "", locality = "Nani's house"), namedPlace(null, nani))
    }

    @Test fun placesSurviveSavingAndANameIsKeptOnOneLine() {
        val odd = CustomPlace("Beach\thut\nnorth", -8.5, 115.25)
        assertEquals(listOf(nani, odd.copy(name = "Beach hut north")), decodeCustomPlaces(encodeCustomPlaces(listOf(nani, odd))))
        assertEquals(emptyList<CustomPlace>(), decodeCustomPlaces(null))
        assertEquals(listOf(nani), decodeCustomPlaces("bad line\n" + encodeCustomPlaces(listOf(nani))))
    }

    @Test fun pinningANameAgainMovesIt() {
        val moved = CustomPlace("NANI'S HOUSE", 15.5, 73.9)
        assertEquals(listOf(office, moved), withCustomPlace(listOf(nani, office), moved))
    }
}
