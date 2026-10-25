package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * What the smart index knows about one photo or video, keyed by its MediaStore id.
 * Later analyses (faces, text in photos, embeddings) get their own tables keyed by mediaId, like [ItemLabel].
 */
@Entity(tableName = "items")
data class IndexedItem(
    @PrimaryKey val id: Long,
    val modified: Long, // MediaStore DATE_MODIFIED (seconds); with size, tells an edited file from an unchanged one
    val size: Long,
    val version: Int, // INDEX_VERSION it was analysed with; other versions are redone
    val category: Category?, // null when the file couldn't be read
    val lat: Double?,
    val lng: Double?,
    val located: Boolean, // GPS was looked for (needs ACCESS_MEDIA_LOCATION); false rows are redone once it's granted
    @Embedded val place: Place?,
)

/** ML Kit label with its confidence (0 to 1). Kept for search; [IndexedItem.category] is derived from these. */
@Entity(
    tableName = "labels",
    primaryKeys = ["mediaId", "label"],
    foreignKeys = [ForeignKey(IndexedItem::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("label")],
)
data class ItemLabel(val mediaId: Long, val label: String, val confidence: Float)

/** The columns that decide whether an item needs analysing again. */
data class IndexStamp(val id: Long, val modified: Long, val size: Long, val version: Int, val located: Boolean)

data class CategoryCount(val category: Category, val count: Int)

data class PlaceCount(@Embedded val place: Place, val count: Int)

@Dao
interface IndexDao {
    @Query("SELECT id, modified, size, version, located FROM items")
    suspend fun stamps(): List<IndexStamp>

    /** Replaces the rows and labels of [items] in one transaction, so a stopped run never leaves half an item. */
    @Transaction
    suspend fun save(items: List<IndexedItem>, labels: List<ItemLabel>) {
        deleteLabels(items.map { it.id })
        upsert(items)
        insert(labels)
    }

    @Upsert
    suspend fun upsert(items: List<IndexedItem>)

    @Insert
    suspend fun insert(labels: List<ItemLabel>)

    @Query("DELETE FROM labels WHERE mediaId IN (:ids)")
    suspend fun deleteLabels(ids: List<Long>)

    /** Labels go too (ON DELETE CASCADE). */
    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT category, COUNT(*) AS count FROM items WHERE category IS NOT NULL GROUP BY category ORDER BY count DESC")
    fun categories(): Flow<List<CategoryCount>>

    @Query(
        "SELECT city, admin1, countryCode, country, COUNT(*) AS count FROM items WHERE city IS NOT NULL " +
            "GROUP BY city, admin1, countryCode ORDER BY count DESC",
    )
    fun places(): Flow<List<PlaceCount>>

    @Query("SELECT id FROM items WHERE category = :category")
    fun ids(category: Category): Flow<List<Long>>

    @Query("SELECT id FROM items WHERE city = :city AND admin1 = :admin1 AND countryCode = :countryCode")
    fun ids(city: String, admin1: String, countryCode: String): Flow<List<Long>>
}

// Schemas are exported to app/schemas so new tables can ship as Room auto-migrations instead of a full re-index.
@Database(entities = [IndexedItem::class, ItemLabel::class], version = 1)
abstract class IndexDb : RoomDatabase() {
    abstract fun dao(): IndexDao
}
