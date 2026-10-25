package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun textIsReadWhereItLikelyIs() {
        assertEquals(TextChance.Sure, textChance(emptyList(), screenshot = true, edges = 0f))
        assertEquals(TextChance.Sure, textChance(listOf("Receipt" to 0.8f), screenshot = false, edges = 0f))
        assertEquals(TextChance.Sure, textChance(listOf("Mobile phone" to 0.6f), screenshot = false, edges = 0f)) // a photo of a screen
        assertEquals(TextChance.Maybe, textChance(listOf("Receipt" to 0.35f), screenshot = false, edges = 0f)) // ML Kit isn't sure
        assertEquals(TextChance.Maybe, textChance(listOf("Product" to 0.7f), screenshot = false, edges = 0f)) // packaging
        assertEquals(TextChance.Maybe, textChance(listOf("Dog" to 0.9f), screenshot = false, edges = 0.2f)) // a sign behind the dog, maybe
        assertEquals(TextChance.None, textChance(listOf("Dog" to 0.9f, "Beach" to 0.8f), screenshot = false, edges = 0.02f))
    }

    @Test fun edgesAreDenseInTextAndRareInSkies() {
        val w = 120
        val h = 60
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertEquals(0f, edgeDensity(IntArray(w * h) { white }, w), 0f)
        // A sky: a smooth gradient never jumps
        assertEquals(0f, edgeDensity(IntArray(w * h) { i -> grey(i / w * 255 / h) }, w), 0f)
        // Lines of "print": 2 px strokes 2 px apart, 4 px tall lines with 4 px of paper between
        val page = IntArray(w * h) { i -> if ((i / w) % 8 < 4 && (i % w) % 4 < 2) black else white }
        assertTrue(edgeDensity(page, w) > 0.2f)
        // Gentle noise, like a wall or skin: small steps don't count
        val noise = java.util.Random(1)
        assertEquals(0f, edgeDensity(IntArray(w * h) { grey(120 + noise.nextInt(20)) }, w), 0f)
        assertEquals(0f, edgeDensity(IntArray(3) { white }, 2), 0f) // too small to measure
    }

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun countingEdgesIsCheap() {
        val thumb = IntArray(256 * 192) { i -> grey((i * 7919) % 256) } // the indexer's size, every pixel a step
        repeat(20) { edgeDensity(thumb, 256) } // warm up the JIT
        val started = System.nanoTime()
        repeat(100) { edgeDensity(thumb, 256) }
        val ms = (System.nanoTime() - started) / 1_000_000.0 / 100
        assertTrue("took $ms ms", ms < 5) // next to labelling (tens of ms), nothing
    }

    @Test fun aQuickReadOnlyCountsRealText() {
        assertEquals(true, looksLikeText("TAX INVOICE 2024"))
        assertEquals(true, looksLikeText("Welcome to Goa"))
        assertEquals(false, looksLikeText("ll"))
        assertEquals(false, looksLikeText("1111 1111")) // digits from a texture, no word
        assertEquals(false, looksLikeText(null))
    }

    @Test fun categoriesKnowTheirLabels() {
        assertTrue(labelsOf(Category.Pets).containsAll(listOf("Dog", "Cat")))
        assertTrue(labelsOf(Category.Other).isEmpty())
    }

    @Test fun screenshotsByFolderOrName() {
        assertEquals(true, isScreenshot("DCIM/Screenshots/", "IMG_1.png"))
        assertEquals(true, isScreenshot("Pictures/", "Screenshot_20260101-101010.png"))
        assertEquals(false, isScreenshot("DCIM/Camera/", "IMG_20260101.jpg"))
    }
}
