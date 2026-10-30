package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic pet photos: each animal is a random direction in 128-d space; each photo of it adds noise of 0.25 its length,
 * so two photos of one animal score about 0.94 and two animals about 0. Real describe-it vectors are far less tidy (see
 * PETS): these check the rules, not the bars.
 */
class PetsTest {
    private val random = Random(11)
    private val animals = List(3) { randomUnit() }

    private fun randomUnit() = unit(FloatArray(128) { random.nextFloat() * 2 - 1 })

    private fun photoOf(animal: Int, noise: Float = 0.25f) = randomUnit().let { n -> unit(FloatArray(128) { animals[animal][it] + noise * n[it] }) }

    private fun photo(id: Long, species: Species = Species.Dog, weak: Boolean = false) = PetPhoto(id, species, 0.9f, weak)

    private val none = Corrections(emptySet(), emptyList(), emptyMap())

    private fun groupsOf(rows: List<PetRow>) = rows.filter { it.cluster > 0 }.groupBy({ it.cluster }, { it.mediaId }).values.map { it.toSet() }.toSet()

    @Test fun petPhotosAreSureDogsAndCatsThatAreNotVideosOrScreenshots() {
        val scores = mapOf(
            "Dog" to mapOf(1L to 0.9f, 3L to 0.95f, 4L to 0.4f, 6L to 0.9f),
            "Cat" to mapOf(2L to 0.6f, 5L to 0.75f),
            "Dalmatian" to mapOf(5L to 0.8f),
            "Bird" to mapOf(7L to 0.99f),
        )
        val camera = mapOf(1L to true, 2L to true, 3L to false, 4L to true, 5L to true, 7L to true) // 6: a video, a screenshot or gone
        assertEquals(
            listOf(
                PetPhoto(1, Species.Dog, 0.9f, weak = false),
                PetPhoto(2, Species.Cat, 0.6f, weak = true), // ML Kit not sure enough for it to start a pet
                PetPhoto(3, Species.Dog, 0.95f, weak = true), // a forward: most likely someone else's dog
                PetPhoto(5, Species.Dog, 0.8f, weak = false), // the breed beats the weaker cat label
            ),
            petPhotos(scores) { camera[it] },
        )
    }

    @Test fun eachPetGroupsTogetherAndADogMetOnceStaysOut() {
        val photos = (1L..17L).map { photo(it) }
        val vectors = (1L..16L).associateWith { photoOf((it % 2).toInt()) } + (17L to photoOf(2))
        val result = groupPets(photos, vectors, emptyMap(), emptyMap(), none)
        assertEquals(setOf((2L..16L step 2).toSet(), (1L..15L step 2).toSet()), groupsOf(result.rows))
        assertTrue(result.rows.filter { it.cluster > 0 }.all { it.cluster >= PET_GROUPS }) // in the pets' band, not faces'
        assertEquals(UNSURE, result.rows.single { it.mediaId == 17L }.cluster)
    }

    @Test fun aDogNeverJoinsACatHoweverAlikeTheirPhotos() {
        // One sofa, one light: photos as alike as one animal's
        val photos = (1L..6L).map { photo(it, Species.Dog) } + (7L..12L).map { photo(it, Species.Cat) }
        val result = groupPets(photos, (1L..12L).associateWith { photoOf(0) }, emptyMap(), emptyMap(), none)
        assertEquals(setOf((1L..6L).toSet(), (7L..12L).toSet()), groupsOf(result.rows))
        assertEquals(setOf(Species.Dog), result.rows.filter { it.mediaId <= 6 }.map { it.species }.toSet())
    }

    @Test fun weakPhotosJoinAPetButNeverStartOne() {
        val forwards = (1L..8L).map { photo(it, weak = true) }
        val vectors = (1L..8L).associateWith { photoOf(0) } + (11L..16L).associateWith { photoOf(0) }
        assertTrue(groupPets(forwards, vectors, emptyMap(), emptyMap(), none).rows.none { it.cluster > 0 })
        val own = (11L..16L).map { photo(it) }
        assertEquals(setOf((1L..8L).toSet() + (11L..16L).toSet()), groupsOf(groupPets(forwards + own, vectors, emptyMap(), emptyMap(), none).rows))
    }

    @Test fun aPhotoWithoutAVectorYetKeepsItsPlace() {
        // Describe-it search still reading the photos, or removed since
        val old = mapOf(1L to PetRow(1, Species.Dog, PET_GROUPS + 3, 0.9f, 0.8f))
        val rows = groupPets(listOf(photo(1), photo(2)), emptyMap(), old, emptyMap(), none).rows.associateBy { it.mediaId }
        assertEquals(PET_GROUPS + 3, rows.getValue(1).cluster)
        assertEquals(0L, rows.getValue(2).cluster)
    }

    @Test fun theUsersWordWins() {
        val bruno = 7L
        val mili = 8L
        val photos = (1L..6L).map { photo(it) } + photo(9) // ML Kit says 9 is a dog
        val vectors = (1L..9L).associateWith { photoOf(0) }
        val old = mapOf(3L to PetRow(3, Species.Dog, bruno, 0.9f, 0.9f))
        val corrections = Corrections(
            setOf(bruno, mili),
            listOf(1L, 2L, 4L, 5L, 6L).map { Confirmed(bruno, it, WHOLE, it) } + Confirmed(mili, 9, WHOLE, 10), // 9 put in their cat
            mapOf(3L to setOf(bruno)), // Not Bruno
        )
        val rows = groupPets(photos, vectors, old, mapOf(bruno to Species.Dog, mili to Species.Cat), corrections).rows.associateBy { it.mediaId }
        assertEquals(setOf(bruno), listOf(1L, 2L, 4L, 5L, 6L).map { rows.getValue(it).cluster }.toSet())
        assertNotEquals(bruno, rows.getValue(3).cluster) // never back in, however alike
        assertEquals(Species.Cat to mili, rows.getValue(9).let { it.species to it.cluster })
    }

    @Test fun aSecondRunMovesNothing() {
        val photos = (1L..12L).map { photo(it) } + photo(13)
        val vectors = (1L..12L).associateWith { photoOf((it % 2).toInt()) } + (13L to photoOf(2))
        val first = groupPets(photos, vectors, emptyMap(), emptyMap(), none)
        val second = groupPets(photos, vectors, first.rows.associateBy { it.mediaId }, emptyMap(), none, first.nextAuto)
        assertEquals(first.rows, second.rows)
        assertEquals(first.nextAuto, second.nextAuto)
    }

    @Test fun petBarsAreStricterThanFaces() {
        // About 0.6 alike: one person's faces, but photos that merely look alike
        val faces = (1L..10L).map { FaceVector(it, it, WHOLE, photoOf(0, noise = 0.8f), 0) }
        assertTrue(cluster(faces, none).changed.values.all { it.cluster >= AUTO_CLUSTERS })
        assertTrue(cluster(faces, none, bars = PETS).changed.values.all { it.cluster == UNSURE })
    }

    @Test fun anUnnamedPetShowsOnlyOncePhotographedOnSeveralDays() {
        assertFalse(isShown(null, 40, petDays = PET_DAYS - 1)) // a friend's dog, one afternoon
        assertTrue(isShown(null, PETS.min, petDays = PET_DAYS))
        assertFalse(isShown(null, PETS.min - 1, petDays = 30))
        assertTrue(isShown("Bruno", 1, petDays = 1))
    }

    @Test fun petPhotosAreWholePhotoFacesWithIdsNoFaceHas() {
        val tile = PetRow(42, Species.Cat, PET_GROUPS, 0.5f, 0.9f).tile()
        assertTrue(tile.id < 0)
        assertEquals(42L to WHOLE, tile.mediaId to tile.box)
        assertEquals(WHOLE, WHOLE.rotated(clockwise = true)) // turning a photo leaves a pet's confirmed box as it is
        assertEquals(PET_GROUPS + 5, clusterOfAlbum(personAlbumId(PET_GROUPS + 5))) // a pet page opens like a person's
    }

    @Test fun peopleSaysWhoIsAPet() {
        fun album(cluster: Long, name: String?, species: Species? = null) = PersonAlbum(cluster, name, false, FaceCrop(-cluster, cluster, WHOLE), emptyList(), 5, species = species)
        val asha = album(1, "Asha")
        val bruno = album(2, "Bruno", Species.Dog)
        val dog = album(PET_GROUPS, null, Species.Dog)
        val cat = album(PET_GROUPS + 1, null, Species.Cat)
        assertEquals(listOf("Asha", "Unnamed person", "Bruno, dog", "Unnamed cat"), listOf(asha, album(3, null), bruno, cat).map(::spokenName))
        assertEquals("1 person · 3 pets · 2 hidden", peopleSubtitle(listOf(asha, bruno, dog, cat), hidden = 2))
        assertEquals("1 pet", peopleSubtitle(listOf(bruno), hidden = 0))
        assertEquals("0 people", peopleSubtitle(emptyList(), hidden = 0))
        assertEquals("Same pet?", reviewTitle(listOf(PeoplePair(bruno, dog))))
        assertEquals("Same person?", reviewTitle(listOf(PeoplePair(bruno, dog), PeoplePair(asha, album(4, null)))))
    }
}
