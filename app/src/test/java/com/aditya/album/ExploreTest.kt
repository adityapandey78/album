package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreTest {
    private val bengaluru = Place("Bengaluru", "Karnataka", "IN", "India")
    private val mumbai = Place("Mumbai", "Maharashtra", "IN", "India")
    private val paris = Place("Paris", "Île-de-France", "FR", "France")
    private val aurangabadMh = Place("Aurangabad", "Maharashtra", "IN", "India")
    private val aurangabadBr = Place("Aurangabad", "Bihar", "IN", "India")
    private val places = listOf(bengaluru, mumbai, paris, aurangabadMh, aurangabadBr)

    @Test fun thingIdsRoundTrip() {
        Category.entries.forEach { assertEquals(it, thingOf(thingIdOf(it))) }
    }

    @Test fun otherAlbumsAreNotThings() {
        val others = listOf(0L, -1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), Long.MIN_VALUE, Long.MAX_VALUE, albumIdOf(1)) +
            Special.entries.map { it.id } + places.map { placeIdOf(it) } + (thingIdOf(Category.entries.last()) + 1)
        others.forEach { assertNull(thingOf(it)) }
    }

    @Test fun placeIdsAreStableAndOnlyPlaceIds() {
        assertEquals(placeIdOf(bengaluru), placeIdOf(bengaluru.copy()))
        assertEquals(places.size, places.map { placeIdOf(it) }.toSet().size)
        places.forEach { assertTrue(isPlaceId(placeIdOf(it))) }
        val others = listOf(0L, -1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), Long.MIN_VALUE, Long.MAX_VALUE, albumIdOf(1)) +
            Special.entries.map { it.id } + Category.entries.map { thingIdOf(it) }
        others.forEach { assertFalse(isPlaceId(it)) }
    }

    @Test fun thingAndPlaceIdsNeverClashWithFoldersSpecialsOrCollections() {
        val ids = Category.entries.map { thingIdOf(it) } + places.map { placeIdOf(it) } +
            Place("", "", "", "").let(::placeIdOf) + Place("ÿÿÿÿ", "ÿÿÿÿ", "ÿÿ", "ÿÿÿÿ").let(::placeIdOf)
        assertTrue(ids.none { it in Int.MIN_VALUE..Int.MAX_VALUE }) // BUCKET_ID is a 32-bit hash
        assertTrue(ids.none { id -> Special.entries.any { it.id == id } })
        assertTrue(ids.none { collectionIdOf(it) != null })
    }

    @Test fun groupsFollowTheTimelineBiggestFirst() {
        val media = listOf(60L, 50L, 40L, 30L, 20L, 10L) // newest first, like Gallery.media
        val ids = mapOf("food" to setOf(10L, 50L), "pets" to setOf(20L, 40L, 60L), "sky" to setOf(99L), "city" to setOf(30L))
        val groups = groupsOf(ids, media) { it }
        assertEquals(listOf("pets", "food", "city"), groups.map { it.key }) // sky's only photo is gone, so sky is too
        assertEquals(listOf(60L, 40L, 20L), groups[0].items) // newest first: the cover is items.first()
        assertEquals(listOf(50L, 10L), groups[1].items)
    }

    @Test fun groupTiesKeepTheIndexOrder() {
        val groups = groupsOf(linkedMapOf("b" to setOf(1L), "a" to setOf(2L)), listOf(2L, 1L)) { it }
        assertEquals(listOf("b", "a"), groups.map { it.key })
    }

    @Test fun countriesWithMostPhotosComeFirst() {
        val groups = listOf(
            Group(paris, List(5) { it }),
            Group(bengaluru, List(4) { it }),
            Group(mumbai, List(3) { it }),
        )
        val countries = byCountry(groups)
        assertEquals(listOf("IN", "FR"), countries.map { it.first().key.countryCode }) // 7 photos beat 5
        assertEquals(listOf(bengaluru, mumbai), countries[0].map { it.key })
    }

    @Test fun sameNamedTownsGetTheirState() {
        val names = placeNames(places)
        assertEquals("Bengaluru", names[bengaluru])
        assertEquals("Aurangabad, Maharashtra", names[aurangabadMh])
        assertEquals("Aurangabad, Bihar", names[aurangabadBr])
        assertEquals("Aurangabad", placeNames(listOf(aurangabadBr))[aurangabadBr])
    }
}
