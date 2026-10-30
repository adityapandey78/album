package com.aditya.album

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Hidden items: people and folder albums put behind the privacy PIN. Unlike Hide from Library (folders) and Hide from
// People, which only tidy a list, these leave everything that shows photos: the Library, Collections, search, For you,
// places and things, and the widget. Only Hidden items shows them, after the PIN or fingerprint. Their files stay where
// they are, so other apps still show them; Private safe is for photos nobody else should see.

/**
 * What's hidden behind the privacy PIN: [folders] by [folderKey], locked [people] by cluster id, and [personPhotos], the
 * MediaStore ids of every photo a locked person is in.
 */
data class HiddenState(val folders: Set<String> = emptySet(), val people: Set<Long> = emptySet(), val personPhotos: Set<Long> = emptySet()) {
    val isEmpty get() = folders.isEmpty() && people.isEmpty()

    /** Whether the item with MediaStore id [id], in the folder at [path], stays out of sight. */
    fun hides(id: Long, path: String) = id in personPhotos || (folders.isNotEmpty() && folderKey(path) in folders)

    /**
     * Whether album [albumId] is a locked person or a hidden folder (by its items in [media], every photo and video): a
     * page only Hidden items opens.
     */
    fun isHiddenAlbum(albumId: Long, media: List<Media>) =
        clusterOfAlbum(albumId)?.let { it in people } ?: (folders.isNotEmpty() && media.any { it.bucketId == albumId && folderKey(it.path) in folders })
}

/** [items] minus everything hidden behind the privacy PIN. Generic so it's testable without MediaStore rows. */
fun <T> withoutHidden(items: List<T>, h: HiddenState, idOf: (T) -> Long, pathOf: (T) -> String): List<T> =
    if (h.isEmpty) items else items.filterNot { h.hides(idOf(it), pathOf(it)) }

/** These items minus everything hidden behind the privacy PIN. Like [inPhotos], every view of photos goes through it. */
fun List<Media>.withoutHidden(h: HiddenState) = withoutHidden(this, h, Media::id, Media::path)

/** "Asha", "Goa trip", "3 people and 2 albums": what a hide or unhide was about, for its snackbar. */
fun hiddenWhat(people: List<String?>, albums: List<String>): String = when {
    people.size + albums.size == 1 -> if (people.size == 1) people[0] ?: "1 person" else albums[0]
    people.isEmpty() -> counted(albums.size, "album", "albums")
    albums.isEmpty() -> counted(people.size, "person", "people")
    else -> "${counted(people.size, "person", "people")} and ${counted(albums.size, "album", "albums")}"
}

/**
 * What a hide or unhide says: "Hid 2 items" for Hidden items' own picks ([counted]), else naming the one thing hidden
 * from its page or menu: "Hid Goa trip. It is in Hidden items."
 */
fun hiddenNote(people: List<String?>, albums: List<String>, hide: Boolean, counted: Boolean): String {
    val what = if (counted) itemCount(people.size + albums.size) else hiddenWhat(people, albums)
    return when {
        !hide -> "Unhid $what"
        counted -> "Hid $what"
        people.size + albums.size == 1 -> "Hid $what. It is in Hidden items."
        else -> "Hid $what. They are in Hidden items."
    }
}

/** Hides and unhides people and albums, and says so with Undo; [state] is what every screen filters by. */
object Hidden {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * What's hidden now. Null until people.db has answered, so a screen that waits for it never flashes a hidden photo at
     * launch. Kept up to date for the life of the process: cheap while nobody is locked (no face index read).
     */
    val state: StateFlow<HiddenState?> by lazy {
        combine(AlbumPrefs.locked, People.locked) { folders, people -> HiddenState(folders, people.ids, people.photos) }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    /**
     * Puts [people] and [albums] behind the PIN. Call with privacy unlocked (see [rememberPrivacyAsk]). [counted]: the
     * message counts them (Hidden items' own picks) rather than naming one.
     */
    fun hide(context: Context, people: List<PersonAlbum>, albums: List<Album>, counted: Boolean = false) = change(context, people, albums, hide = true, counted)

    /** Takes [people] and [albums] out of Hidden items. Call with privacy unlocked. [counted] as in [hide]. */
    fun unhide(context: Context, people: List<PersonAlbum>, albums: List<Album>, counted: Boolean = false) = change(context, people, albums, hide = false, counted)

    private fun change(context: Context, people: List<PersonAlbum>, albums: List<Album>, hide: Boolean, counted: Boolean, undo: Boolean = true) {
        if (people.isEmpty() && albums.isEmpty()) return
        val app = context.applicationContext
        CrashLog.note("${if (hide) "hide" else "unhide"} ${people.size} people ${albums.size} albums")
        scope.launch {
            albums.forEach { AlbumPrefs.setLocked(it.key, hide) }
            // An automatic group becomes a person on the way, with a new id: Undo needs that one
            val changed = people.mapNotNull { p ->
                try {
                    p.copy(cluster = People.setLocked(p.cluster, hide))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CrashLog.note("people lock failed: $e")
                    null
                }
            }
            runCatching { MemoryWidget.refresh(app) } // the widget may show one of these photos
            val text = when {
                changed.size < people.size && hide -> "Couldn't move everyone to Hidden items. Try again."
                changed.size < people.size -> "Couldn't move everyone out of Hidden items. Try again."
                else -> hiddenNote(changed.map { it.name }, albums.map { it.name }, hide, counted)
            }
            PrivacyLock.notices.trySend(PrivacyLock.Notice(text, if (undo) ({ change(app, changed, albums, !hide, counted, undo = false) }) else null))
        }
    }
}
