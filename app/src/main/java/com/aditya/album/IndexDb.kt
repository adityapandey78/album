package com.aditya.album

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * What the smart index knows about one photo or video, keyed by its MediaStore id.
 * Later analyses (faces, text in photos, embeddings) get their own tables keyed by mediaId, like [ItemLabel].
 */
// Explore and search look items up by category and by place
@Entity(tableName = "items", indices = [Index("category"), Index("city", "admin1", "countryCode")])
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
    // The place came from photos with GPS taken around the same time (estimatePlaces), not from this file's own GPS.
    // Only ever stored here: nothing is written to the file.
    @ColumnInfo(defaultValue = "0") val estimated: Boolean = false,
)

/** ML Kit label with its confidence (0 to 1). Kept for search; [IndexedItem.category] is derived from these. */
@Entity(
    tableName = "labels",
    primaryKeys = ["mediaId", "label"],
    foreignKeys = [ForeignKey(IndexedItem::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("label")],
)
data class ItemLabel(val mediaId: Long, val label: String, val confidence: Float)

/**
 * Text read from a photo (ML Kit text recognition), full-text indexed for search. Its rowid is the MediaStore id.
 * FTS tables can't have foreign keys, so rows are deleted alongside their item ([IndexDao.save], [IndexDao.remove]).
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61) // splits on any script's punctuation and ignores case and accents
@Entity(tableName = "texts")
data class ItemText(@PrimaryKey @ColumnInfo(name = "rowid") val mediaId: Long, val text: String)

/** Where an item was taken, for naming its place again. */
data class ItemGps(val id: Long, val lat: Double, val lng: Double)

/** An item's place as the index has it: from its own GPS ([gps]) or [estimated] from other photos, or neither. */
data class ItemPlace(val id: Long, val gps: Boolean, val estimated: Boolean, @Embedded val place: Place?)

/** All the index says about where an item is, so Edit place can set it, and put it back on Undo. */
data class ItemSpot(val id: Long, val lat: Double?, val lng: Double?, val estimated: Boolean, @Embedded val place: Place?)

/** A photo a search reading found, and how sure the index is of it (an ML Kit confidence, 0 to 1). */
data class Scored(val id: Long, val score: Float?)

/** The columns that decide whether an item needs analysing again. */
data class IndexStamp(val id: Long, val modified: Long, val size: Long, val version: Int, val located: Boolean)

data class CategoryCount(val category: Category, val count: Int)

/** A city-level place ([Place.locality] and [Place.district] empty, so it's the same key however its photos spread) and its photo count. */
data class PlaceCount(@Embedded val place: Place, val count: Int)

@Dao
interface IndexDao {
    @Query("SELECT id, modified, size, version, located FROM items")
    suspend fun stamps(): List<IndexStamp>

    /** Replaces the rows, labels and text of [items] in one transaction, so a stopped run never leaves half an item. */
    @Transaction
    suspend fun save(items: List<IndexedItem>, labels: List<ItemLabel>, texts: List<ItemText>) {
        val ids = items.map { it.id }
        deleteLabels(ids)
        deleteTexts(ids)
        upsert(items)
        insert(labels)
        insertTexts(texts)
    }

    @Upsert
    suspend fun upsert(items: List<IndexedItem>)

    @Insert
    suspend fun insert(labels: List<ItemLabel>)

    @Insert
    suspend fun insertTexts(texts: List<ItemText>)

    @Query("DELETE FROM labels WHERE mediaId IN (:ids)")
    suspend fun deleteLabels(ids: List<Long>)

    @Query("DELETE FROM texts WHERE rowid IN (:ids)")
    suspend fun deleteTexts(ids: List<Long>)

    /** Labels go too (ON DELETE CASCADE). */
    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Forgets media that's gone. */
    @Transaction
    suspend fun remove(ids: List<Long>) {
        deleteTexts(ids)
        delete(ids)
    }

    @Query("SELECT id, lat, lng FROM items WHERE lat IS NOT NULL AND lng IS NOT NULL")
    suspend fun located(): List<ItemGps>

    /** The same points, live, for the map. */
    @Query("SELECT id, lat, lng FROM items WHERE lat IS NOT NULL AND lng IS NOT NULL")
    fun gps(): Flow<List<ItemGps>>

    @Query("SELECT id, lat IS NOT NULL AS gps, estimated, city, admin1, countryCode, country, locality, district FROM items")
    suspend fun itemPlaces(): List<ItemPlace>

    @Query(
        "UPDATE items SET city = :city, admin1 = :admin1, countryCode = :countryCode, country = :country, locality = :locality, " +
            "district = :district, estimated = :estimated WHERE id = :id",
    )
    suspend fun setPlace(id: Long, city: String?, admin1: String?, countryCode: String?, country: String?, locality: String?, district: String?, estimated: Boolean)

    /** Names the places of [places] (null: no place), all from their own GPS, or all [estimated]. */
    @Transaction
    suspend fun setPlaces(places: Map<Long, Place?>, estimated: Boolean = false) = places.forEach { (id, p) ->
        setPlace(id, p?.city, p?.admin1, p?.countryCode, p?.country, p?.locality, p?.district, estimated && p != null)
    }

    @Query("SELECT id, lat, lng, estimated, city, admin1, countryCode, country, locality, district FROM items WHERE id IN (:ids)")
    suspend fun spots(ids: List<Long>): List<ItemSpot>

    @Query(
        "UPDATE items SET lat = :lat, lng = :lng, estimated = :estimated, city = :city, admin1 = :admin1, countryCode = :countryCode, " +
            "country = :country, locality = :locality, district = :district WHERE id = :id",
    )
    suspend fun setSpot(
        id: Long, lat: Double?, lng: Double?, estimated: Boolean,
        city: String?, admin1: String?, countryCode: String?, country: String?, locality: String?, district: String?,
    )

    @Transaction
    suspend fun setSpots(spots: List<ItemSpot>) = spots.forEach { s ->
        val p = s.place
        setSpot(s.id, s.lat, s.lng, s.estimated, p?.city, p?.admin1, p?.countryCode, p?.country, p?.locality, p?.district)
    }

    /** Where photos in a city were taken, from their own GPS (or a place the user set with a spot). */
    @Query("SELECT id, lat, lng FROM items WHERE city = :city AND admin1 = :admin1 AND countryCode = :countryCode AND lat IS NOT NULL AND lng IS NOT NULL")
    suspend fun gpsIn(city: String, admin1: String, countryCode: String): List<ItemGps>

    @Query("SELECT category, COUNT(*) AS count FROM items WHERE category IS NOT NULL GROUP BY category ORDER BY count DESC")
    fun categories(): Flow<List<CategoryCount>>

    @Query(
        "SELECT city, admin1, countryCode, country, '' AS locality, '' AS district, COUNT(*) AS count FROM items " +
            "WHERE city IS NOT NULL GROUP BY city, admin1, countryCode ORDER BY count DESC",
    )
    fun places(): Flow<List<PlaceCount>>

    @Query("SELECT id FROM items WHERE category = :category")
    fun ids(category: Category): Flow<List<Long>>

    @Query("SELECT id FROM items WHERE city = :city AND admin1 = :admin1 AND countryCode = :countryCode")
    fun ids(city: String, admin1: String, countryCode: String): Flow<List<Long>>

    @Query("SELECT id FROM items WHERE estimated = 1")
    fun estimated(): Flow<List<Long>>

    @Query("SELECT id, lat IS NOT NULL AS gps, estimated, city, admin1, countryCode, country, locality, district FROM items WHERE id = :id")
    fun place(id: Long): ItemPlace?

    // Search: the names it can match, then blocking lookups for the ones it did (called off the main thread).

    @Query("SELECT DISTINCT label FROM labels")
    fun labels(): Flow<List<String>>

    /** Every place name at every level: localities, cities, districts, states and countries. */
    @Query(
        "SELECT locality FROM items WHERE locality != '' UNION SELECT city FROM items WHERE city != '' " +
            "UNION SELECT district FROM items WHERE district != '' UNION SELECT admin1 FROM items WHERE admin1 != '' " +
            "UNION SELECT country FROM items WHERE country != ''",
    )
    fun placeNames(): Flow<List<String>>

    /** A category's photos, each with its strongest label of [labels] (the ones the category is made from), or null. */
    @Query(
        "SELECT items.id AS id, MAX(labels.confidence) AS score FROM items " +
            "LEFT JOIN labels ON labels.mediaId = items.id AND labels.label IN (:labels) WHERE items.category = :category GROUP BY items.id",
    )
    fun scoredIn(category: Category, labels: List<String>): List<Scored>

    @Query("SELECT mediaId AS id, confidence AS score FROM labels WHERE label = :label")
    fun scoredWith(label: String): List<Scored>

    /** By locality, city, district, state or country name. */
    @Query("SELECT id FROM items WHERE locality = :name OR city = :name OR district = :name OR admin1 = :name OR country = :name")
    fun idsAt(name: String): List<Long>

    /** [query] is FTS syntax, like `invoice*` for words starting with "invoice" or `"tax invoice"` for those words in a row. */
    @Query("SELECT rowid FROM texts WHERE texts MATCH :query")
    fun idsWithText(query: String): List<Long>

    /** The text read from one item, or null when it had none. */
    @Query("SELECT text FROM texts WHERE rowid = :id")
    fun text(id: Long): String?
}

// Schemas are exported to app/schemas so new tables and columns ship as Room auto-migrations instead of a full re-index.
// Version 2 added texts without one: existing photos needed text read anyway, so that index was dropped and rebuilt.
// Version 3 adds place levels (locality, district) and estimated places as columns, keeping every row.
@Database(
    entities = [IndexedItem::class, ItemLabel::class, ItemText::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 2, to = 3, spec = IndexDb.PlaceLevels::class)],
)
abstract class IndexDb : RoomDatabase() {
    abstract fun dao(): IndexDao

    /**
     * Saved places get empty levels rather than none, so every row's place is either all there or all null (Room can't
     * read half a place). The next indexing pass names them again with their levels.
     */
    class PlaceLevels : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE items SET locality = '', district = '' WHERE city IS NOT NULL")
        }
    }
}
