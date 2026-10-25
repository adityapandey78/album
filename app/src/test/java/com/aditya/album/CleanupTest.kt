package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CleanupTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun shot(
        id: Long,
        taken: Long = now,
        w: Int = 4000,
        h: Int = 3000,
        size: Long = 1000 + id, // distinct unless a test wants twins
        path: String = "DCIM/Camera/",
        name: String = "IMG_$id.jpg",
        isVideo: Boolean = false,
        bucket: String = "Camera",
    ) = Shot(id, isVideo, taken, w, h, size, bucket, path, name)

    private fun row(id: Long, dhash: Long? = null, sharpness: Float? = null, contrast: Float? = null, hash: String? = null) =
        CleanupRow(id, modified = 1, size = 1, version = 1, dhash, sharpness, contrast, hash)

    // --- What a pass measures

    private fun stamp(id: Long, size: Long, isVideo: Boolean = false, modified: Long = 1) = MediaStamp(id, isVideo, modified, size)
    private fun stored(id: Long, size: Long, modified: Long = 1, hash: String? = null) = CleanupRow(id, modified, size, 1, null, null, null, hash)

    @Test fun measuresNewPhotosAndHashesOnlySameSizeFiles() {
        val plan = planCleanup(listOf(stamp(1, 10), stamp(2, 20, isVideo = true), stamp(3, 30), stamp(4, 30, isVideo = true)), emptyList(), 1)
        assertEquals(listOf(1L, 3L, 4L), plan.work.map { it.item.id }) // the lone video has nothing to measure
        assertEquals(listOf(true, true, false), plan.work.map { it.look })
        assertEquals(listOf(false, true, true), plan.work.map { it.hash })
    }

    @Test fun skipsMeasuredFilesButHashesOneThatGainedATwin() {
        val rows = listOf(stored(1, 10), stored(2, 20, hash = "ab"))
        val plan = planCleanup(listOf(stamp(1, 10), stamp(2, 20), stamp(3, 10)), rows, 1)
        assertEquals(listOf(1L, 3L), plan.work.map { it.item.id })
        assertEquals(listOf(false, true), plan.work.map { it.look }) // 1 is measured already, it only needs its hash now
    }

    @Test fun unreadableFilesAreNotRetriedUntilTheyChange() {
        val rows = listOf(stored(1, 10, hash = ""), stored(2, 10, hash = ""))
        assertTrue(planCleanup(listOf(stamp(1, 10), stamp(2, 10)), rows, 1).work.isEmpty())
        assertEquals(listOf(1L), planCleanup(listOf(stamp(1, 10, modified = 2), stamp(2, 10)), rows, 1).work.map { it.item.id })
    }

    @Test fun removesRowsOfDeletedAndEditedLoneVideos() {
        val rows = listOf(stored(1, 10), stored(2, 20), stored(3, 30))
        val plan = planCleanup(listOf(stamp(2, 21, isVideo = true), stamp(3, 30)), rows, 1)
        assertEquals(listOf(2L, 1L), plan.remove) // 2 was edited and no longer shares its size; 1 is gone
        assertTrue(plan.work.isEmpty())
    }

    @Test fun redoesOlderVersions() {
        assertEquals(listOf(1L), planCleanup(listOf(stamp(1, 10)), listOf(stored(1, 10)), version = 2).work.map { it.item.id })
    }

    // --- Measuring

    /** A 9 by 8 grid of random grey blocks, [scale] pixels each, so dHash cells line up with blocks. */
    private fun blocks(seed: Long, scale: Int, lift: Int = 0): IntArray {
        val levels = Random(seed).let { r -> IntArray(72) { r.nextInt(200) } }
        val w = 9 * scale
        return IntArray(w * 8 * scale) { i -> levels[(i / w / scale) * 9 + (i % w) / scale] + lift }
    }

    @Test fun dHashIgnoresSizeAndExposureButNotTheScene() {
        val a = dHash(blocks(1, 10), 90, 80)
        assertEquals(a, dHash(blocks(1, 20), 180, 160)) // resized
        assertEquals(a, dHash(blocks(1, 10, lift = 40), 90, 80)) // brighter
        assertTrue(hamming(a, dHash(blocks(2, 10), 90, 80)) > SIMILAR_BITS * 2) // another scene
    }

    @Test fun blurNeedsContrastAndSoftEdges() {
        val crisp = blocks(3, 20)
        assertTrue(sharpnessOf(crisp, 180, 160) > BLUR_SHARPNESS * 10)
        // A smooth ramp from black to white: plenty to see, no edges at all
        val ramp = IntArray(180 * 160) { (it % 180) * 255 / 179 }
        assertTrue(isBlurry(sharpnessOf(ramp, 180, 160), contrastOf(ramp)))
        // A flat grey wall can't be judged, so it is never called blurry
        val flat = IntArray(180 * 160) { 128 }
        assertFalse(isBlurry(sharpnessOf(flat, 180, 160), contrastOf(flat)))
        assertFalse(isBlurry(null, null)) // not measured
    }

    @Test fun greyUsesLumaWeights() {
        assertEquals(listOf(255, 0, 76, 149, 29), greyOf(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())).toList())
    }

    @Test fun contentHashIsSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", contentHash("abc".byteInputStream()))
        val big = ByteArray(200_000) { it.toByte() } // several 64 KB pieces
        assertEquals(contentHash(big.inputStream()), contentHash(big.copyOf().inputStream()))
    }

    // --- Rules

    @Test fun bestShotIsSharpestThenBiggestThenNewest() {
        val sharp = mapOf(1L to 100f, 2L to 105f, 3L to 300f, 4L to 100f)
        val by = { s: Shot -> sharp.getValue(s.id) }
        assertEquals(3L, bestShot(listOf(shot(1), shot(2), shot(3)), by).id)
        // 100 and 105 are one step apart at most: the bigger photo wins
        assertEquals(1L, bestShot(listOf(shot(1, w = 8000, h = 6000), shot(2)), by).id)
        // same sharpness and size: the newer one
        assertEquals(4L, bestShot(listOf(shot(1, taken = now), shot(4, taken = now + 1000)), by).id)
    }

    @Test fun similarShotsNeedCloseTimesSameOrientationAndCloseHashes() {
        val photos = listOf(
            shot(1, taken = now), shot(2, taken = now + 30_000), shot(3, taken = now + 60_000),
            shot(4, taken = now + 45_000), // another scene
            shot(5, taken = now + 20_000, w = 3000, h = 4000), // portrait
            shot(6, taken = now + 10 * 60_000), // same scene, ten minutes later
        )
        val hashes = mapOf(1L to 0L, 2L to 0b11L, 3L to 0b101L, 4L to -1L, 5L to 0L, 6L to 0L)
        val sharpness = mapOf(2L to 500f)
        val groups = similarGroups(photos, { hashes[it.id] }, { sharpness[it.id] ?: 50f })
        assertEquals(listOf(ShotGroup(2, listOf(2L, 3L, 1L))), groups) // best first, then newest first
    }

    @Test fun burstsChainWithinTheWindow() {
        // Each shot is two minutes after the last: every pair of neighbours is within the window
        val photos = (0 until 4).map { shot(it.toLong(), taken = now + it * 2 * 60_000L) }
        assertEquals(1, similarGroups(photos, { 0L }, { 1f }).size)
        assertTrue(similarGroups(photos, { null }, { 1f }).isEmpty()) // not measured yet
    }

    @Test fun duplicatesKeepTheFirstSaved() {
        val shots = listOf(shot(9, size = 50), shot(3, size = 50), shot(5, size = 50), shot(7, size = 60), shot(8, size = 60))
        val hashes = mapOf(9L to "a", 3L to "a", 5L to "b", 7L to "", 8L to "")
        assertEquals(listOf(ShotGroup(3, listOf(3L, 9L))), duplicateGroups(shots) { hashes[it.id] }) // unreadable ("") never match
    }

    @Test fun sortsTheLibraryIntoCategories() {
        val shots = listOf(
            shot(1, taken = now - 31 * day, path = "Pictures/Screenshots/", name = "Screenshot_1.png"),
            shot(2, taken = now - 5 * day, path = "Pictures/Screenshots/", name = "Screenshot_2.png"),
            shot(3, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", bucket = "WhatsApp Images"),
            shot(4, isVideo = true, size = 150_000_000),
            shot(5, isVideo = true, size = 300_000_000),
            shot(6, isVideo = true, size = 50_000_000),
            shot(7, size = 777), shot(8, size = 777), // copies, which also look alike
            shot(10), // blurry
            shot(11, path = "Pictures/Screenshots/", name = "Screenshot_11.png"), // a blurry-looking screenshot is still a screenshot
        )
        val rows = listOf(
            row(7, dhash = 0, hash = "x"), row(8, dhash = 0, hash = "x"),
            row(10, sharpness = 2f, contrast = 40f), row(11, sharpness = 2f, contrast = 40f),
        ).associateBy { it.id }
        val r = cleanupOf(shots, rows, now)
        assertEquals(listOf(1L), r.screenshots)
        assertEquals(listOf(3L), r.whatsApp)
        assertEquals(listOf(5L, 4L), r.bigVideos)
        assertEquals(listOf(ShotGroup(7, listOf(7L, 8L))), r.duplicates)
        assertTrue(r.similar.isEmpty()) // 8 is already a duplicate, so 7 has nothing alike left
        assertEquals(listOf(10L), r.blurry)
    }

    @Test fun summaryCountsEachSuggestedItemOnce() {
        val r = CleanupResult(
            similar = listOf(ShotGroup(1, listOf(1L, 2L, 3L))), duplicates = emptyList(),
            blurry = listOf(2L, 4L), screenshots = emptyList(), whatsApp = listOf(5L), bigVideos = emptyList(),
        )
        val s = summaryOf(r) { it * 10 }
        assertEquals(CleanupCount(2, 50), s.kinds[CleanupKind.Similar]) // the best shot isn't suggested
        assertEquals(CleanupCount(2, 60), s.kinds[CleanupKind.Blurry])
        assertEquals(3, s.toReview) // 2, 3 and 4; WhatsApp is there to browse, not a suggestion
        assertEquals(90, s.reviewBytes)
    }

    @Test fun reviewQueueShowsBestShotsFirstAndMergesReasons() {
        val r = CleanupResult(
            similar = listOf(ShotGroup(1, listOf(1L, 2L, 3L))), duplicates = listOf(ShotGroup(4, listOf(4L, 5L))),
            blurry = listOf(2L, 7L), screenshots = listOf(9L), whatsApp = listOf(10L), bigVideos = emptyList(),
        )
        val q = reviewQueue(r, CleanupKind.entries.filter { it.suggested })
        assertEquals(listOf(1L, 2L, 3L, 5L, 7L, 9L), q.map { it.id })
        assertEquals(ReviewItem(1, listOf("Similar · 3"), best = true), q[0])
        assertEquals(listOf("Similar · 3", "Blurry"), q[1].reasons)
        assertEquals(listOf("Duplicate"), q[3].reasons)
        assertEquals(listOf(10L), reviewQueue(r, listOf(CleanupKind.WhatsApp)).map { it.id })
    }

    @Test fun reviewQueueFitsOneTrashRequest() {
        val r = CleanupResult(emptyList(), emptyList(), (1L..2500L).toList(), emptyList(), emptyList(), emptyList())
        assertEquals(MAX_REQUEST, reviewQueue(r, listOf(CleanupKind.Blurry)).size)
    }

    @Test fun topFoldersBySizeTellSameNamesApart() {
        val shots = listOf(
            shot(1, size = 10, path = "DCIM/Camera/"), shot(2, size = 10, path = "DCIM/Camera/"),
            shot(3, size = 50, path = "Pictures/Camera/"), shot(4, size = 5, path = "Download/", bucket = "Download"),
        )
        assertEquals(
            listOf(FolderSize("Camera", 1, 50), FolderSize("Camera", 2, 20)),
            topFolders(shots, 2),
        )
    }
}
