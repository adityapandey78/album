package com.aditya.album

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
