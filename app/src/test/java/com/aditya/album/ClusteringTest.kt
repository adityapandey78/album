package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic faces: each person is a random direction in 128-d space; each photo of them adds noise of 0.8 its length,
 * so two photos of one person score about 0.6 and two people about 0, roughly like EdgeFace on real faces.
 */
class ClusteringTest {
    private val random = Random(7)
    private val people = List(6) { randomUnit() }

    private fun randomUnit() = unit(FloatArray(128) { random.nextFloat() * 2 - 1 })

    private fun photoOf(person: Int): FloatArray {
        val noise = randomUnit()
        return unit(FloatArray(128) { people[person][it] + 0.8f * noise[it] })
    }

    private val box = FaceBox(0.4f, 0.3f, 0.6f, 0.6f)

    /** Face [id] of [person], alone in photo [id] unless [mediaId] says otherwise. */
    private fun face(id: Long, person: Int, cluster: Long = 0, mediaId: Long = id, b: FaceBox = box, weak: Boolean = false, fit: Float = 0f) =
        FaceVector(id, mediaId, b, photoOf(person), cluster, weak, fit)

    private fun FaceVector.with(cluster: Long, fit: Float = this.fit) = FaceVector(id, mediaId, box, embedding, cluster, weak, fit)

    private val none = Corrections(emptySet(), emptyList(), emptyMap())

    /** Faces with the clustering applied, as cluster id per face id. */
    private fun apply(faces: List<FaceVector>, result: Clustering) = faces.associate { it.id to (result.changed[it.id]?.cluster ?: it.cluster) }

    /** Faces as the next run sees them. */
    private fun next(faces: List<FaceVector>, result: Clustering) = faces.map { f -> result.changed[f.id]?.let { f.with(it.cluster, it.fit) } ?: f }

    private fun groupsOf(clusters: Map<Long, Long>) = clusters.entries.filter { it.value > 0 }.groupBy({ it.value }, { it.key }).values.map { it.toSet() }.toSet()

    @Test fun samePersonGroupsTogetherAndStrangersStayApart() {
        val faces = (1L..30L).map { face(it, person = (it % 3).toInt()) } + face(31, person = 5) // three people, and one stranger once
        val clusters = apply(faces, cluster(faces, none))
        val expected = faces.filter { it.id <= 30 }.groupBy({ it.id % 3 }, { it.id }).values.map { it.toSet() }.toSet()
        assertEquals(expected, groupsOf(clusters))
        assertTrue(clusters.filterKeys { it <= 30 }.values.all { it >= AUTO_CLUSTERS }) // nobody named yet: all automatic
        assertEquals(UNSURE, clusters[31]) // one face is no one yet: left out, not a group of one
    }

    @Test fun sameInputSameResultWhateverTheListOrder() {
        val faces = (1L..40L).map { face(it, person = (it % 4).toInt()) }
        val a = cluster(faces, none)
        val b = cluster(faces.shuffled(Random(1)), none)
        assertEquals(a.changed, b.changed)
        assertEquals(a.suggestions, b.suggestions)
    }

    @Test fun newFacesJoinExistingGroupsWithoutReshufflingThem() {
        val first = (1L..20L).map { face(it, person = (it % 2).toInt()) }
        val existing = next(first, cluster(first, none))
        val new = (21L..25L).map { face(it, person = 0) }
        val result = cluster(existing + new, none)
        assertEquals(new.map { it.id }.toSet(), result.changed.keys) // nothing already grouped moved
        val group0 = existing.first { it.id == 2L }.cluster // face 2 is person 0
        assertEquals(setOf(group0), new.map { result.changed.getValue(it.id).cluster }.toSet())
        assertTrue(new.all { result.changed.getValue(it.id).fit >= ATTACH })
    }

    @Test fun aFaceTooCloseToTwoGroupsIsLeftOutRatherThanGuessed() {
        // Two people, then a face exactly between them: it matches both about as well
        val faces = next((1L..12L).map { face(it, person = (it % 2).toInt()) }, cluster((1L..12L).map { face(it, person = (it % 2).toInt()) }, none))
        val between = FaceVector(13, 13, box, unit(FloatArray(128) { people[0][it] + people[1][it] }), 0)
        val result = cluster(faces + between, none)
        assertEquals(UNSURE, result.changed.getValue(13).cluster)
        assertTrue(result.changed.getValue(13).fit > 0f) // its best score so far, kept for later tries
    }

    @Test fun weakFacesJoinAClearMatchButNeverStartAPerson() {
        val weak = (1L..6L).map { face(it, person = 0, weak = true) }
        assertTrue(cluster(weak, none).changed.values.all { it.cluster == UNSURE })
        val strong = (11L..16L).map { face(it, person = 1) }
        val grouped = next(strong, cluster(strong, none))
        val result = cluster(grouped + face(20, person = 1, weak = true), none)
        assertEquals(grouped.first().cluster, result.changed.getValue(20).cluster)
    }

    @Test fun groupsGrowByExemplarsNotByACentroid() {
        // Each face is a common direction plus its own, so any two score 1/3: below every threshold. Their sum points
        // along the common direction, so a new look-alike scores high against the centroid, but not against any face.
        fun lookAlike(i: Int) = unit(FloatArray(128) { if (it == 0) 1f else if (it == i) 1.4142135f else 0f })
        val group = (1..8).map { FaceVector(it.toLong(), it.toLong(), box, lookAlike(it), AUTO_CLUSTERS + 1) }
        val newcomer = FaceVector(9, 9, box, lookAlike(9), 0)
        val centroid = unit(FloatArray(128) { i -> group.sumOf { it.embedding[i].toDouble() }.toFloat() })
        assertTrue(dot(centroid, newcomer.embedding) > ATTACH)
        assertTrue(score(newcomer, group) < ATTACH)
        assertEquals(UNSURE, cluster(group + newcomer, none).changed.getValue(9).cluster)
    }

    @Test fun aChainOfLookAlikesNeverStartsAPerson() {
        // Faces along a circle, 53 degrees apart: neighbours score 0.6, but two apart -0.28. Chained one pair at a time
        // they'd make one "person"; averaged over all their pairs they don't hold together.
        val faces = (0..4).map { i -> FaceVector(i + 1L, i + 1L, box, FloatArray(128).also { it[0] = cos(i * 0.9273f); it[1] = sin(i * 0.9273f) }, 0) }
        assertTrue(dot(faces[0].embedding, faces[1].embedding) > SAME_PERSON)
        assertTrue(cluster(faces, none).changed.values.all { it.cluster == UNSURE })
    }

    @Test fun peopleSeenOnceBeforeComeTogetherWhenTheyReturn() {
        // Two faces of a person, each alone and unsure after its own run; a third arrives and the three hold together
        val old = listOf(face(1, person = 2, cluster = UNSURE), face(2, person = 2, cluster = UNSURE), face(3, person = 4, cluster = UNSURE))
        val result = cluster(old + face(4, person = 2), none)
        val group = result.changed.getValue(4).cluster
        assertTrue(group >= AUTO_CLUSTERS)
        assertEquals(setOf(1L, 2L, 4L), result.changed.filterValues { it.cluster == group }.keys)
    }

    @Test fun anUnsureFaceJoinsAGroupThatAppearsLater() {
        val unsure = face(1, person = 3, cluster = UNSURE, weak = true, fit = 0.1f) // weak: it can't start the group itself
        val result = cluster(listOf(unsure) + (2L..5L).map { face(it, person = 3) }, none)
        assertEquals(result.changed.getValue(2).cluster, result.changed.getValue(1).cluster)
    }

    @Test fun anUnsureFaceJoinsItsPersonWhenTheyGrowWhateverItScoredBefore() {
        // Face 20 was left out earlier with its best score against this very group (it was too small then, say). The
        // group grows and the face clearly matches it, with nothing else close: its own old score is no rival.
        val first = (1L..6L).map { face(it, person = 0) }
        val group = next(first, cluster(first, none))
        val earlier = face(20, person = 0, cluster = UNSURE)
        val unsure = earlier.with(UNSURE, fit = score(earlier, group))
        val result = cluster(group + unsure + face(21, person = 0), none)
        assertEquals(group.first().cluster, result.changed.getValue(21).cluster)
        assertEquals(group.first().cluster, result.changed.getValue(20).cluster)
    }

    @Test fun groupsAnEditGrewCountAsChanged() {
        // The user merged a group into person 7: its faces moved outside cluster(), so this run can't see person 7 grow.
        // Told so, it tries the unsure face against them.
        val person = (1L..6L).map { face(it, person = 1, cluster = 7) }
        val unsure = face(20, person = 1, cluster = UNSURE)
        val corrections = Corrections(setOf(7L), emptyList(), emptyMap())
        assertTrue(20L !in cluster(person + unsure, corrections).changed)
        assertEquals(7L, cluster(person + unsure, corrections, touched = setOf(7L)).changed.getValue(20).cluster)
    }

    @Test fun anUnsureFaceStillHasToBeatEveryGroup() {
        // A new face makes group A change; the unsure face matches A, but group B (unchanged) just as well
        val a = (1L..6L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 1) }
        val b = (7L..12L).map { face(it, person = 1, cluster = AUTO_CLUSTERS + 2) }
        val between = FaceVector(20, 20, box, unit(FloatArray(128) { people[0][it] + people[1][it] }), UNSURE)
        val result = cluster(a + b + between + face(21, person = 0), none)
        assertEquals(AUTO_CLUSTERS + 1, result.changed.getValue(21).cluster)
        assertTrue(score(between, a) >= ATTACH)
        assertTrue(20L !in result.changed) // still left out
    }

    @Test fun confirmedFacesGoToTheirPersonWhateverTheyLookLike() {
        val faces = (1L..10L).map { face(it, person = 0) } + face(11, person = 1)
        val corrections = Corrections(setOf(5L), listOf(Confirmed(5, mediaId = 11, box, order = 1)), emptyMap())
        val clusters = apply(faces, cluster(faces, corrections))
        assertEquals(5L, clusters[11]) // the user said face 11 is person 5
        assertTrue((1L..10L).none { clusters[it] == 5L })
    }

    @Test fun latestConfirmationWinsWhenTwoPeopleClaimOneFace() {
        val faces = listOf(face(1, person = 0))
        val confirmed = listOf(Confirmed(3, 1, box, order = 1), Confirmed(4, 1, box, order = 2))
        val clusters = apply(faces, cluster(faces, Corrections(setOf(3L, 4L), confirmed, emptyMap())))
        assertEquals(4L, clusters[1])
    }

    @Test fun notThisPersonTakesThePhotoOutForGood() {
        // Person 9 is person 0's faces, grouped automatically; photo 3 is then marked "Not" them
        val faces = (1L..6L).map { face(it, person = 0, cluster = 9) }
        val corrections = Corrections(setOf(9L), emptyList(), mapOf(3L to setOf(9L)))
        val clusters = apply(faces, cluster(faces, corrections))
        assertNotEquals(9L, clusters[3]) // even though it looks just like them
        assertTrue(clusters.filterKeys { it != 3L }.values.all { it == 9L })
    }

    @Test fun facesOfAPersonThatIsGoneAreLookedAtAgain() {
        val faces = (1L..4L).map { face(it, person = 0, cluster = 7) } // person 7 was deleted
        val clusters = apply(faces, cluster(faces, none))
        assertTrue(clusters.values.toSet().single() >= AUTO_CLUSTERS)
    }

    @Test fun afterARebuildNamedPeopleComeBackByPhotoAndBox() {
        // The index was wiped: same photos, new face ids, boxes a little off (a newer detector), nothing clustered
        val shifted = FaceBox(0.42f, 0.31f, 0.61f, 0.62f)
        val faces = (1L..5L).map { face(100 + it, person = 1, mediaId = it, b = shifted) } + (6L..8L).map { face(100 + it, person = 1, mediaId = it) }
        val confirmed = (1L..5L).map { Confirmed(2, mediaId = it, box, order = it) }
        val clusters = apply(faces, cluster(faces, Corrections(setOf(2L), confirmed, emptyMap())))
        assertTrue(clusters.values.all { it == 2L }) // confirmed ones by box, the rest by looking like them
    }

    @Test fun groupsAreNeverMergedByThemselvesButLikelyOnesAreSuggested() {
        val a = (1L..6L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 1) }
        val b = (7L..10L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 2) }
        val c = (11L..14L).map { face(it, person = 1, cluster = AUTO_CLUSTERS + 3) }
        val result = cluster(a + b + c + face(15, person = 0), none)
        // The new face matches the two groups of person 0 equally, so it's left out; nothing else moves
        assertEquals(mapOf(15L to UNSURE), result.changed.mapValues { it.value.cluster })
        assertEquals(listOf(AUTO_CLUSTERS + 1 to AUTO_CLUSTERS + 2), result.suggestions.map { it.a to it.b })
        assertTrue(result.suggestions.single().score >= SUGGEST)
    }

    @Test fun peopleSaidToBeDifferentOrSharingAPhotoAreNeverSuggested() {
        val one = (1L..4L).map { face(it, person = 0, cluster = 1) }
        val two = (5L..8L).map { face(it, person = 0, cluster = 2) }
        assertEquals(1, cluster(one + two, Corrections(setOf(1L, 2L), emptyList(), emptyMap())).suggestions.size)
        assertEquals(emptyList<Suggestion>(), cluster(one + two, Corrections(setOf(1L, 2L), emptyList(), emptyMap(), setOf(1L to 2L))).suggestions)
        val together = two.dropLast(1) + face(8, person = 0, cluster = 2, mediaId = 1) // in photo 1 with person 1's face
        assertEquals(emptyList<Suggestion>(), cluster(one + together, Corrections(setOf(1L, 2L), emptyList(), emptyMap())).suggestions)
        // A group made of photos the user took out of person 1 isn't offered back to them
        val outOfOne = (5L..8L).map { face(it, person = 0, cluster = AUTO_CLUSTERS + 1) }
        assertEquals(emptyList<Suggestion>(), cluster(one + outOfOne, Corrections(setOf(1L), emptyList(), mapOf(6L to setOf(1L)))).suggestions)
    }

    @Test fun twoFacesInOnePhotoNeverShareAGroup() {
        val twin = face(1, person = 0)
        val faces = listOf(twin, FaceVector(2, mediaId = 1, box, twin.embedding, 0)) + (3L..6L).map { face(it, person = 0) }
        val clusters = apply(faces, cluster(faces, none))
        assertNotEquals(clusters[1], clusters[2]) // identical faces, but one photo: two people
        // Nor does a face join a group that already has a face from its photo
        val grouped = next((10L..14L).map { face(it, person = 2) }, cluster((10L..14L).map { face(it, person = 2) }, none))
        val result = cluster(grouped + face(15, person = 2, mediaId = 10), none)
        assertNotEquals(grouped.first().cluster, result.changed.getValue(15).cluster)
    }

    @Test fun automaticIdsAreNeverHandedOutTwice() {
        val first = cluster((1L..3L).map { face(it, person = 0) }, none, nextAuto = AUTO_CLUSTERS + 40)
        assertEquals(AUTO_CLUSTERS + 40, first.changed.getValue(1).cluster)
        assertEquals(AUTO_CLUSTERS + 41, first.nextAuto)
        // Even with every group gone (photos deleted), the next one gets a fresh id
        assertEquals(AUTO_CLUSTERS + 41, cluster((4L..6L).map { face(it, person = 1) }, none, nextAuto = first.nextAuto).changed.getValue(4).cluster)
    }

    @Test fun scoreIsTheMeanOfTheThreeBestOtherFaces() {
        fun at(id: Long, x: Float) = FaceVector(id, id, box, unit(floatArrayOf(x, 1 - x)), 0)
        val probe = FaceVector(9, 9, box, floatArrayOf(1f, 0f), 0)
        val exemplars = listOf(at(1, 1f), at(2, 1f), at(3, 0f), at(4, 1f), probe)
        assertEquals(1f, score(probe, exemplars), 1e-6f) // three perfect matches; itself and the odd one don't count
        assertEquals(0.5f, score(probe, listOf(at(1, 1f), at(3, 0f))), 1e-6f) // fewer than three: all of them
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
