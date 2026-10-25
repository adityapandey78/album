package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class EmbeddingsTest {
    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun random(rng: Random, dim: Int = 512) = unit(FloatArray(dim) { rng.nextFloat() * 2 - 1 })

    private fun library(vectors: List<FloatArray>): VectorIndex {
        val dim = vectors.first().size
        val values = ByteArray(vectors.size * dim)
        val scales = FloatArray(vectors.size)
        vectors.forEachIndexed { i, v ->
            val (bytes, scale) = quantize(v)
            bytes.copyInto(values, i * dim)
            scales[i] = scale
        }
        return VectorIndex(LongArray(vectors.size) { 100L + it }, values, scales, dim)
    }

    @Test fun quantizedCosinesStayClose() {
        val rng = Random(1)
        repeat(50) {
            val a = random(rng)
            val b = unit(FloatArray(512) { a[it] + (rng.nextFloat() - 0.5f) }) // somewhat similar
            val exact = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
            val score = library(listOf(a)).rank(b, 1, floor = -1f, window = 2f).single().second
            assertEquals(exact, score, 0.005f)
        }
    }

    @Test fun quantizeKeepsTheLargestComponentExact() {
        val (bytes, scale) = quantize(floatArrayOf(0.5f, -0.2f, 0f))
        assertEquals(127, bytes[0].toInt())
        assertEquals(-51, bytes[1].toInt()) // -50.8
        assertEquals(0, bytes[2].toInt())
        assertEquals(0.5f / 127, scale, 1e-9f)
        assertEquals(0f, quantize(FloatArray(4)).second)
    }

    @Test fun ranksBestFirstAboveTheFloorAndNearTheBest() {
        // Photos at known cosines to the query: 0.40, 0.36, 0.30, 0.25, 0.10
        val dim = 64
        val query = unit(FloatArray(dim) { if (it == 0) 1f else 0f })
        val photos = listOf(0.30f, 0.40f, 0.10f, 0.36f, 0.25f).map { c ->
            unit(FloatArray(dim) { when (it) { 0 -> c; 1 -> sqrt(1 - c * c); else -> 0f } })
        }
        val all = library(photos).rank(query, 10, floor = 0f, window = 1f)
        assertEquals(listOf(101L, 103L, 100L, 104L, 102L), all.map { it.first })
        assertTrue(all.zipWithNext().all { (a, b) -> a.second >= b.second })
        // Floor 0.23, within 0.06 of the best (0.40): only 0.40 and 0.36 are close enough
        assertEquals(listOf(101L, 103L), library(photos).rank(query, 10, floor = 0.23f, window = 0.06f).map { it.first })
        // Nothing reaches the floor: nothing at all, rather than the least bad
        assertEquals(emptyList<Pair<Long, Float>>(), library(photos).rank(query, 10, floor = 0.5f, window = 1f))
        assertEquals(listOf(101L), library(photos).rank(query, 1, floor = 0f, window = 1f).map { it.first })
    }

    @Test fun emptyLibraryFindsNothing() {
        assertEquals(0, VectorIndex(LongArray(0), ByteArray(0), FloatArray(0)).rank(FloatArray(512) { 0.04f }, 500).size)
    }
}
