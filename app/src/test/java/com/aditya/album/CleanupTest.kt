package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        modified: Long = 1,
    ) = Shot(id, isVideo, taken, w, h, size, bucket, path, name, modified)

    private fun row(
        id: Long, pdq: LongArray? = null, quality: Int? = if (pdq != null) 80 else null, blur: Float? = null, textured: Float? = null,
        capture: String? = null, faces: Int? = null, eyes: Float? = null, smile: Float? = null, faceSharpness: Float? = null,
        clipped: Float? = null, hash: String? = null, size: Long = 1000 + id,
    ) = CleanupRow(
        id, modified = 1, size = size, version = 2, pdq = pdq?.let(::pdqHex), quality = quality, blur = blur, textured = textured,
        clipped = clipped, capture = capture, faces = faces, eyes = eyes, smile = smile, faceSharpness = faceSharpness, hash = hash,
    )

    /** A hash [bits] bits away from [base]: the lowest bits flipped. */
    private fun near(base: LongArray, bits: Int) = base.copyOf().also { h -> for (b in 0 until bits) h[b / 64] = h[b / 64] xor (1L shl (b % 64)) }

    private fun random(seed: Long) = Random(seed).let { r -> LongArray(4) { r.nextLong() } }

    // A camera's capture time, [s] seconds into a minute: the photo has camera details
    private fun cam(s: Int) = "2026:01:01 10:00:${"%02d".format(s)}.00"

    // --- What a pass measures

    @Test fun measuresNewPhotosAndHashesOnlyFilesThatMayBeTheSame() {
        val found = listOf(shot(1, size = 10), shot(2, size = 20, isVideo = true), shot(3, size = 30), shot(4, size = 30, isVideo = true))
        val plan = planCleanup(found, emptyList(), emptyList(), 2, now)
        assertEquals(listOf(1L, 3L, 4L), plan.work.map { it.item.id }) // the lone video has nothing to measure
        assertEquals(listOf(true, true, false), plan.work.map { it.look })
        assertEquals(listOf(false, true, true), plan.work.map { it.hash })
        // Once measured, two photos with the same PDQ and size on screen need a hash too, though their files differ in size
        val h = random(1)
        val rows = listOf(row(1, h, size = 10, faces = 0), row(3, h, size = 30, hash = "x", faces = 0))
        val again = planCleanup(listOf(shot(1, size = 10), shot(3, size = 30)), rows, emptyList(), 2, now)
        assertEquals(listOf(1L), again.work.map { it.item.id })
        assertEquals(listOf(true), again.work.map { it.hash })
    }

    @Test fun unreadableFilesAreNotRetriedUntilTheyChange() {
        val rows = listOf(row(1, hash = "", size = 10), row(2, hash = "", size = 10))
        assertTrue(planCleanup(listOf(shot(1, size = 10), shot(2, size = 10)), rows, emptyList(), 2, now).work.isEmpty())
        assertEquals(listOf(1L), planCleanup(listOf(shot(1, size = 10, modified = 2), shot(2, size = 10)), rows, emptyList(), 2, now).work.map { it.item.id })
    }

    @Test fun removesRowsOfDeletedAndEditedLoneVideos() {
        val rows = listOf(row(1, size = 10), row(2, size = 20), row(3, size = 30, hash = "x"))
        val plan = planCleanup(listOf(shot(2, size = 21, isVideo = true), shot(3, size = 30, isVideo = true)), rows, emptyList(), 2, now)
        assertEquals(listOf(2L, 1L), plan.remove) // 2 was edited and no longer shares its size; 1 is gone
        assertTrue(plan.work.isEmpty())
    }

    @Test fun redoesOlderVersions() {
        assertEquals(listOf(1L), planCleanup(listOf(shot(1, size = 10)), listOf(row(1, size = 10)), emptyList(), 3, now).work.map { it.item.id })
    }

    @Test fun looksForFacesOnlyInSimilarShotsAndBlurSuspects() {
        val h = random(2)
        val rows = listOf(
            row(1, h, capture = cam(1)), row(2, near(h, 10), capture = cam(3)), // similar
            row(3, random(3), blur = 0.01f, textured = 0.9f, capture = cam(5)), // may be blurry
            row(4, random(4), blur = 0.5f, textured = 0.9f, capture = cam(7)), // sharp, alone
            row(5, random(5), blur = 0.01f, textured = 0.9f), // soft, but no camera details (a forward): never judged for blur
        )
        val found = listOf(shot(1), shot(2, taken = now + 2000), shot(3, taken = now - day), shot(4, taken = now - 2 * day), shot(5, taken = now - 3 * day))
        val plan = planCleanup(found, rows, emptyList(), 2, now)
        assertEquals(listOf(1L, 2L, 3L), plan.work.filter { it.faces }.map { it.item.id })
        // Looked at already: nothing more to do
        val looked = rows.map { it.copy(faces = 0) }
        assertTrue(planCleanup(found, looked, emptyList(), 2, now).work.isEmpty())
    }

    // --- Measuring

    @Test fun contentHashIsSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", contentHash("abc".byteInputStream()))
        val big = ByteArray(200_000) { it.toByte() } // several 64 KB pieces
        assertEquals(contentHash(big.inputStream()), contentHash(big.copyOf().inputStream()))
    }

    private fun jpeg(scan: Byte, vararg extra: Byte): ByteArray {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 6) + "Exif".toByteArray()
        val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 4, 1, 2)
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 3, 4)
        val data = byteArrayOf(10, scan, 0xFF.toByte(), 0, 11, 0xFF.toByte(), 0xD3.toByte(), 12) // a stuffed 0xFF and a restart marker
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return soi + app1 + extra + dqt + sos + data + eoi
    }

    @Test fun imageHashIgnoresMetadataButNotPixels() {
        val plain = imageHash(jpeg(1))
        val comment = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 5, 'h'.code.toByte(), 'i'.code.toByte(), '!'.code.toByte())
        assertEquals(plain, imageHash(jpeg(1, *comment))) // a comment added
        assertEquals(plain, imageHash(jpeg(1) + "a motion photo's video".toByteArray())) // after the end of the image
        assertNotEquals(plain, imageHash(jpeg(2))) // one compressed byte differs
        // PNG: a text chunk is metadata, a data chunk isn't
        fun chunk(type: String, data: String) = byteArrayOf(0, 0, 0, data.length.toByte()) + type.toByteArray() + data.toByteArray() + byteArrayOf(0, 0, 0, 0)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val png = signature + chunk("IHDR", "head") + chunk("IDAT", "pixels") + chunk("IEND", "")
        val tagged = signature + chunk("IHDR", "head") + chunk("tEXt", "Date 2026") + chunk("IDAT", "pixels") + chunk("IEND", "")
        assertEquals(imageHash(png), imageHash(tagged))
        assertNotEquals(imageHash(png), imageHash(signature + chunk("IHDR", "head") + chunk("IDAT", "pixelz") + chunk("IEND", "")))
    }

    @Test fun framesAreTrimmedButPicturesAreNot() {
        val w = 100
        val h = 80
        // A black frame 10 px deep around noise
        val r = Random(5)
        val framed = FloatArray(w * h) { i -> if (i % w in 10 until 90 && i / w in 10 until 70) 60f + r.nextInt(150) else 3f }
        assertEquals(listOf(10, 10, 90, 70), frameOf(framed, w, h).toList())
        // A mid-grey plain border is part of the picture (a wall), and nothing is trimmed from a picture without a frame
        val grey = FloatArray(w * h) { i -> if (i % w in 10 until 90 && i / w in 10 until 70) 60f + r.nextInt(150) else 128f }
        assertEquals(listOf(0, 0, w, h), frameOf(grey, w, h).toList())
        // A white page: never more than 40% from a side
        assertEquals(listOf(40, 32, 60, 48), frameOf(FloatArray(w * h) { 250f }, w, h).toList())
    }

    @Test fun sharpnessNeedsTextureAndReadsBlurFromIt() {
        val w = 256
        val h = 192
        val r = Random(7)
        val levels = IntArray(64 * 48) { r.nextInt(200) }
        val crisp = FloatArray(w * h) { i -> levels[(i / w / 4) * 64 + (i % w) / 4].toFloat() } // 4 px blocks with hard edges
        val (sharp, textured) = sharpnessOf(crisp, w, h)
        assertTrue(textured > 0.9f)
        // The same blocks seen through a soft lens: averaged over 9 x 9 pixels
        val soft = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0f
            for (dy in -4..4) for (dx in -4..4) s += crisp[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            s / 81
        }
        assertTrue(sharpnessOf(soft, w, h).first!! < sharp!! / 4)
        // A flat wall has nothing to judge
        assertEquals(null to 0f, sharpnessOf(FloatArray(w * h) { 128f }, w, h))
    }

    @Test fun lookAtTrimsScreenshotBarsAndFrames() {
        // A photo shown in a black letterbox under a status bar that says the time: the bars and the frame come off
        val w = 60
        val h = 120
        val r = Random(9)
        val photo = IntArray(w * 40) { val v = r.nextInt(256); (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val screen = IntArray(w * h) { i ->
            val y = i / w
            when {
                y < 6 -> if (i % 7 == 0) -1 else 0xff202020.toInt() // status bar with a clock
                y in 40 until 80 -> photo[(y - 40) * w + i % w]
                else -> 0xff000000.toInt()
            }
        }
        val hasher = PdqHasher()
        val alone = lookAt(photo, w, 40, false, hasher).pdq.original
        assertEquals(0, pdqDistance(alone, lookAt(screen, w, h, true, hasher).pdq.original))
    }

    // --- Rules

    @Test fun originalHasCameraDetailsThenMostPixelsThenOwnFolderThenEarliestThenFirstSaved() {
        val camera = { s: Shot -> s.id in setOf(2L, 3L, 4L, 5L) }
        // Camera details first: an enlarged or "enhanced" export without them never wins over the photo it came from
        assertEquals(2L, originalOf(listOf(shot(1, w = 8000, h = 6000), shot(2)), camera).id)
        assertEquals(2L, originalOf(listOf(shot(1), shot(2)), camera).id)
        assertEquals(1L, originalOf(listOf(shot(1, w = 8000, h = 6000), shot(6)), camera).id) // neither has them: most pixels
        val whatsApp = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        assertEquals(3L, originalOf(listOf(shot(2, path = whatsApp), shot(3)), camera).id)
        assertEquals(4L, originalOf(listOf(shot(3, taken = now + 1), shot(4, taken = now)), camera).id)
        // Same moment, same folder: the first saved, whatever the sizes (an edit saved as a copy can be the bigger file)
        assertEquals(4L, originalOf(listOf(shot(5, size = 9_000), shot(4, size = 10)), camera).id)
        // A screenshot has the screen's pixels, more than a small photo it shows: never the original
        assertEquals(1L, originalOf(listOf(shot(1, w = 1280, h = 853), shot(2, w = 1080, h = 2400, path = "Pictures/Screenshots/", name = "Screenshot_2.png")), camera).id)
    }

    @Test fun anEnlargedExportNeverGetsTheCameraOriginalTrashed() {
        val shots = listOf(shot(1, w = 4000, h = 3000), shot(2, w = 8000, h = 6000, path = "Pictures/"))
        val rows = mapOf(1L to row(1, random(61), capture = "2026:01:01 10:00:00.10"), 2L to row(2, random(61)))
        assertEquals(listOf(ShotGroup(1, listOf(1L, 2L))), copyGroups(shots, rows, listOf(CopyPair(1, 2, 4))))
    }

    @Test fun trashedCopiesHandOverToTheOriginalThatStays() {
        val r = result(copies = listOf(ShotGroup(1, listOf(1L, 2L, 3L)), ShotGroup(6, listOf(6L, 4L, 5L))), similar = listOf(ShotGroup(8, listOf(8L, 9L))), blurry = listOf(7L))
        assertEquals(listOf(1L to 2L, 1L to 3L), r.originalsOf(listOf(2L, 3L, 7L))) // picked copies and a blurry photo
        assertEquals(listOf(6L to 5L), r.originalsOf(listOf(1L, 2L, 5L))) // 1 goes too: nothing stays to take over from 2
        assertEquals(emptyList<Pair<Long, Long>>(), r.originalsOf(listOf(9L))) // a similar shot is another moment, not a copy
    }

    @Test fun exactCopiesJoinTheCopiesOfTheirOriginal() {
        // 2 and 3 are one WhatsApp photo saved twice, 2 a copy of camera photo 1; 6 is 1 saved again; 5 the same file as 4
        val near = listOf(ShotGroup(1, listOf(1L, 2L)))
        val exact = listOf(ShotGroup(2, listOf(2L, 3L)), ShotGroup(4, listOf(4L, 5L)), ShotGroup(1, listOf(1L, 6L)))
        val merged = mergeCopies(exact, near) { if (it == 5L) 100L else 1L }
        assertEquals(listOf(ShotGroup(4, listOf(4L, 5L)), ShotGroup(1, listOf(1L, 2L, 3L, 6L))), merged) // the one freeing most first
    }

    @Test fun exactCopiesNeedTheSamePictureData() {
        val h = random(11)
        val shots = listOf(shot(1, size = 50), shot(2, size = 60, path = "Download/"), shot(3, size = 50), shot(4, size = 70, w = 400, h = 300))
        val rows = listOf(
            row(1, h, hash = "a"), row(2, h, hash = "a"), // same pixels, one with its metadata changed
            row(3, near(h, 1), hash = "b"), // looks the same, isn't
            row(4, h, hash = "a"), // same data but another size on screen: can't be
        ).associateBy { it.id }
        assertEquals(listOf(ShotGroup(1, listOf(1L, 2L))), exactGroups(shots, rows))
        // Videos: byte for byte
        val videos = listOf(shot(5, isVideo = true, size = 9), shot(6, isVideo = true, size = 9))
        assertEquals(listOf(ShotGroup(5, listOf(5L, 6L))), exactGroups(videos, mapOf(5L to row(5, hash = "v"), 6L to row(6, hash = "v"))))
        assertTrue(exactGroups(videos, mapOf(5L to row(5, hash = ""), 6L to row(6, hash = ""))).isEmpty()) // unreadable never match
    }

    @Test fun copiesKeepTheOriginalAndNeverPairTwoCameraPhotosOrTwoScreenshots() {
        val whatsApp = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        val shots = listOf(
            shot(1), shot(2, w = 1600, h = 1200, path = whatsApp), shot(3, w = 1080, h = 810, path = "Pictures/"),
            shot(4), // a burst frame next to 1
            shot(5, path = "Pictures/Screenshots/", name = "Screenshot_5.png"), shot(6, path = "Pictures/Screenshots/", name = "Screenshot_6.png"),
            shot(7), shot(8, w = 1600, h = 1200), // too plain to trust
            shot(9, path = "DCIM/Camera/"), // an edit of 1 saved as a copy: same moment, camera details kept
        )
        val rows = listOf(
            row(1, capture = "2026:01:01 10:00:00.10"), row(2), row(3), row(4, capture = "2026:01:01 10:00:00.40"),
            row(5), row(6), row(7, quality = 30), row(8, quality = 30), row(9, capture = "2026:01:01 10:00:00.10"),
        ).associateBy { it.id }.mapValues { (_, r) -> r.copy(pdq = pdqHex(random(1)), quality = r.quality ?: 80) }
        val pairs = listOf(
            CopyPair(1, 2, 12), CopyPair(1, 3, 20), CopyPair(2, 3, 9), CopyPair(1, 4, 6), CopyPair(5, 6, 0), CopyPair(7, 8, 2), CopyPair(1, 9, 8),
        )
        assertEquals(listOf(ShotGroup(1, listOf(1L, 2L, 3L))), copyGroups(shots, rows, pairs))
        // A copy of a copy that's too far from the original stays out: no chains
        assertEquals(listOf(ShotGroup(1, listOf(1L, 2L))), copyGroups(shots.take(3), rows, listOf(CopyPair(1, 2, 12), CopyPair(2, 3, 25))))
    }

    @Test fun similarShotsNeedCloseTimesSameOrientationAndToLookLikeTheWholeGroup() {
        val a = random(21)
        val photos = listOf(
            shot(1, taken = now), shot(2, taken = now + 30_000), shot(3, taken = now + 60_000),
            shot(4, taken = now + 45_000), // another scene
            shot(5, taken = now + 20_000, w = 3000, h = 4000), // portrait
            shot(6, taken = now + 10 * 60_000), // same scene, ten minutes later
        )
        val hashes = mapOf(1L to a, 2L to near(a, 2), 3L to near(a, 3), 4L to random(22), 5L to a, 6L to a)
        val sharp = mapOf(2L to 0.5f)
        val groups = similarGroups(photos, { hashes[it.id] }) { g -> bestShot(g) { s -> row(s.id, blur = sharp[s.id] ?: 0.1f) } }
        assertEquals(listOf(ShotGroup(2, listOf(2L, 3L, 1L))), groups) // best first, then newest first
    }

    @Test fun similarShotsDoNotChain() {
        // 1 looks like 2 and 2 like 3, but 1 and 3 are far apart: never one group of three
        val a = random(31)
        val b = near(a, 40)
        val c = near(a, 80)
        val photos = listOf(shot(1, taken = now), shot(2, taken = now + 1000), shot(3, taken = now + 2000))
        val hashes = mapOf(1L to a, 2L to b, 3L to c)
        val groups = similarGroups(photos, { hashes[it.id] }) { it.first() }
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].ids.size)
        // A long run of near shots splits once a group would span more than five minutes
        val run = (0 until 8).map { shot(it.toLong(), taken = now + it * 60_000L) }
        assertTrue(similarGroups(run, { a }) { it.first() }.all { g -> g.ids.size <= 6 })
        assertTrue(similarGroups(run, { null }) { it.first() }.isEmpty()) // not measured yet
    }

    @Test fun bestShotGoesByEyesThenSmilesThenSharpness() {
        val shots = listOf(shot(1), shot(2), shot(3))
        val rows = mapOf(
            1L to row(1, blur = 0.9f, faces = 1, eyes = 0.1f, smile = 0.9f), // sharpest, eyes closed
            2L to row(2, blur = 0.5f, faces = 1, eyes = 0.95f, smile = 0.2f),
            3L to row(3, blur = 0.5f, faces = 1, eyes = 0.9f, smile = 0.8f), // eyes open and smiling
        )
        assertEquals(3L, bestShot(shots) { rows[it.id] }.id)
        // Without faces: sharpness less clipping, in steps, then pixels, then newest
        val plain = mapOf(1L to row(1, blur = 0.9f, clipped = 0.5f), 2L to row(2, blur = 0.8f), 3L to row(3, blur = 0.78f))
        assertEquals(3L, bestShot(listOf(shot(1), shot(2), shot(3, taken = now + 1))) { plain[it.id] }.id)
        assertEquals(2L, bestShot(listOf(shot(1), shot(2, w = 8000, h = 6000), shot(3))) { plain[it.id] }.id)
    }

    @Test fun blurNeedsTextureNoDepthAndNoSharpFace() {
        val blurry = row(1, blur = BLUR_SHARPNESS / 2, textured = 0.8f, faces = 0)
        assertTrue(isBlurry(blurry))
        assertFalse(isBlurry(blurry.copy(faces = null))) // not looked at closely yet
        assertFalse(isBlurry(blurry.copy(textured = 0.1f))) // fog, sky: nothing to judge
        assertFalse(isBlurry(blurry.copy(portrait = true))) // the soft background is on purpose
        assertFalse(isBlurry(blurry.copy(faces = 1, faceSharpness = FACE_SHARPNESS * 2))) // a sharp face: a portrait
        assertFalse(isBlurry(blurry.copy(night = true, blur = (BLUR_SHARPNESS + BLUR_SHARPNESS_NIGHT) / 2))) // night shots need more
        assertTrue(isDark(row(2).copy(high = 20)))
        assertFalse(isDark(row(2).copy(high = 200)))
        assertTrue(isBright(row(3).copy(low = 240)))
    }

    @Test fun everyItemShowsUnderTheOneCategoryThatExplainsIt() {
        val h = random(41)
        val b = random(46)
        val whatsApp = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        val shots = listOf(
            shot(1, taken = now - 31 * day, path = "Pictures/Screenshots/", name = "Screenshot_1.png"),
            shot(2, taken = now - 5 * day, path = "Pictures/Screenshots/", name = "Screenshot_2.png"), // not old yet
            shot(3, path = whatsApp, bucket = "WhatsApp Images"),
            shot(4, isVideo = true, size = 150_000_000),
            shot(5, isVideo = true, size = 300_000_000, path = whatsApp, bucket = "WhatsApp Video"), // big before WhatsApp
            shot(6, isVideo = true, size = 50_000_000),
            shot(7, size = 777), shot(8, size = 777, path = "Download/"), // exact copies, which also look alike
            shot(9, w = 1600, h = 1200, taken = now + 1000, path = whatsApp, bucket = "WhatsApp Images"), // shared: a copy of 7, not WhatsApp
            shot(10), // blurry
            shot(11, taken = now - 40 * day, path = "Pictures/Screenshots/", name = "Screenshot_11.png"), // of 7: a copy before an old screenshot
            shot(12), shot(13), // too dark, too bright
            shot(14, path = whatsApp, bucket = "WhatsApp Images"), // a soft forward: no camera details, never called blurry
            shot(15, taken = now + 60_000), shot(16, taken = now + 62_000), // a burst; 16 also blurry
            shot(17, taken = now + 62_500), // blurry and too dark: too dark says it better
        )
        val rows = listOf(
            row(7, h, hash = "x", capture = cam(1)), row(8, h, hash = "x", capture = cam(1)), row(9, near(h, 5)), row(11, near(h, 8)),
            row(10, random(42), blur = 0.001f, textured = 0.9f, faces = 0, capture = cam(2)),
            row(12, random(44), quality = 10, capture = cam(3)).copy(high = 12), row(13, random(45), quality = 10, capture = cam(4)).copy(low = 250),
            row(14, random(47), blur = 0.001f, textured = 0.9f, faces = 0),
            row(15, b, blur = 0.5f, capture = cam(5)), row(16, near(b, 6), blur = 0.001f, textured = 0.9f, faces = 0, capture = cam(7)),
            row(17, random(48), blur = 0.001f, textured = 0.9f, faces = 0, capture = cam(8)).copy(high = 10),
        ).associateBy { it.id }
        val r = cleanupOf(shots, rows, listOf(CopyPair(7, 9, 5), CopyPair(8, 9, 5), CopyPair(7, 11, 8)), now)
        assertEquals(listOf(ShotGroup(7, listOf(7L, 9L, 11L, 8L))), r.copies) // the exact copy joins the copies of its original
        assertEquals(setOf(8L), r.exact) // and is still known as one
        assertEquals(listOf(ShotGroup(15, listOf(15L, 16L))), r.similar)
        assertEquals(listOf(12L, 17L), r.dark)
        assertEquals(listOf(13L), r.bright)
        assertEquals(listOf(10L), r.blurry)
        assertEquals(listOf(1L), r.screenshots)
        assertEquals(listOf(5L, 4L), r.bigVideos)
        assertEquals(listOf(3L, 14L), r.whatsApp)
        // Nothing suggested twice, and what stays (the original 7, the best shot 15) suggested nowhere
        val all = CleanupKind.entries.flatMap { r.candidates(it) }
        assertEquals(all.size, all.toSet().size)
        assertFalse(7L in all || 15L in all)
    }

    @Test fun aSharedShotStaysAmongItsSimilarShots() {
        val a = random(71)
        val shots = listOf(shot(1), shot(2, taken = now + 1000), shot(3, w = 1600, h = 1200, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        val rows = mapOf(
            1L to row(1, a, blur = 0.2f, capture = cam(0)), // softer, but the one you shared
            2L to row(2, near(a, 10), blur = 0.6f, capture = cam(1)),
            3L to row(3, near(a, 2)),
        )
        val r = cleanupOf(shots, rows, listOf(CopyPair(1, 2, 10), CopyPair(1, 3, 2), CopyPair(2, 3, 12)), now)
        assertEquals(listOf(ShotGroup(1, listOf(1L, 3L))), r.copies)
        assertEquals(listOf(ShotGroup(1, listOf(1L, 2L))), r.similar)
        assertEquals(listOf(2L), r.candidates(CleanupKind.Similar))
    }

    private fun result(
        copies: List<ShotGroup> = emptyList(), similar: List<ShotGroup> = emptyList(), blurry: List<Long> = emptyList(),
        screenshots: List<Long> = emptyList(), whatsApp: List<Long> = emptyList(),
    ) = CleanupResult(copies, similar, emptyList(), emptyList(), blurry, screenshots, emptyList(), whatsApp)

    @Test fun summaryCountsEachSuggestedItemOnce() {
        val r = result(similar = listOf(ShotGroup(1, listOf(1L, 2L, 3L))), blurry = listOf(4L), whatsApp = listOf(5L))
        val s = summaryOf(r) { it * 10 }
        assertEquals(CleanupCount(2, 50), s.kinds[CleanupKind.Similar]) // the best shot isn't suggested
        assertEquals(CleanupCount(1, 40), s.kinds[CleanupKind.Blurry])
        assertEquals(3, s.toReview) // 2, 3 and 4; WhatsApp is there to browse, not a suggestion
        assertEquals(90, s.reviewBytes)
    }

    @Test fun sectionsKeepGroupsTogetherAndTheViewerGetsEachTileOnce() {
        val r = result(
            copies = listOf(ShotGroup(1, listOf(1L, 2L)), ShotGroup(3, listOf(3L, 4L))),
            similar = listOf(ShotGroup(5, listOf(5L, 1L, 6L))), // 1 is an original and one of these shots
            blurry = listOf(7L, 8L), whatsApp = listOf(9L),
        )
        val gone = setOf(4L, 8L) // trashed meanwhile
        val lookup = { id: Long -> id.takeIf { it !in gone } }
        val sections = cleanupSections(r, CleanupKind.entries.filter { it.suggested }, lookup)
        assertEquals(listOf(CleanupKind.Copies, CleanupKind.Similar, CleanupKind.Blurry), sections.map { it.kind })
        assertEquals(listOf(listOf(1L, 2L)), sections[0].runs) // the group left with one is gone
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L), tilesOf(sections) { it }) // screen order, each photo once
        assertEquals(listOf(9L), tilesOf(cleanupSections(r, listOf(CleanupKind.WhatsApp), lookup)) { it })
        assertEquals(listOf(6L), r.candidates(CleanupKind.Similar)) // neither the best shot nor the original
    }

    @Test fun allPreviewsTheFirstSetOrTheFirstRow() {
        val r = result(copies = listOf(ShotGroup(1, listOf(1L, 2L)), ShotGroup(3, listOf(3L, 4L))), blurry = (10L..16L).toList())
        val (copies, blurry) = cleanupSections(r, CleanupKind.entries) { it }.map(::previewOf)
        assertEquals(listOf(listOf(1L, 2L)), copies.runs) // a whole set, the one that stays first
        assertEquals(listOf(listOf(10L, 11L, 12L, 13L)), blurry.runs) // one row of four
    }

    @Test fun keptItemsLeaveEveryCategoryButAnOriginalStillStands() {
        val r = result(
            copies = listOf(ShotGroup(1, listOf(1L, 2L))),
            similar = listOf(ShotGroup(3, listOf(3L, 1L, 4L, 5L))), // 1 is an original and one of these shots
            blurry = listOf(6L, 7L), whatsApp = listOf(8L),
        )
        val k = r.without(setOf(2L, 3L, 5L, 6L, 8L)) // 3, the best shot, stays whatever: keeping it changes nothing
        assertEquals(emptyList<Long>(), k.candidates(CleanupKind.Copies))
        assertEquals(listOf(ShotGroup(3, listOf(3L, 1L, 4L))), k.similar)
        assertEquals(listOf(4L), k.candidates(CleanupKind.Similar)) // 1 has no copies left to suggest, but is still not suggested
        assertEquals(listOf(7L), k.blurry)
        assertEquals(emptyList<Long>(), k.whatsApp)
        assertEquals(r, r.without(emptySet()))
    }

    @Test fun foldersBelongToTheirApps() {
        assertEquals(MediaApp.WhatsApp, MediaApp.of("WhatsApp Images"))
        assertEquals(MediaApp.WhatsAppBusiness, MediaApp.of("WhatsApp Business Images"))
        assertEquals(MediaApp.Telegram, MediaApp.of("Telegram Images"))
        assertEquals(MediaApp.Instagram, MediaApp.of("Instagram"))
        assertNull(MediaApp.of("Camera"))
        assertEquals(MediaApp.WhatsApp, CleanupKind.WhatsApp.app)
    }

    @Test fun topFoldersBySizeTellSameNamesApart() {
        val shots = listOf(
            shot(1, size = 10, path = "DCIM/Camera/"), shot(2, size = 10, path = "DCIM/Camera/"),
            shot(3, size = 50, path = "Pictures/Camera/"), shot(4, size = 5, path = "Download/", bucket = "Download"),
        )
        assertEquals(listOf(FolderSize("Camera", 1, 50), FolderSize("Camera", 2, 20)), topFolders(shots, 2))
    }

    // --- Finding copies at scale

    @Test fun copyIndexFindsTurnedCopiesAndScales() {
        val index = CopyIndex()
        val turns = Random(51).let { r -> LongArray(8 * PDQ_LONGS) { r.nextLong() } }
        index.add(1, turns.copyOfRange(3 * PDQ_LONGS, 4 * PDQ_LONGS)) // stored the way it was saved: turned
        assertEquals(listOf(CopyPair(1, 2, 0)), index.copiesOf(2, near(turns, 7))) // its fourth turn is the stored one exactly
        assertNull(index.copiesOf(1, turns).firstOrNull()) // never a copy of itself

        // Every hash up to COPY_DISTANCE away is found, wherever the differing bits fall, and nothing further
        val r = Random(53)
        val base = random(54)
        val many = CopyIndex()
        val apart = IntArray(2000) { r.nextInt(48) }
        for ((k, d) in apart.withIndex()) {
            val h = base.copyOf()
            (0 until 256).shuffled(r).take(d).forEach { b -> h[b / 64] = h[b / 64] xor (1L shl (b % 64)) }
            many.add(k.toLong() + 10, h)
        }
        val found = many.copiesOf(0, base).associate { it.b to it.distance }
        assertEquals(apart.withIndex().filter { it.value <= COPY_DISTANCE }.associate { it.index.toLong() + 10 to it.value }, found)

        // A first pass over 20,000 photos: each compared with all measured before it, in all eight turns
        val n = 20_000
        val hashes = Array(n) { LongArray(8 * PDQ_LONGS) { r.nextLong() } }
        val big = CopyIndex()
        val started = System.nanoTime()
        var wrong = 0
        for (i in 0 until n) {
            wrong += big.copiesOf(i.toLong(), hashes[i]).size
            big.add(i.toLong(), hashes[i])
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("copy search, first pass over $n random photos: %.2f s (%.0f µs a photo against all before it), %d false matches".format(seconds, seconds * 1e6 / n, wrong))
        assertEquals(0, wrong) // random hashes are ~128 bits apart, nowhere near 31
    }
}
