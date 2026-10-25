package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    private val bright = 0.6f
    private val dark = 0.1f

    @Test fun confidentSubjectBeatsScene() {
        assertEquals(Category.Pets, categoryOf(listOf("Beach" to 0.92f, "Dog" to 0.85f, "Sand" to 0.8f), bright))
        assertEquals(Category.Food, categoryOf(listOf("Food" to 0.9f, "Tableware" to 0.8f, "Sky" to 0.6f), bright))
        assertEquals(Category.People, categoryOf(listOf("Selfie" to 0.8f, "Mountain" to 0.9f), bright))
    }

    @Test fun weakSubjectLosesToScene() {
        assertEquals(Category.Beach, categoryOf(listOf("Beach" to 0.9f, "Hair" to 0.6f), bright))
        assertEquals(Category.Food, categoryOf(listOf("Cake" to 0.6f), bright)) // but still counts when it's all there is
    }

    @Test fun strongestSceneWins() {
        assertEquals(Category.Sunsets, categoryOf(listOf("Sunset" to 0.9f, "Sky" to 0.88f, "Beach" to 0.7f), bright))
        assertEquals(Category.Mountains, categoryOf(listOf("Mountain" to 0.9f, "Sky" to 0.85f), bright))
        assertEquals(Category.Beach, categoryOf(listOf("Mountain" to 0.8f, "Beach" to 0.8f), bright)) // tie: declaration order
    }

    @Test fun darkScenesAreNight() {
        assertEquals(Category.Night, categoryOf(listOf("Skyscraper" to 0.8f, "Building" to 0.7f), dark))
        assertEquals(Category.Night, categoryOf(emptyList(), dark))
        assertEquals(Category.Food, categoryOf(listOf("Food" to 0.9f), dark)) // a dim dinner is still food
        assertEquals(Category.Night, categoryOf(listOf("Moon" to 0.8f), bright))
    }

    @Test fun unknownLabelsAreOther() {
        assertEquals(Category.Other, categoryOf(listOf("Chair" to 0.9f, "Not a label" to 0.9f), bright))
        assertEquals(Category.Other, categoryOf(emptyList(), bright))
    }

    @Test fun brightnessIsPerceivedLuma() {
        assertEquals(0f, brightnessOf(IntArray(4) { 0xFF000000.toInt() }), 1e-6f)
        assertEquals(1f, brightnessOf(IntArray(4) { 0xFFFFFFFF.toInt() }), 1e-6f)
        assertEquals(0.587f, brightnessOf(intArrayOf(0xFF00FF00.toInt())), 1e-3f) // green looks brighter than blue
        assertEquals(0f, brightnessOf(IntArray(0)), 0f)
    }
}
