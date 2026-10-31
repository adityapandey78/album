package com.aditya.album

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeDbTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A safe.db as v1.1.1 left it (version 1, built with the SQL Room exported in app/schemas/.../SafeDb/1.json), opened by
     * the version with ID cards: every item, moved or still waiting, survives in Private safe, and ID cards start empty.
     */
    @Test fun version1UpgradesWithEverythingInThePrivateSafe() = runBlocking {
        val name = "safe-upgrade-1-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v1 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `isVideo` INTEGER NOT NULL, `taken` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `path` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `size` INTEGER NOT NULL, `sha256` TEXT, `original` TEXT, `added` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'c338a86d82c9773ab239c2f35c76134c')",
                "INSERT INTO items VALUES (1, 'aadhaar.jpg', 'image/jpeg', 0, 200, 4000, 3000, 0, 'DCIM/Camera/', 1, 1234, 'ab', NULL, 300)",
                "INSERT INTO items VALUES (2, 'trip.mp4', 'video/mp4', 1, 100, 1920, 1080, 5000, 'DCIM/Camera/', 0, 0, NULL, 'content://media/external/video/media/7', 400)",
            ).forEach(v1::execSQL)
            v1.version = 1
        }
        val upgraded = Room.databaseBuilder(context, SafeDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            val safe = dao.items(SafeItem.SAFE).first()
            assertEquals(listOf(1L), safe.map { it.id })
            assertEquals(listOf("aadhaar.jpg", "DCIM/Camera/", "ab"), safe.single().let { listOf(it.name, it.path, it.sha256) })
            assertEquals(true, safe.single().favorite)
            assertEquals(listOf(2L), dao.waiting().map { it.id }) // a move the app was closed in the middle of, still finished later
            assertEquals(SafeItem.SAFE, dao.waiting().single().bucket)
            assertEquals(emptyList<SafeItem>(), dao.items(SafeItem.ID_CARDS).first())
            assertEquals(1, dao.count(SafeItem.SAFE))
            assertEquals(0, dao.count(SafeItem.ID_CARDS))
            // The new column works on the upgraded file, and each bucket's reset leaves the other alone
            val card = dao.insert(SafeItem(name = "pan.jpg", mime = "image/jpeg", isVideo = false, taken = 500, width = 800, height = 500, durationMs = 0, path = "Download/", favorite = false, size = 10, sha256 = "cd", original = null, added = 600, bucket = SafeItem.ID_CARDS))
            assertEquals(listOf(card), dao.items(SafeItem.ID_CARDS).first().map { it.id })
            assertEquals(listOf(1L), dao.items(SafeItem.SAFE).first().map { it.id })
            dao.clear(SafeItem.SAFE)
            assertEquals(listOf(card), dao.items(SafeItem.ID_CARDS).first().map { it.id })
            assertEquals(0, dao.count(SafeItem.SAFE))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A safe.db with ID cards (version 2, as app/schemas/.../SafeDb/2.json made it), opened by the version that seals moves
     * in: every item keeps its bucket and opens with the safe key as before (no sealed key), and a sealed one goes in.
     */
    @Test fun version2UpgradesWithNothingSealed() = runBlocking {
        val name = "safe-upgrade-2-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v2 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `isVideo` INTEGER NOT NULL, `taken` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `path` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `size` INTEGER NOT NULL, `sha256` TEXT, `original` TEXT, `added` INTEGER NOT NULL, `bucket` INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '1afc2f7ebef9699b96e0b347b6fdf06e')",
                "INSERT INTO items VALUES (1, 'trip.jpg', 'image/jpeg', 0, 200, 4000, 3000, 0, 'DCIM/Camera/', 1, 1234, 'ab', NULL, 300, 0)",
                "INSERT INTO items VALUES (2, 'pan.jpg', 'image/jpeg', 0, 100, 800, 500, 0, 'Download/', 0, 10, 'cd', NULL, 400, 1)",
                "INSERT INTO items VALUES (3, 'clip.mp4', 'video/mp4', 1, 50, 1920, 1080, 5000, 'DCIM/Camera/', 0, 0, NULL, 'content://media/external/video/media/7', 500, 0)",
            ).forEach(v2::execSQL)
            v2.version = 2
        }
        val upgraded = Room.databaseBuilder(context, SafeDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            assertEquals(listOf(1L to null), dao.items(SafeItem.SAFE).first().map { it.id to it.sealedKey })
            assertEquals(listOf(2L to null), dao.items(SafeItem.ID_CARDS).first().map { it.id to it.sealedKey })
            assertEquals(listOf(3L to null), dao.waiting().map { it.id to it.sealedKey })
            val sealed = dao.insert(SafeItem(name = "new.jpg", mime = "image/jpeg", isVideo = false, taken = 600, width = 10, height = 10, durationMs = 0, path = "DCIM/Camera/", favorite = false, size = 0, sha256 = null, original = null, added = 700, sealedKey = "c2VhbGVk"))
            assertEquals("c2VhbGVk", dao.items(SafeItem.SAFE).first().single { it.id == sealed }.sealedKey)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A safe.db with sealed keys (version 3, as app/schemas/.../SafeDb/3.json made it), opened by the version with the
     * list's index: every item stays, and the list reads through the index instead of sorting every row, which Android
     * redoes for each 2 MB window of a long result (a 20,000 item safe took over half a second).
     */
    @Test fun version3UpgradesWithTheListIndex() = runBlocking {
        val name = "safe-upgrade-3-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v3 ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `isVideo` INTEGER NOT NULL, `taken` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `path` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `size` INTEGER NOT NULL, `sha256` TEXT, `original` TEXT, `added` INTEGER NOT NULL, `bucket` INTEGER NOT NULL DEFAULT 0, `sealedKey` TEXT)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '5bccf7d482c5259f2851e099417b6476')",
                "INSERT INTO items VALUES (1, 'old.jpg', 'image/jpeg', 0, 100, 4000, 3000, 0, 'DCIM/Camera/', 0, 1234, 'ab', NULL, 300, 0, NULL)",
                "INSERT INTO items VALUES (2, 'new.jpg', 'image/jpeg', 0, 200, 4000, 3000, 0, 'DCIM/Camera/', 0, 1234, 'cd', NULL, 400, 0, 'c2VhbGVk')",
                "INSERT INTO items VALUES (3, 'pan.jpg', 'image/jpeg', 0, 300, 800, 500, 0, 'Download/', 0, 10, 'ef', NULL, 500, 1, NULL)",
            ).forEach(v3::execSQL)
            v3.version = 3
        }
        val upgraded = Room.databaseBuilder(context, SafeDb::class.java, name).build()
        try {
            val dao = upgraded.dao()
            assertEquals(listOf(2L to "c2VhbGVk", 1L to null), dao.items(SafeItem.SAFE).first().map { it.id to it.sealedKey }) // newest first
            assertEquals(listOf(3L), dao.items(SafeItem.ID_CARDS).first().map { it.id })
            val plan = upgraded.openHelper.readableDatabase
                .query("EXPLAIN QUERY PLAN SELECT * FROM items WHERE original IS NULL AND bucket = 0 ORDER BY taken DESC")
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }
                .joinToString()
            assertTrue(plan, plan.contains("index_items_bucket_taken") && !plan.contains("TEMP B-TREE"))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /** The list read in pages is the list in one query: newest first, ties by id, across page edges, and the waiting left out. */
    @Test fun pagesReadTheWholeListInOrder() = runBlocking {
        val name = "safe-pages-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, SafeDb::class.java, name).build()
        try {
            val dao = db.dao()
            fun item(taken: Long, original: String? = null, bucket: Int = SafeItem.SAFE) = SafeItem(
                name = "$taken.jpg", mime = "image/jpeg", isVideo = false, taken = taken, width = 1, height = 1, durationMs = 0,
                path = "DCIM/Camera/", favorite = false, size = 1, sha256 = "ab", original = original, added = 0, bucket = bucket,
            )
            // Ties on taken straddle the page edges (pages of 2); one waiting and one ID card stay out
            val ids = listOf(5L, 5L, 5L, 4L, 9L, 1L, 4L).map { dao.insert(item(it)) }
            dao.insert(item(7, original = "content://media/external/images/media/1"))
            dao.insert(item(8, bucket = SafeItem.ID_CARDS))
            val expected = ids.zip(listOf(5L, 5L, 5L, 4L, 9L, 1L, 4L)).sortedWith(compareByDescending<Pair<Long, Long>> { it.second }.thenByDescending { it.first }).map { it.first }
            assertEquals(expected, dao.all(SafeItem.SAFE, size = 2).map { it.id })
            assertEquals(expected, dao.all(SafeItem.SAFE, size = 7).map { it.id }) // a last page that's exactly full
            assertEquals(dao.items(SafeItem.SAFE).first().map { it.id }.toSet(), expected.toSet())
            assertEquals(emptyList<Long>(), dao.all(SafeItem.SAFE + 5).map { it.id })
            val plan = db.openHelper.readableDatabase
                .query("EXPLAIN QUERY PLAN SELECT * FROM items WHERE original IS NULL AND bucket = 0 AND taken <= 5 AND (taken < 5 OR id < 3) ORDER BY taken DESC, id DESC LIMIT 2")
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }
                .joinToString()
            assertTrue(plan, plan.contains("index_items_bucket_taken (bucket=? AND taken<?)") && !plan.contains("TEMP B-TREE"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
