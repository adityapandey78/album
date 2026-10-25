package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic faces: each person is a random direction in 128-d space; each photo of them adds noise of 0.8 its length,
 * so two photos of one person score about 0.6 and two people about 0, roughly like SFace on real faces.
 */
class ClusteringTest {
    private val random = Random(7)
    private val people = List(4) { randomUnit() }

    private fun randomUnit() = unit(FloatArray(128) { random.nextFloat() * 2 - 1 })

    private fun photoOf(person: Int): FloatArray {
        val noise = randomUnit()
        return unit(FloatArray(128) { people[person][it] + 0.8f * noise[it] })
    }

    private val box = FaceBox(0.4f, 0.3f, 0.6f, 0.6f)

    /** Face [id] of [person], alone in photo [id] unless [mediaId] says otherwise. */
    private fun face(id: Long, person: Int, cluster: Long = 0, mediaId: Long = id, b: FaceBox = box) =
        FaceVector(id, mediaId, b, photoOf(person), cluster)

    private val none = Corrections(emptyMap(), emptyList(), emptyMap())

    /** Faces with the clustering applied, as cluster id per face id. */
    private fun apply(faces: List<FaceVector>, result: Clustering) = faces.associate { it.id to (result.changed[it.id] ?: it.cluster) }

    private fun groupsOf(clusters: Map<Long, Long>) = clusters.entries.groupBy({ it.value }, { it.key }).values.map { it.toSet() }.toSet()

    @Test fun samePersonGroupsTogetherAndStrangersStayApart() {
        val faces = (1L..30L).map { face(it, person = (it % 3).toInt()) } // three people, photos interleaved
        val clusters = apply(faces, cluster(faces, none))
        val expected = faces.groupBy({ it.id % 3 }, { it.id }).values.map { it.toSet() }.toSet()
        assertEquals(expected, groupsOf(clusters))
        assertTrue(clusters.values.all { it >= AUTO_CLUSTERS }) // nobody named yet: all automatic
    }

    @Test fun sameInputSameResultWhateverTheListOrder() {
        val faces = (1L..40L).map { face(it, person = (it % 4).toInt()) }
        val a = cluster(faces, none)
        val b = cluster(faces.shuffled(Random(1)), none)
        assertEquals(a.changed, b.changed)
    }

    @Test fun newFacesJoinExistingGroupsWithoutReshufflingThem() {
        val first = (1L..20L).map { face(it, person = (it % 2).toInt()) }
        val clustered = apply(first, cluster(first, none))
        val existing = first.map { FaceVector(it.id, it.mediaId, it.box, it.embedding, clustered.getValue(it.id)) }
        val new = (21L..25L).map { face(it, person = 0) }
        val result = cluster(existing + new, none)
        assertEquals(new.map { it.id }.toSet(), result.changed.keys) // nothing already grouped moved
        assertEquals(setOf(clustered.getValue(2)), new.map { result.changed.getValue(it.id) }.toSet()) // face 2 is person 0
    }

    @Test fun confirmedFacesGoToTheirPersonWhateverTheyLookLike() {
        val faces = (1L..10L).map { face(it, person = 0) } + face(11, person = 1)
        val corrections = Corrections(mapOf(5L to null), listOf(Confirmed(5, mediaId = 11, box, order = 1)), emptyMap())
        val clusters = apply(faces, cluster(faces, corrections))
        assertEquals(5L, clusters[11]) // the user said face 11 is person 5
        assertTrue((1L..10L).none { clusters[it] == 5L })
    }

    @Test fun latestConfirmationWinsWhenTwoPeopleClaimOneFace() {
        val faces = listOf(face(1, person = 0))
        val confirmed = listOf(Confirmed(3, 1, box, order = 1), Confirmed(4, 1, box, order = 2))
        val clusters = apply(faces, cluster(faces, Corrections(mapOf(3L to null, 4L to null), confirmed, emptyMap())))
        assertEquals(4L, clusters[1])
    }

    @Test fun notThisPersonTakesThePhotoOutForGood() {
        // Person 9 is person 0's faces, grouped automatically; photo 3 is then marked "Not" them
        val faces = (1L..6L).map { face(it, person = 0, cluster = 9) }
        val corrections = Corrections(mapOf(9L to null), emptyList(), mapOf(3L to setOf(9L)))
        val clusters = apply(faces, cluster(faces, corrections))
        assertNotEquals(9L, clusters[3]) // even though it looks just like them
        assertTrue(clusters.filterKeys { it != 3L }.values.all { it == 9L })
    }

    @Test fun facesOfAPersonThatIsGoneAreRegrouped() {
        val faces = (1L..4L).map { face(it, person = 0, cluster = 7) } // person 7 was merged away
        val clusters = apply(faces, cluster(faces, none))
        assertTrue(clusters.values.toSet().single() >= AUTO_CLUSTERS)
    }

    @Test fun afterARebuildNamedPeopleComeBackByPhotoAndBox() {
        // The index was wiped: same photos, new face ids, boxes a little off (a newer detector), nothing clustered
        val shifted = FaceBox(0.42f, 0.31f, 0.61f, 0.62f)
        val faces = (1L..5L).map { face(100 + it, person = 1, mediaId = it, b = shifted) } + (6L..8L).map { face(100 + it, person = 1, mediaId = it) }
        val confirmed = (1L..5L).map { Confirmed(2, mediaId = it, box, order = it) }
        val clusters = apply(faces, cluster(faces, Corrections(mapOf(2L to null), confirmed, emptyMap())))
        assertTrue(clusters.values.all { it == 2L }) // confirmed ones by box, the rest by looking like them
    }

    private fun centroidOf(person: Int, faces: Int) = Centroid(unit(FloatArray(128) { i -> List(faces) { photoOf(person) }.let { p -> p.sumOf { it[i].toDouble() }.toFloat() } }), faces)

    @Test fun savedCentroidFindsNewPhotosBeforeTheOldOnesAreScannedAgain() {
        val faces = (1L..3L).map { face(it, person = 3) } + face(4, person = 2)
        val clusters = apply(faces, cluster(faces, Corrections(mapOf(8L to centroidOf(3, faces = 10)), emptyList(), emptyMap())))
        assertEquals(listOf(8L, 8L, 8L), (1L..3L).map { clusters[it] })
        assertNotEquals(8L, clusters[4])
    }

    @Test fun aFewNewFacesDontOutweighASavedCentroid() {
        val saved = centroidOf(3, faces = 50)
        val result = cluster(listOf(face(1, person = 3)), Corrections(mapOf(8L to saved), emptyList(), emptyMap()))
        val live = result.centroids.getValue(8)
        assertEquals(1, live.faces) // so the caller keeps the saved one (made from 50) instead
        assertTrue(dot(live.vector, saved.vector) > 0.99f) // and the seed's weight keeps the group where it was
    }

    @Test fun aNewFaceMakesItsGroupLookAgainAndSplitGroupsMerge() {
        val a = (1L..6L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 1) }
        val b = (7L..9L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 2) }
        assertTrue(cluster(a + b, none).changed.isEmpty()) // nothing changed: groups aren't compared again
        val all = a + b + face(10, person = 0)
        val result = cluster(all, none)
        assertEquals(setOf(AUTO_CLUSTERS + 1), apply(all, result).values.toSet()) // the bigger group keeps its id
        assertEquals(mapOf(AUTO_CLUSTERS + 2 to AUTO_CLUSTERS + 1), result.merged) // so pages and edits can follow it
    }

    @Test fun mergeFoldsAutomaticGroupsIntoPeopleButNeverAcrossANot() {
        val named = (1L..5L).map { face(it, person = 0, cluster = 4) }
        val group = (6L..8L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 1) } + face(9, person = 0, cluster = AUTO_CLUSTERS + 2)
        val newFace = face(10, person = 0, cluster = 0)
        val people = mapOf<Long, Centroid?>(4L to null)
        val all = named + group + newFace
        assertEquals(setOf(4L), apply(all, cluster(all, Corrections(people, emptyList(), emptyMap()))).values.toSet())
        val not = Corrections(people, emptyList(), mapOf(7L to setOf(4L))) // photo 7 is "Not" person 4
        val clusters = apply(all, cluster(all, not))
        assertTrue((6L..8L).none { clusters[it] == 4L })
    }

    @Test fun peopleNeverMergeWithEachOtherByThemselves() {
        val one = (1L..4L).map { face(it, person = 0, cluster = 1) }
        val two = (5L..8L).map { face(it, person = 0, cluster = 2) } + face(9, person = 0) // same face, but the user keeps them apart
        val result = cluster(one + two, Corrections(mapOf(1L to null, 2L to null), emptyList(), emptyMap()))
        assertEquals(setOf(9L), result.changed.keys)
        assertTrue(result.merged.isEmpty())
        assertEquals(setOf(1L, 2L), result.centroids.keys)
    }

    @Test fun automaticIdsAreNeverHandedOutTwice() {
        val first = cluster(listOf(face(1, person = 0)), none, nextAuto = AUTO_CLUSTERS + 40)
        assertEquals(AUTO_CLUSTERS + 40, first.changed[1])
        assertEquals(AUTO_CLUSTERS + 41, first.nextAuto)
        // Even with every group gone (merged away, photos deleted), the next one gets a fresh id
        assertEquals(AUTO_CLUSTERS + 41, cluster(listOf(face(2, person = 1)), none, nextAuto = first.nextAuto).changed[2])
    }

    private fun assertBox(expected: FaceBox, actual: FaceBox) = assertArrayEquals(
        floatArrayOf(expected.left, expected.top, expected.right, expected.bottom),
        floatArrayOf(actual.left, actual.top, actual.right, actual.bottom),
        1e-6f,
    )

    @Test fun turningAPhotoTurnsItsBoxes() {
        val b = FaceBox(0.1f, 0.2f, 0.4f, 0.5f) // near the top left
        assertBox(FaceBox(0.5f, 0.1f, 0.8f, 0.4f), b.rotated(clockwise = true)) // now near the top right
        assertBox(FaceBox(0.2f, 0.6f, 0.5f, 0.9f), b.rotated(clockwise = false)) // now near the bottom left
        assertBox(b, b.rotated(true).rotated(true).rotated(true).rotated(true))
        assertBox(b, b.rotated(false).rotated(false).rotated(false).rotated(false))
        assertBox(b, b.rotated(true).rotated(false))
    }

    @Test fun boxOverlap() {
        assertEquals(1f, iou(box, box), 1e-6f)
        assertEquals(0f, iou(box, FaceBox(0.7f, 0.7f, 0.9f, 0.9f)), 0f)
        assertEquals(1f / 3, iou(FaceBox(0f, 0f, 2f, 1f), FaceBox(1f, 0f, 3f, 1f)), 1e-6f)
    }
}
