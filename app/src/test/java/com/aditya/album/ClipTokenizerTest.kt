package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ClipTokenizerTest {
    private val tokenizer = ClipTokenizer(File("src/main/assets/$MERGES_ASSET").readLines())

    // Meta's tokenizer (perception_models core/vision_encoder/tokenizer.py) on the same strings, as printed by
    // tools/describe_convert.py: start of text, the tokens, end of text. The rest of the 32 ids are zeros.
    private val expected = listOf(
        "dog" to intArrayOf(49406, 1929, 49407),
        "Dog on a BEACH" to intArrayOf(49406, 1929, 525, 320, 2117, 49407),
        "a photo of a cat" to intArrayOf(49406, 320, 1125, 539, 320, 2368, 49407),
        "two kids playing cricket" to intArrayOf(49406, 1237, 1911, 1629, 5373, 49407),
        "birthday cake with 25 candles" to intArrayOf(49406, 1166, 2972, 593, 273, 276, 15472, 49407),
        "Diwali lights at night!!!" to intArrayOf(49406, 18218, 3073, 536, 930, 995, 49407),
        "Mumbai, 2019" to intArrayOf(49406, 6971, 267, 273, 271, 272, 280, 49407),
        "it's my brother's wedding" to intArrayOf(49406, 585, 568, 607, 3157, 568, 3101, 49407),
        "rock'n'roll" to intArrayOf(49406, 2172, 262, 333, 262, 3341, 49407),
        "dog’s ball" to intArrayOf(49406, 1929, 568, 1069, 49407),
        "“quoted” words" to intArrayOf(49406, 257, 27706, 257, 2709, 49407),
        "café au lait" to intArrayOf(49406, 15304, 2566, 572, 585, 49407),
        "naïve résumé" to intArrayOf(49406, 1097, 35689, 563, 29106, 7054, 4166, 49407),
        "  lots   of\tspace  " to intArrayOf(49406, 2958, 539, 2138, 49407),
        "fish &amp; chips" to intArrayOf(49406, 2759, 261, 8855, 49407),
        "ﬁsh ﬂakes" to intArrayOf(49406, 2759, 20934, 49407),
        "ＦＵＬＬ ｗｉｄｔｈ" to intArrayOf(49406, 1476, 23571, 49407),
        "शादी की तस्वीरें" to intArrayOf(49406, 43181, 13343, 29552, 25751, 22067, 25751, 3124, 97, 28505, 19389, 39545, 25751, 19052, 48612, 30833, 49407),
        "東京タワー" to intArrayOf(49406, 48338, 21078, 105, 34941, 2429, 107, 30391, 49407),
        "🐶🏖️ beach dog" to intArrayOf(49406, 14466, 1109, 28877, 2117, 1929, 49407),
        "<end_of_text> sneaky" to intArrayOf(49406, 49407, 21293, 49407),
        "e-mail: a.b@c.com" to intArrayOf(49406, 324, 268, 2614, 281, 320, 269, 321, 287, 322, 269, 2464, 49407),
        // Cut at 32 ids, ending with end of text, as Meta's does
        "a very long description of a family gathering at grandmother's house with lots of food on the table and children " +
            "playing in the courtyard while the elders talk late into the night" to intArrayOf(
            49406, 320, 1070, 1538, 13951, 539, 320, 1315, 9197, 536, 17469, 568, 1212, 593, 2958, 539, 1559, 525, 518,
            2175, 537, 2153, 1629, 530, 518, 21900, 1519, 518, 28617, 1841, 2325, 49407,
        ),
    )

    @Test fun matchesMetasTokenizer() {
        for ((text, ids) in expected) {
            assertArrayEquals(text, ids + IntArray(TEXT_TOKENS - ids.size), tokenizer.encode(text))
        }
    }

    @Test fun cleansLikeMetas() {
        assertEquals("dog's ball", clean("  Dog’s  BALL\n"))
        assertEquals("fish & chips", clean("fish &amp;amp; chips")) // decoded twice, as Meta's does
        assertEquals("full width", clean("ＦＵＬＬ　ｗｉｄｔｈ"))
        assertEquals("café", clean("café")) // NFC
        assertEquals("ab", clean("a\u0000﻿b"))
    }

    @Test fun emptyTextIsJustStartAndEnd() {
        assertArrayEquals(intArrayOf(49406, 49407) + IntArray(TEXT_TOKENS - 2), tokenizer.encode("   "))
    }
}
