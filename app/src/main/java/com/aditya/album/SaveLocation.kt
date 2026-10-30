package com.aditya.album

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// Where Album saves what it makes: new albums (and what's copied or moved into them), rotated copies that can't sit next
// to their original, and photos leaving the Private safe that can't go back to their folder. MediaStore only lets Album add
// photos and videos under DCIM or Pictures, so the choice is one of those, or a folder of its own in one, on the phone's
// storage or an SD card. Choosing never moves a photo that's already on the phone.

/** The phone's own shared storage, as MediaStore names it. */
const val PHONE_STORAGE = MediaStore.VOLUME_EXTERNAL_PRIMARY

private const val PHONE_LABEL = "Phone storage"

/**
 * Where Album puts new files: [folder], a RELATIVE_PATH ending in "/", on the MediaStore volume [volume]. [label] is that
 * storage's name as people know it ("SD card"), kept so it can still be named while the card is out.
 */
data class SaveLocation(val volume: String = PHONE_STORAGE, val folder: String = "Pictures/", val label: String = PHONE_LABEL) {
    /** The folder called [name] in this one, on the same storage: a new album, Rotated, Restored. */
    fun inside(name: String) = copy(folder = albumFolder(folder, name))
}

/** The top folders that can hold an album: MediaStore takes photos and videos in both. Movies takes videos only. */
val saveTops = listOf("Pictures", "DCIM")

/** RELATIVE_PATH of [top] ("Pictures"), or of a folder of Album's own called [own] in it. */
fun saveFolder(top: String, own: String? = null) = if (own.isNullOrBlank()) "$top/" else "$top/${own.trim()}/"

/** The folder called [name] in [folder]: "Pictures/" and "Goa" make "Pictures/Goa/". */
fun albumFolder(folder: String, name: String) = "$folder$name/"

/** "Pictures" for "Pictures/Album/". */
fun topOf(folder: String) = folder.substringBefore('/')

/** "Album" for "Pictures/Album/", or null when [folder] is Pictures or DCIM itself. */
fun ownOf(folder: String) = folder.trimEnd('/').substringAfter('/', "").ifEmpty { null }

/** Why [name] can't be a folder of Album's own in [top], or null if it can. Album names follow the same rules. */
fun ownFolderError(top: String, name: String): String? = albumNameError(name, emptyList())
    // New albums would end up inside the camera's or the screenshots' folder
    ?: "${name.trim()} is a system folder. Pick another name.".takeIf { folderKey(saveFolder(top, name)) in systemFolders }

/** The storage to save to now: [chosen] while it's in the phone ([mounted] is MediaStore's list), else the phone's own. */
fun volumeNow(chosen: String, mounted: Collection<String>) = if (chosen in mounted) chosen else PHONE_STORAGE

/** How [location] reads in Settings and on the first-run button: "Pictures", "Pictures/Album on SD card". */
fun placeName(location: SaveLocation) =
    location.folder.trimEnd('/') + if (location.volume == PHONE_STORAGE) "" else " on ${location.label}"

/** The MediaStore volume [uri]'s file is on. Album's URIs all say "external", which covers every storage. */
fun volumeOf(resolver: ContentResolver, uri: Uri): String = runCatching {
    resolver.query(uri, arrayOf(MediaColumns.VOLUME_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull() ?: PHONE_STORAGE

/** Bytes free on [volume], or null if Android won't say. */
fun freeSpace(context: Context, volume: String): Long? =
    if (volume == PHONE_STORAGE) {
        context.filesDir.usableSpace // shared storage sits on the same partition as the app's own files
    } else {
        context.getSystemService(StorageManager::class.java).storageVolumes
            .find { it.mediaStoreVolumeName == volume }?.directory?.usableSpace?.takeIf { it > 0 }
    }

/** A storage Album can save to right now. */
class Storage(val volume: String, val label: String, val free: Long?)

/** Storages MediaStore can add photos to now: the phone's first, then any SD card that's in. Call off the main thread. */
fun storages(context: Context): List<Storage> {
    val mounted = MediaStore.getExternalVolumeNames(context)
    return context.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED && it.mediaStoreVolumeName in mounted }
        .map { v ->
            val volume = v.mediaStoreVolumeName!!
            Storage(volume, if (volume == PHONE_STORAGE) PHONE_LABEL else v.getDescription(context), freeSpace(context, volume))
        }
        .sortedByDescending { it.volume == PHONE_STORAGE }
}

/** The [SaveLocation], in the "settings" prefs, and whether the first-run step has been answered. */
object SavePrefs {
    private const val VOLUME = "save_volume"
    private const val LABEL = "save_volume_label"
    private const val FOLDER = "save_folder"
    private const val TOLD = "save_card_out_told"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(SaveLocation())
    private val answered = MutableStateFlow(true) // until loaded, so the step can't flash up for someone who answered
    val location = state.asStateFlow()

    /** False until a place is picked on the first-run step. Everyone is asked once, people updating from 1.1 too. */
    val asked = answered.asStateFlow()

    /** Call before the first screen draws. Safe to call repeatedly. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        state.value = SaveLocation(
            prefs.getString(VOLUME, null) ?: PHONE_STORAGE,
            prefs.getString(FOLDER, null) ?: "Pictures/",
            prefs.getString(LABEL, null) ?: PHONE_LABEL,
        )
        answered.value = prefs.contains(FOLDER)
    }

    fun set(location: SaveLocation) {
        CrashLog.note("save to ${if (location.volume == PHONE_STORAGE) "phone" else "card"} ${topOf(location.folder)}")
        state.value = location
        answered.value = true
        prefs.edit {
            putString(VOLUME, location.volume)
            putString(LABEL, location.label)
            putString(FOLDER, location.folder)
            remove(TOLD)
        }
    }

    /**
     * Where a file about to be added goes: the chosen place, or the same folder on the phone's storage while the chosen SD
     * card is out. The first time that happens a toast says so, and again only once the card has been back in between.
     */
    @Synchronized
    fun target(context: Context): SaveLocation {
        load(context)
        val chosen = state.value
        if (volumeNow(chosen.volume, MediaStore.getExternalVolumeNames(context)) == chosen.volume) {
            if (prefs.getBoolean(TOLD, false)) prefs.edit { remove(TOLD) }
            return chosen
        }
        if (!prefs.getBoolean(TOLD, false)) {
            prefs.edit { putBoolean(TOLD, true) }
            val app = context.applicationContext
            app.mainExecutor.execute {
                Messages.post(app, "Your ${chosen.label} isn't in. Album saves to the phone's storage until it's back.")
            }
        }
        return chosen.copy(volume = PHONE_STORAGE, label = PHONE_LABEL)
    }
}

private val LocationSaver = listSaver<SaveLocation, String>({ listOf(it.volume, it.folder, it.label) }) { SaveLocation(it[0], it[1], it[2]) }

private const val SAVE_NOTE = "New albums you make go here. Photos already on your phone stay where they are."

/**
 * Asked once, after photo access is granted: where Album saves what it makes, with Pictures on the phone's storage picked.
 * Tapping Use saves the choice and the Library opens. Leaving without it asks again next time.
 */
@Composable
fun SaveLocationStep() {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    SavePrefs.load(LocalContext.current)
    var choice by rememberSaveable(stateSaver = LocationSaver) { mutableStateOf(SavePrefs.location.value) }
    var naming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding().padding(horizontal = Space.s4)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = Space.s12), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Icon(Sym.Folder, null, size = 40.dp, tint = c.inkMuted)
            Text("Where should Album save?", style = type.titleLg, color = c.ink)
            Text("$SAVE_NOTE You can change this in Settings.", style = type.body, color = c.inkMuted)
            Spacer(Modifier.height(Space.s4))
            SaveChoices(choice, { choice = it }) { naming = true }
        }
        // Pictures is picked to begin with, so this is also the way to skip
        AlbumButton("Use ${placeName(choice)}", { SavePrefs.set(choice) }, Modifier.fillMaxWidth().padding(vertical = Space.s4), ButtonVariant.Primary)
    }
    if (naming) {
        OwnFolderSheet(choice.folder, { naming = false }) {
            choice = choice.copy(folder = it)
            naming = false
        }
    }
}

/** For Settings > Photos and videos: where Album saves. Tapping opens the same choices as the first-run step, each applying at once. */
@Composable
fun SaveLocationRow(modifier: Modifier = Modifier) {
    SavePrefs.load(LocalContext.current)
    val location by SavePrefs.location.collectAsState()
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    GroupRow("Where Album saves", { open = true }, modifier, icon = Sym.Folder, sub = placeName(location))
    // One sheet at a time: naming a folder takes the choices' place, and closing it brings them back
    when {
        naming -> OwnFolderSheet(location.folder, { naming = false }) {
            SavePrefs.set(location.copy(folder = it))
            naming = false
        }
        open -> Sheet({ open = false }, "Where Album saves") {
            Text(SAVE_NOTE, Modifier.padding(bottom = Space.s2), style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted)
            SaveChoices(location, SavePrefs::set) { naming = true }
        }
    }
}

/**
 * The folder rows, then the storage rows when there's more than the phone's own. [onOwnFolder] opens the sheet for a
 * folder of Album's own; everything else picks at once.
 */
@Composable
private fun SaveChoices(choice: SaveLocation, onChoose: (SaveLocation) -> Unit, onOwnFolder: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val storages by produceState<List<Storage>?>(null) { value = withContext(Dispatchers.IO) { storages(context) } }

    @Composable
    fun Option(icon: Sym, label: String, sub: String, on: Boolean, onClick: () -> Unit) = MenuRow(
        icon, label, onClick,
        // The check carries the choice; the tint only backs it up
        (if (on) Modifier.background(c.flashSoft, RoundedCornerShape(Space.radiusMd)) else Modifier).semantics { selected = on },
        sub = sub, trailing = if (on) Sym.Check else null,
    )

    val own = ownOf(choice.folder)
    Option(Sym.Image, "Pictures", "Recommended. Each new album is a folder in Pictures", choice.folder == "Pictures/") {
        onChoose(choice.copy(folder = "Pictures/"))
    }
    Option(Sym.PhotoCamera, "DCIM", "Next to your camera's photos", choice.folder == "DCIM/") { onChoose(choice.copy(folder = "DCIM/")) }
    Option(
        Sym.Folder, if (own == null) "A folder of your own" else choice.folder.trimEnd('/'),
        if (own == null) "Everything Album makes in one place, like Pictures/Album" else "Tap to rename it or move it", own != null, onOwnFolder,
    )

    // Below the folders, so the rows above don't jump when this loads
    val shown = storages ?: return
    val out = choice.volume != PHONE_STORAGE && shown.none { it.volume == choice.volume }
    if (shown.size < 2 && !out) return
    Text("Storage", Modifier.padding(top = Space.s4), style = AlbumTheme.type.titleMd, color = c.ink)
    // Android moves a file by renaming it where it is, so a move into a new album can't change its storage
    Text("Copies go here. A photo you move stays on the storage it's on.", style = AlbumTheme.type.caption, color = c.inkMuted)
    shown.forEach { s ->
        Option(
            if (s.volume == PHONE_STORAGE) Sym.PhoneAndroid else Sym.SdCard, s.label,
            s.free?.let { "${Formatter.formatShortFileSize(context, it)} free" } ?: "In the phone", s.volume == choice.volume,
        ) { onChoose(choice.copy(volume = s.volume, label = s.label)) }
    }
    // A card that's out stays listed, picked, so it's plain why new files land on the phone for now
    if (out) Option(Sym.SdCard, choice.label, "Not in right now. Album saves to the phone's storage until it's back", true) {}
}

/** Names a folder of Album's own, in Pictures or DCIM, starting from [folder], what's chosen now. */
@Composable
private fun OwnFolderSheet(folder: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val start = ownOf(folder) ?: "Album"
    var top by rememberSaveable { mutableStateOf(topOf(folder)) }
    // Starts with the name selected, so typing replaces it
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val error = ownFolderError(top, text.text)
    NameSheet(
        "A folder of your own", text, { text = it }, "Use folder", enabled = error == null, onDismiss = onDismiss,
        onConfirm = { if (error == null) onDone(saveFolder(top, text.text)) },
        note = error ?: "New albums go in ${saveFolder(top, text.text).trimEnd('/')}", isError = error != null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            saveTops.forEach { t -> Chip(t, { top = t }, selected = top == t) }
        }
    }
}
