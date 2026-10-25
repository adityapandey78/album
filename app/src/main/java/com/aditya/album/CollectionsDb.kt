package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A user's collection: a virtual album that lists photos without copying or moving them. */
@Entity(tableName = "collections")
data class MediaCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val created: Long, // epoch millis
    val updated: Long, // last time items were added or removed; orders the tiles, most recently used first
)

/** One photo or video in a collection, by MediaStore id. A photo can be in many collections. */
@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "mediaId"],
    foreignKeys = [ForeignKey(MediaCollection::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE)],
)
data class CollectionItem(val collectionId: Long, val mediaId: Long, val added: Long)

/** SQLite allows a limited number of `?` per statement, so long id lists go in chunks. */
private const val CHUNK = 500

@Dao
interface CollectionsDao {
    @Query("SELECT * FROM collections ORDER BY updated DESC")
    fun collections(): Flow<List<MediaCollection>>

    @Query("SELECT * FROM collection_items")
    fun items(): Flow<List<CollectionItem>>

    @Insert
    suspend fun insert(collection: MediaCollection): Long

    /** Row ids, -1 for items that were in the collection already (their original added time stays). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<CollectionItem>): List<Long>

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Items go too (ON DELETE CASCADE). The photos themselves are never touched. */
    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    /** Rows changed: 0 when the collection no longer exists. */
    @Query("UPDATE collections SET updated = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long): Int

    @Query("SELECT * FROM collection_items WHERE collectionId = :id AND mediaId IN (:mediaIds)")
    suspend fun find(id: Long, mediaIds: List<Long>): List<CollectionItem>

    @Query("DELETE FROM collection_items WHERE collectionId = :id AND mediaId IN (:mediaIds)")
    suspend fun deleteItems(id: Long, mediaIds: List<Long>)

    @Query("SELECT DISTINCT mediaId FROM collection_items")
    suspend fun mediaIds(): List<Long>

    @Query("DELETE FROM collection_items WHERE mediaId IN (:mediaIds)")
    suspend fun deleteMedia(mediaIds: List<Long>)

    /** Adds [items] to [collectionId], keeping any already there. How many were new; 0 if the collection was deleted meanwhile. */
    @Transaction
    suspend fun add(collectionId: Long, items: List<CollectionItem>, now: Long): Int {
        if (touch(collectionId, now) == 0) return 0
        return insert(items).count { it != -1L }
    }

    /** Removes [mediaIds] from [collectionId] and returns the rows removed, so Undo can put them back as they were. */
    @Transaction
    suspend fun remove(collectionId: Long, mediaIds: List<Long>, now: Long): List<CollectionItem> {
        val removed = mediaIds.chunked(CHUNK).flatMap { chunk -> find(collectionId, chunk).also { deleteItems(collectionId, chunk) } }
        if (removed.isNotEmpty()) touch(collectionId, now)
        return removed
    }

    @Transaction
    suspend fun forget(mediaIds: List<Long>) = mediaIds.chunked(CHUNK).forEach { deleteMedia(it) }
}

/**
 * Collections are user data, unlike the smart index: never add fallbackToDestructiveMigration. Without a migration,
 * Room refuses to open a newer version (a crash on launch) rather than silently dropping everyone's collections.
 * To change the schema: bump the version, keep the exported app/schemas/com.aditya.album.CollectionsDb/N.json files in git,
 * and add `autoMigrations = [AutoMigration(from = N, to = N + 1)]` (or a hand-written Migration for renames and splits).
 */
@Database(entities = [MediaCollection::class, CollectionItem::class], version = 1)
abstract class CollectionsDb : RoomDatabase() {
    abstract fun dao(): CollectionsDao
}
