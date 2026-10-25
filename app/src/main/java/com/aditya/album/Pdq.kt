package com.aditya.album

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

// PDQ, Meta's perceptual photo hash, ported from its Java reference implementation (github.com/facebook/ThreatExchange,
// pdq/java: PDQHasher, Hash256 and MatrixUtil). Copyright (c) Meta Platforms, Inc. and affiliates; BSD licence, see
// licenses/pdq-BSD.txt. Torben's median is public domain (Torben Mogensen, N. Devillard). The arithmetic is kept step for
// step, floats where the reference uses floats, so the same pixels give the reference's hashes bit for bit (PdqTest).
//
// How it works (Meta's hashing.pdf): luminance, a tent-filter blur, 64 by 64 samples, a DCT, and one bit per coefficient
// of the lowest 16 by 16 frequencies (leaving out the plain average): above or below their median. Resizing,
// recompression and small edits move few of the 256 bits; a different photo differs in about half of them.

/** PDQ's 256 bits as 4 longs: bit k of the reference hash is bit k % 64 of long k / 64. Bigger arrays hold hashes back to back. */
const val PDQ_LONGS = 4

/** The eight ways to turn or mirror a photo, in the reference's order. [Pdq.hashes] holds one hash for each. */
enum class Dihedral { Original, Rotate90, Rotate180, Rotate270, FlipX, FlipY, FlipPlus1, FlipMinus1 }

/**
 * A photo's PDQ: [hashes] is the hash of each [Dihedral] turn of the photo, [PDQ_LONGS] longs each, original first.
 * [quality] (0 to 100) says how much there is to go on: below 50 a photo is too plain or dark to trust a match.
 */
class Pdq(val hashes: LongArray, val quality: Int) {
    val original: LongArray get() = hashes.copyOf(PDQ_LONGS)
}

/** Bits that differ between hash [a] at long [aAt] and hash [b] at long [bAt]: 0 for the same picture, about 128 for unrelated ones. */
fun pdqDistance(a: LongArray, aAt: Int, b: LongArray, bAt: Int): Int =
    java.lang.Long.bitCount(a[aAt] xor b[bAt]) + java.lang.Long.bitCount(a[aAt + 1] xor b[bAt + 1]) +
        java.lang.Long.bitCount(a[aAt + 2] xor b[bAt + 2]) + java.lang.Long.bitCount(a[aAt + 3] xor b[bAt + 3])

fun pdqDistance(a: LongArray, b: LongArray) = pdqDistance(a, 0, b, 0)

/** 64 hex digits, most significant first, as the reference tools print them. */
fun pdqHex(hash: LongArray, at: Int = 0) = "%016x%016x%016x%016x".format(hash[at + 3], hash[at + 2], hash[at + 1], hash[at])

/** The hash [pdqHex] wrote. */
fun pdqOf(hex: String): LongArray {
    require(hex.length == 64) { "PDQ hex is 64 digits" }
    return LongArray(PDQ_LONGS) { java.lang.Long.parseUnsignedLong(hex.substring(48 - it * 16, 64 - it * 16), 16) }
}

/** Brightness of an ARGB pixel the way the reference reads it, in floats so the hash matches it exactly. */
fun pdqLuma(argb: Int): Float = 0.299f * (argb shr 16 and 0xff) + 0.587f * (argb shr 8 and 0xff) + 0.114f * (argb and 0xff)

/**
 * Works out [Pdq]s. Holds its buffers between photos, so keep one per thread: not thread-safe.
 * [hash] takes the photo as brightness ([pdqLuma]), [width] by [height], row by row, and leaves [luma] as it was.
 */
class PdqHasher {
    // DCT basis for frequencies 1 to 16 over 64 samples (Zauner 2010): frequency 0 is the plain average, which says nothing
    private val dct = FloatArray(16 * 64).also { d ->
        val scale = sqrt(2.0 / 64.0).toFloat()
        for (i in 0 until 16) for (j in 0 until 64) d[i * 64 + j] = (scale * cos((Math.PI / 2 / 64.0) * (i + 1) * (2 * j + 1))).toFloat()
    }
    private var buffer1 = FloatArray(0)
    private var buffer2 = FloatArray(0)
    private val samples = FloatArray(64 * 64)
    private val half = FloatArray(16 * 64)
    private val freq = FloatArray(16 * 16)
    private val turned = FloatArray(16 * 16)

    fun hash(luma: FloatArray, width: Int, height: Int): Pdq {
        require(width > 0 && height > 0 && luma.size >= width * height)
        val n = width * height
        if (buffer1.size < n) {
            buffer1 = FloatArray(n)
            buffer2 = FloatArray(n)
        }
        luma.copyInto(buffer1, endIndex = n)
        // Two passes of box blurs along rows then columns make a tent filter (Jarosz, 'Fast Image Convolutions', 2001), each
        // window 1/128 of the side, so every one of the 64 x 64 samples averages its own patch of the photo instead of aliasing
        val rowWindow = jaroszWindow(width)
        val colWindow = jaroszWindow(height)
        repeat(2) {
            for (i in 0 until height) box1D(buffer1, i * width, buffer2, i * width, width, 1, rowWindow)
            for (j in 0 until width) box1D(buffer2, j, buffer1, j, height, width, colWindow)
        }
        // Samples at the centres of 64 x 64 cells, not their corners
        for (i in 0 until 64) {
            val y = (((i + 0.5) * height) / 64).toInt()
            for (j in 0 until 64) samples[i * 64 + j] = buffer1[y * width + (((j + 0.5) * width) / 64).toInt()]
        }
        val quality = quality(samples)
        // freq = D * samples * D', only the 16 x 16 corner that the hash uses
        for (i in 0 until 16) for (j in 0 until 64) {
            var sum = 0f
            for (k in 0 until 64) sum += dct[i * 64 + k] * samples[k * 64 + j]
            half[i * 64 + j] = sum
        }
        for (i in 0 until 16) for (j in 0 until 16) {
            var sum = 0f
            for (k in 0 until 64) sum += half[i * 64 + k] * dct[j * 64 + k]
            freq[i * 16 + j] = sum
        }
        // A turned or mirrored photo's frequencies are these with some signs flipped and rows and columns swapped, so all
        // eight hashes come almost free. Each is thresholded at its own median, as the reference does.
        val hashes = LongArray(8 * PDQ_LONGS)
        for (variant in Dihedral.entries) {
            for (i in 0 until 16) for (j in 0 until 16) {
                val v = freq[i * 16 + j]
                val odd = when (variant) { // the reference's sign patterns: odd frequencies flip sign under a mirror
                    Dihedral.Original, Dihedral.FlipPlus1 -> false
                    Dihedral.Rotate90, Dihedral.FlipY -> (j and 1) == 0
                    Dihedral.Rotate270, Dihedral.FlipX -> (i and 1) == 0
                    Dihedral.Rotate180, Dihedral.FlipMinus1 -> ((i + j) and 1) != 0
                }
                val transpose = variant == Dihedral.Rotate90 || variant == Dihedral.Rotate270 || variant == Dihedral.FlipPlus1 || variant == Dihedral.FlipMinus1
                turned[if (transpose) j * 16 + i else i * 16 + j] = if (odd) -v else v
            }
            val median = torben(turned)
            val at = variant.ordinal * PDQ_LONGS
            for (k in 0 until 256) if (turned[k] > median) hashes[at + k / 64] = hashes[at + k / 64] or (1L shl (k % 64))
        }
        return Pdq(hashes, quality)
    }

    // Counts clear brightness steps between neighbouring samples, ignoring small ones; flat, dark or empty pictures score low
    private fun quality(s: FloatArray): Int {
        var gradients = 0
        for (i in 0 until 63) for (j in 0 until 64) gradients += abs((((s[i * 64 + j] - s[(i + 1) * 64 + j]) * 100) / 255).toInt())
        for (i in 0 until 64) for (j in 0 until 63) gradients += abs((((s[i * 64 + j] - s[i * 64 + j + 1]) * 100) / 255).toInt())
        return (gradients / 90).coerceAtMost(100)
    }
}

// Rounded up: a 1024 px side blurs over 8 px per pass, and the second pass widens that to the 16 px each sample covers
private fun jaroszWindow(side: Int) = (side + 127) / 128

/**
 * Box average over [window] values along one row or column of [input] (from [inStart], every [stride]th value), written to
 * [output]. Near the ends the window shrinks rather than reading past them: grow, slide, then shrink.
 */
private fun box1D(input: FloatArray, inStart: Int, output: FloatArray, outStart: Int, length: Int, stride: Int, window: Int) {
    val halfWindow = (window + 2) / 2 // 7 -> 4, 8 -> 5
    var left = 0
    var right = 0
    var out = 0
    var sum = 0f
    var count = 0
    repeat(halfWindow - 1) { // fill the left half of the first window, no output yet
        sum += input[inStart + right]
        count++
        right += stride
    }
    repeat(window - halfWindow + 1) { // grow the window to full size, writing as it grows
        sum += input[inStart + right]
        count++
        output[outStart + out] = sum / count
        right += stride
        out += stride
    }
    repeat(length - window) { // slide the full window
        sum += input[inStart + right]
        sum -= input[inStart + left]
        output[outStart + out] = sum / count
        left += stride
        right += stride
        out += stride
    }
    repeat(halfWindow - 1) { // shrink it off the far end
        sum -= input[inStart + left]
        count--
        output[outStart + out] = sum / count
        left += stride
        out += stride
    }
}

/** Torben Mogensen's median: no sorting and no copy, a few passes over the 256 values. */
private fun torben(m: FloatArray): Float {
    val midn = (m.size + 1) / 2
    var min = m[0]
    var max = m[0]
    for (v in m) {
        if (v < min) min = v
        if (v > max) max = v
    }
    var less: Int
    var greater: Int
    var equal: Int
    var guess: Float
    var maxLessThanGuess: Float
    var minGreaterThanGuess: Float
    while (true) {
        guess = (min + max) / 2
        less = 0
        greater = 0
        equal = 0
        maxLessThanGuess = min
        minGreaterThanGuess = max
        for (v in m) {
            if (v < guess) {
                less++
                if (v > maxLessThanGuess) maxLessThanGuess = v
            } else if (v > guess) {
                greater++
                if (v < minGreaterThanGuess) minGreaterThanGuess = v
            } else {
                equal++
            }
        }
        if (less <= midn && greater <= midn) break
        if (less > greater) max = maxLessThanGuess else min = minGreaterThanGuess
    }
    return when {
        less >= midn -> maxLessThanGuess
        less + equal >= midn -> guess
        else -> minGreaterThanGuess
    }
}
