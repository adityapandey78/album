package com.aditya.album

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
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

/** A named group of albums: "Trips" holding "Goa 2024" and "Manali". One level deep, so a group never holds a group. */
@Entity(tableName = "album_groups")
data class AlbumGroup(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val created: Long)

/**
 * An album in a group, by the key its pin is saved under ([folderKey] or [collectionKey]). The key is the primary key,
 * so an album is in at most one group. No files move: a folder stays where it is on the phone.
 */
@Entity(
    tableName = "group_members",
    foreignKeys = [ForeignKey(AlbumGroup::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")],
)
data class GroupMember(@PrimaryKey val albumKey: String, val groupId: Long, val added: Long)

/** The photo the user picked as the cover of an album, collection or group ([groupKey]), by MediaStore id. No row: Album picks. */
@Entity(tableName = "album_covers")
data class AlbumCover(@PrimaryKey val albumKey: String, val mediaId: Long)

/**
 * A date or place the user fixed for one photo or video. Kept here as well as in the file, where the file can take it,
 * because MediaStore recomputes its date whenever it rescans a file, and the smart index is a cache that can be rebuilt.
 * [taken] (epoch millis) is null when the date wasn't fixed. [placeFixed] says the place was, to [place] at [lat], [lng],
 * or removed when [place] is null. [modified] is the file's DATE_MODIFIED (seconds) right after the fix: once another
 * app changes the file, it no longer matches and the file's own details count again.
 */
@Entity(tableName = "media_fixes")
data class MediaFix(
    @PrimaryKey val mediaId: Long,
    val modified: Long,
    val taken: Long? = null,
    val placeFixed: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    @Embedded(prefix = "place_") val place: Place? = null,
)

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
    suspend fun deleteRow(id: Long)

    /** Deletes collection [id] with its items, its place in a group and its cover. A group it leaves empty goes too. */
    @Transaction
    suspend fun delete(id: Long) {
        deleteRow(id)
        val key = collectionKey(id)
        dropMembers(listOf(key))
        dropCover(key)
        dropEmptyGroups()
    }

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

    // ---------- Album groups ----------

    @Query("SELECT * FROM album_groups")
    fun groups(): Flow<List<AlbumGroup>>

    @Query("SELECT * FROM group_members")
    fun members(): Flow<List<GroupMember>>

    @Query("SELECT * FROM album_groups WHERE id = :id")
    suspend fun group(id: Long): AlbumGroup?

    @Query("SELECT * FROM group_members WHERE groupId = :id")
    suspend fun membersOf(id: Long): List<GroupMember>

    @Insert
    suspend fun insert(group: AlbumGroup): Long

    /** An album already in another group leaves it: the key is the primary key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMembers(members: List<GroupMember>)

    /** For Undo: an album the user has put in another group since stays there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun keepMembers(members: List<GroupMember>)

    @Query("DELETE FROM group_members WHERE albumKey IN (:keys)")
    suspend fun dropMembers(keys: List<String>)

    @Query("UPDATE album_groups SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: Long, name: String)

    /** Its members go too (ON DELETE CASCADE). The albums themselves are never touched. */
    @Query("DELETE FROM album_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("SELECT id FROM album_groups WHERE id NOT IN (SELECT groupId FROM group_members)")
    suspend fun emptyGroups(): List<Long>

    /**
     * Deletes groups with no albums left, and their covers, and returns their ids. Only a group's last album leaving or
     * being deleted empties it. A folder that's only out of sight (an SD card taken out) keeps its row, so its group stays.
     */
    @Transaction
    suspend fun dropEmptyGroups(): List<Long> = emptyGroups().onEach {
        deleteGroup(it)
        dropCover(groupKey(it))
    }

    @Query("SELECT * FROM group_members WHERE albumKey = :key")
    suspend fun memberOf(key: String): GroupMember?

    /** The group album [key] is in, as it is now, so a move can be undone. Null when it's in none. */
    @Transaction
    suspend fun snapshotOf(key: String): GroupSnapshot? =
        memberOf(key)?.let { m -> group(m.groupId)?.let { GroupSnapshot(it, membersOf(it.id), cover(groupKey(it.id))) } }

    /** A new group called [name] holding album [key], taken out of any group it was in. */
    @Transaction
    suspend fun newGroup(name: String, key: String, now: Long): GroupMove {
        val before = snapshotOf(key)
        val id = insert(AlbumGroup(name = name, created = now))
        putMembers(listOf(GroupMember(key, id, now)))
        return GroupMove(before, dropEmptyGroups(), id)
    }

    /** A new group called [name] holding albums [keys], each taken out of any group it was in. Returns its id. */
    @Transaction
    suspend fun newGroupOf(name: String, keys: List<String>, now: Long): Long {
        val id = insert(AlbumGroup(name = name, created = now))
        putMembers(keys.map { GroupMember(it, id, now) })
        dropEmptyGroups() // a group whose last album came over
        return id
    }

    /** Moves albums [keys] into group [id], each out of any group it was in. Nothing happens if the group is gone. */
    @Transaction
    suspend fun addToGroup(keys: List<String>, id: Long, now: Long) {
        if (group(id) == null) return
        putMembers(keys.map { GroupMember(it, id, now) })
        dropEmptyGroups()
    }

    /** Moves album [key] into group [id], or out of its group when [id] is null. */
    @Transaction
    suspend fun moveToGroup(key: String, id: Long?, now: Long): GroupMove {
        val before = snapshotOf(key)
        if (id == null) dropMembers(listOf(key)) else if (group(id) != null) putMembers(listOf(GroupMember(key, id, now)))
        return GroupMove(before, dropEmptyGroups())
    }

    /**
     * Undo for a move: album [key] goes back to the group it was in ([before], brought back whole if the move emptied it)
     * or back on its own. A group the move made, now empty, goes again.
     */
    @Transaction
    suspend fun moveBack(key: String, before: GroupSnapshot?) {
        if (before == null) dropMembers(listOf(key)) else regroup(before, listOf(key))
        dropEmptyGroups()
    }

    /** Deletes group [id] and returns what it was, for Undo. Its albums go back to being on their own. */
    @Transaction
    suspend fun ungroup(id: Long): GroupSnapshot? {
        val group = group(id) ?: return null
        val snapshot = GroupSnapshot(group, membersOf(id), cover(groupKey(id)))
        deleteGroup(id)
        dropCover(groupKey(id))
        return snapshot
    }

    /**
     * Puts group [s] back as it was: its id, name, cover and albums. An album put in another group since stays there,
     * except [force], which come back. Ids are never reused (AUTOINCREMENT), so the old id is free and its pin still fits.
     */
    @Transaction
    suspend fun regroup(s: GroupSnapshot, force: List<String>) {
        if (group(s.group.id) == null) insert(s.group)
        keepMembers(s.members)
        putMembers(s.members.filter { it.albumKey in force })
        s.cover?.let { putCover(it) }
    }

    /** Undo for [ungroup]. */
    @Transaction
    suspend fun undoUngroup(s: GroupSnapshot) {
        regroup(s, emptyList())
        dropEmptyGroups() // every album went to another group meanwhile
    }

    /** A folder renamed from [old] to [new] (both [folderKey]s) keeps its group and cover. */
    @Transaction
    suspend fun rekey(old: String, new: String) {
        renameMember(old, new)
        renameCover(old, new)
    }

    @Query("UPDATE OR REPLACE group_members SET albumKey = :new WHERE albumKey = :old")
    suspend fun renameMember(old: String, new: String)

    // ---------- Covers ----------

    @Query("SELECT * FROM album_covers")
    fun covers(): Flow<List<AlbumCover>>

    @Query("SELECT * FROM album_covers WHERE albumKey = :key")
    suspend fun cover(key: String): AlbumCover?

    @Upsert
    suspend fun putCover(cover: AlbumCover)

    @Query("DELETE FROM album_covers WHERE albumKey = :key")
    suspend fun dropCover(key: String)

    @Query("UPDATE OR REPLACE album_covers SET albumKey = :new WHERE albumKey = :old")
    suspend fun renameCover(old: String, new: String)

    // ---------- Fixed dates and places ----------

    @Query("SELECT * FROM media_fixes")
    fun fixes(): Flow<List<MediaFix>>

    @Query("SELECT * FROM media_fixes")
    suspend fun allFixes(): List<MediaFix>

    @Query("SELECT * FROM media_fixes WHERE mediaId IN (:ids)")
    suspend fun fixesOf(ids: List<Long>): List<MediaFix>

    @Upsert
    suspend fun putFixes(fixes: List<MediaFix>)

    @Query("DELETE FROM media_fixes WHERE mediaId IN (:ids)")
    suspend fun dropFixes(ids: List<Long>)

    /** The rows of [ids] before a fix, in chunks for SQLite's variable limit. */
    @Transaction
    suspend fun fixesBefore(ids: List<Long>): List<MediaFix> = ids.chunked(CHUNK).flatMap { fixesOf(it) }

    /** Undo for a fix: the rows of [ids] become [before] again (a photo without one loses its row). */
    @Transaction
    suspend fun restoreFixes(ids: List<Long>, before: List<MediaFix>) {
        ids.chunked(CHUNK).forEach { dropFixes(it) }
        putFixes(before)
    }
}

/** A group as it was before a change, for Undo. */
class GroupSnapshot(val group: AlbumGroup, val members: List<GroupMember>, val cover: AlbumCover?)

/** What moving an album changed: the group it was in before ([before], for Undo), groups it left empty and deleted, and the group it made. */
class GroupMove(val before: GroupSnapshot?, val emptied: List<Long>, val made: Long? = null)

/**
 * Collections are user data, unlike the smart index: never add fallbackToDestructiveMigration. Without a migration,
 * Room refuses to open a newer version (a crash on launch) rather than silently dropping everyone's collections.
 * To change the schema: bump the version, keep the exported app/schemas/com.aditya.album.CollectionsDb/N.json files in git,
 * and add `autoMigrations = [AutoMigration(from = N, to = N + 1)]` (or a hand-written Migration for renames and splits).
 * Version 2 (1.1.1) adds album groups, covers and fixed dates and places as new tables, leaving the old ones untouched.
 */
@Database(
    entities = [MediaCollection::class, CollectionItem::class, AlbumGroup::class, GroupMember::class, AlbumCover::class, MediaFix::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class CollectionsDb : RoomDatabase() {
    abstract fun dao(): CollectionsDao
}
