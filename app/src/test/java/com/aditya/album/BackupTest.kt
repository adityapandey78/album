package com.aditya.album

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class BackupTest {
    private fun item(id: Long, size: Long = 100, modified: Long = 1, volume: String = "external_primary", path: String = "DCIM/Camera/", name: String = "IMG_$id.jpg") =
        BackupSource(id, isVideo = false, size, modified, volume, path, name)

    private fun stamp(id: Long, size: Long = 100, modified: Long = 1) = CopyStamp(id, size, modified, "hash$id")

    @Test fun plansNewAndChangedItemsOnly() {
        val found = listOf(item(1), item(2), item(3, size = 120), item(4, modified = 9))
        val plan = planBackup(found, listOf(stamp(2), stamp(3), stamp(4), stamp(99)))
        assertEquals(listOf(1L, 3L, 4L), plan.map { it.first.id }) // 2 is unchanged; 99's photo is gone, which copies nothing
        assertEquals(null, plan[0].second) // new
        assertEquals(stamp(3), plan[1].second) // changed: its old copy's stamp comes along for the hash check
    }

    @Test fun aLostCacheMeansEverythingIsCheckedAgain() {
        val found = (1L..5).map { item(it) }
        assertEquals(found, planBackup(found, emptyList()).map { it.first })
    }

    @Test fun leavesOutHiddenItemsUnlessChosen() {
        val h = HiddenState(folders = setOf("pictures/secret/"), personPhotos = setOf(7))
        val secret = item(1, path = "Pictures/Secret/")
        val person = item(7)
        val plain = item(2)
        val all = BackupChoice()
        assertFalse(backsUp(secret, all, h, "", ""))
        assertFalse(backsUp(person, all, h, "", ""))
        assertTrue(backsUp(plain, all, h, "", ""))
        assertTrue(backsUp(secret, all.copy(hidden = true), h, "", ""))
        assertTrue(backsUp(person, all.copy(hidden = true), h, "", ""))
        // Hidden items come whole, whichever albums are chosen
        assertTrue(backsUp(person, BackupChoice(albums = setOf("pictures/trip/"), hidden = true), h, "", ""))
    }

    @Test fun onlyChosenAlbums() {
        val choice = BackupChoice(albums = setOf("dcim/camera/"))
        assertTrue(backsUp(item(1, path = "DCIM/Camera/"), choice, HiddenState(), "", ""))
        assertFalse(backsUp(item(2, path = "Pictures/WhatsApp Images/"), choice, HiddenState(), "", ""))
        assertFalse(backsUp(item(3), BackupChoice(albums = emptySet()), HiddenState(), "", ""))
    }

    @Test fun neverBacksUpItsOwnCopies() {
        val own = ownPath("1234-ABCD:Backups/Album backup")
        assertEquals("Backups/Album backup/", own)
        assertFalse(backsUp(item(1, volume = "1234-abcd", path = "Backups/Album backup/DCIM/Camera/"), BackupChoice(), HiddenState(), "1234-abcd", own))
        assertFalse(backsUp(item(1, volume = "1234-abcd", path = "backups/album backup/"), BackupChoice(), HiddenState(), "1234-abcd", own))
        // The same folders elsewhere, or a folder that only starts the same, are the user's own photos
        assertTrue(backsUp(item(2, volume = "external_primary", path = "Backups/Album backup/"), BackupChoice(), HiddenState(), "1234-abcd", own))
        assertTrue(backsUp(item(3, volume = "1234-abcd", path = "Backups/Album backup 2/"), BackupChoice(), HiddenState(), "1234-abcd", own))
        assertTrue(backsUp(item(4, volume = "1234-abcd", path = "DCIM/Camera/"), BackupChoice(), HiddenState(), "1234-abcd", own))
        // A whole drive picked as the backup folder: nothing on that drive is the Library's to back up
        assertEquals("", ownPath("1234-ABCD:"))
        assertFalse(backsUp(item(5, volume = "1234-abcd", path = "DCIM/Camera/"), BackupChoice(), HiddenState(), "1234-abcd", ownPath("1234-ABCD:")))
    }

    @Test fun mapsFoldersAndNamesAsFatDrivesTakeThem() {
        assertEquals(listOf("DCIM", "Camera"), backupDirs("DCIM/Camera/"))
        assertEquals(listOf("Pictures", "WhatsApp Images"), backupDirs("Pictures/WhatsApp Images/"))
        assertEquals(emptyList<String>(), backupDirs(""))
        assertEquals(listOf("Pictures", "_", "_", "_", "a_b"), backupDirs("Pictures/../././a:b/"))
        assertEquals("IMG_20241024_183012.jpg", fatName("IMG_20241024_183012.jpg"))
        assertEquals("WhatsApp Image 2024-03-15 at 6.30.12 PM.jpeg", fatName("WhatsApp Image 2024-03-15 at 6.30.12 PM.jpeg"))
        assertEquals("a_b_c_d_.jpg", fatName("a:b*c?d|.jpg"))
        assertEquals("_", fatName(""))
    }

    @Test fun clashNamesFollowFreeNamesAndAndroid() {
        assertEquals(listOf("IMG_1.jpg", "IMG_1 (1).jpg", "IMG_1 (2).jpg"), clashNames("IMG_1.jpg").take(3).toList())
        assertEquals(listOf("notes", "notes (1)"), clashNames("notes").take(2).toList())
        assertEquals(listOf(".nomedia", ".nomedia (1)"), clashNames(".nomedia").take(2).toList())
        assertEquals("a.b (1).jpg", clashNames("a.b.jpg").elementAt(1))
        // A changed photo's new copy goes alongside the old under the name freeNames would give it, never over it
        val there = listOf("IMG_1.jpg", "img_1 (1).jpg")
        val free = freeNames(listOf("IMG_1.jpg"), there).single()
        assertEquals("IMG_1 (2).jpg", free)
        assertEquals(free, clashNames("IMG_1.jpg").first { n -> there.none { it.equals(n, ignoreCase = true) } })
    }

    @Test fun copiesEveryByteAndHashesWhatItWrote() = runBlocking {
        for (n in listOf(0, 1, (1 shl 20) - 1, 1 shl 20, (1 shl 20) + 1, 3 * (1 shl 20) + 7)) {
            val bytes = Random(n).nextBytes(n)
            val out = ByteArrayOutputStream()
            val (hash, size) = copyHashing(ByteArrayInputStream(bytes), out)
            assertEquals("size $n", n.toLong(), size)
            assertTrue("bytes $n", bytes.contentEquals(out.toByteArray()))
            assertEquals("hash $n", MessageDigest.getInstance("SHA-256").digest(bytes).hex(), hash)
            assertEquals("read-back $n", sha256(ByteArrayInputStream(out.toByteArray())).hex(), hash)
        }
    }

    @Test fun saysWhenItLastBackedUp() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun at(y: Int, m: Int, d: Int, h: Int = 21) = LocalDateTime.of(y, m, d, h, 4).atZone(zone).toInstant().toEpochMilli()
        val now = at(2026, 10, 30, 22)
        val clock = { _: Long -> "21:04" }
        assertEquals("today at 21:04", backedUpWhen(at(2026, 10, 30), now, zone, clock))
        assertEquals("yesterday at 21:04", backedUpWhen(at(2026, 10, 29), now, zone, clock))
        assertEquals("Sat, 24 Oct", backedUpWhen(at(2026, 10, 24), now, zone, clock))
        assertEquals("24 Oct 2025", backedUpWhen(at(2025, 10, 24), now, zone, clock))
    }
}
