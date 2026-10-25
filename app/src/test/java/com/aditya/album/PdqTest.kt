package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The port against Meta's reference: bridge-1-original.jpg and its hashes come from github.com/facebook/ThreatExchange
 * (pdq/data/reg-test-input/dih and pdq/java/reg_test/expected/out, BSD licence; see resources/cleanup/SOURCES.md).
 * The reference reads the JPEG with javax.imageio, as this test does, so the hashes must match bit for bit.
 */
class PdqTest {
    private fun image(name: String): BufferedImage = ImageIO.read(javaClass.getResourceAsStream("/cleanup/$name"))

    private fun luma(img: BufferedImage) = FloatArray(img.width * img.height) { pdqLuma(img.getRGB(it % img.width, it / img.width)) }

    @Test fun matchesTheReferenceBitForBit() {
        val img = image("bridge-1-original.jpg")
        val pdq = PdqHasher().hash(luma(img), img.width, img.height)
        // pdq-photo-hasher-tool --pdqdih: original, rot90, rot180, rot270, flipx, flipy, flipp, flipm
        val expected = listOf(
            "f8f8f0cee0f4a84f06370a22038f63f0b36e2ed596621e1d33e6b39c4e9c9b22",
            "30a10efd71c83f429013d48d0ffffc52e34e0e17ada952a9d29685211ea9e5af",
            "adad5a64b5a102e55b62a08856dacd5ae63b847fc337b4b766b319361bc93188",
            "a5f0b457a49995e8c1065c275aaa54d8b61ba49df8fcfc0383c32f8b0bfc4f05",
            "f8f80f31e0f457b00637f5d5028f980fb36ed12a9622e1e233e64c634e9c64dd",
            "0dad2599b1a1bd1a5362576752da32a5e63b7380c2374b4866b346c91bc9ce77",
            "f0a5e102f1ccc0bd945308720fff038de34ef1e8ada9a956d2967ade5ea91a50",
            "a5f04ba8a4996a17c906a3d85aaaa927b61b5b42f8fc03fc87c3d0740bfcb0fa",
        )
        assertEquals(expected, Dihedral.entries.map { pdqHex(pdq.hashes, it.ordinal * PDQ_LONGS) })
        assertEquals(100, pdq.quality)
    }

    @Test fun hexRoundTripsAndDistanceCountsBits() {
        val original = "f8f8f0cee0f4a84f06370a22038f63f0b36e2ed596621e1d33e6b39c4e9c9b22"
        assertEquals(original, pdqHex(pdqOf(original)))
        // The reference's delta between bridge-1-original.jpg and the file rotated 90 degrees, hashed as it is
        assertEquals(120, pdqDistance(pdqOf(original), pdqOf("30a10efd71cc3d429013d48d0ffffc52e34e0e17ada952a9d29685211ea9e5af")))
        // Bit 0 is the last hex digit's lowest bit, bit 255 the first digit's highest
        val bits = LongArray(4).also { it[0] = 1L; it[3] = Long.MIN_VALUE }
        assertEquals("8" + "0".repeat(62) + "1", pdqHex(bits))
    }

    @Test fun aTurnedPhotoMatchesItsTurnedHash() {
        val img = image("bridge-1-original.jpg")
        val w = img.width
        val h = img.height
        val hasher = PdqHasher()
        val original = hasher.hash(luma(img), w, h)
        // Turned a quarter clockwise: the new row y, column x is the old row h - 1 - x, column y
        val turned = FloatArray(w * h) { i -> pdqLuma(img.getRGB(i / h, h - 1 - i % h)) }
        val direct = hasher.hash(turned, h, w).original
        val near = Dihedral.entries.minBy { pdqDistance(direct, 0, original.hashes, it.ordinal * PDQ_LONGS) }
        assertTrue(pdqDistance(direct, 0, original.hashes, near.ordinal * PDQ_LONGS) <= 10)
        assertTrue(pdqDistance(direct, original.original) > 100) // without the turned hashes, no match at all
    }

    @Test fun flatPicturesHaveLowQuality() {
        val flat = FloatArray(200 * 150) { 128f }
        assertEquals(0, PdqHasher().hash(flat, 200, 150).quality)
    }
}
