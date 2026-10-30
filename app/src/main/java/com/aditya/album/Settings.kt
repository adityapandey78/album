package com.aditya.album

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Settings: three groups of rows, each opening a page of its own, with a line under each that sums up that page as it is
// now. Every choice applies the moment it's made (AppearancePrefs.update saves it too), so there is no Save button.

private val Columns = listOf(3, 4, 5, 7)

/** The pages only Settings opens. Appearance and About have screens of their own, which the Library's menu opens too. */
enum class SettingsSection(val title: String, val icon: Sym) {
    Lab("Design lab", Sym.Science),
    Library("Library and grid", Sym.PhotoLibrary),
    Media("Photos and videos", Sym.Movie),
    Privacy("Privacy and safe", Sym.Shield),
    Deleting("Deleting", Sym.Delete),
    Smart("Search and smart features", Sym.Search),
    Motion("Motion and feel", Sym.Vibration),
    Editing("Editing", Sym.Tune),
}

/**
 * The Settings page, pushed over the tabs with its own top bar and insets: three groups of rows that each open a page,
 * with a line under each that sums up that page. [columns] is the Library grid's column count, for that line.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    columns: Int,
    onAppearance: () -> Unit,
    onSection: (SettingsSection) -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val a = spec.appearance
    RotatePrefs.load(context)
    remember { PrivacyLock.init(context) }
    remember { DisguisePrefs.load(context) }
    val pin by PrivacyLock.setUp.collectAsState()
    val disguise by DisguisePrefs.chosen.collectAsState()
    val noPopups = rememberCanManageMedia()
    // Read once: this page comes back fresh from the pages it opens
    val autoplay = remember { PlayPrefs.autoplay.get(context) }
    val muted = remember { PlayPrefs.startMuted.get(context) }
    val editor = editorName()
    val rotate by RotatePrefs.choice.collectAsState()

    @Composable
    fun Page(section: SettingsSection, sub: String) = GroupRow(section.title, { onSection(section) }, icon = section.icon, sub = sub)

    SettingsPage("Settings", onBack) {
        Group { Page(SettingsSection.Lab, "Try bar, video, For you and Settings options") }
        Group {
            GroupRow("Appearance", onAppearance, icon = Sym.Palette, sub = listOf(a.theme.label, a.accent.label, a.look.label, a.font.label).joinToString(" · "))
            Page(SettingsSection.Library, "${a.gallery.label} · $columns columns · ${a.bar.label} bar")
            Page(SettingsSection.Media, (if (autoplay) "Plays by itself" else "Tap to play") + " · " + if (muted) "muted" else "with sound")
        }
        Group {
            Page(SettingsSection.Privacy, "PIN ${if (pin) "on" else "off"} · disguise ${disguise?.label ?: "off"}")
            Page(SettingsSection.Deleting, if (noPopups) "Straight to Trash, with Undo" else "Asks first · 30-day Trash")
            Page(SettingsSection.Smart, "Describe-it search, On this day")
        }
        Group {
            Page(SettingsSection.Motion, (if (spec.still) "Animations removed" else "${a.tabMotion.label} tabs") + if (a.haptics) " · haptics on" else "")
            Page(SettingsSection.Editing, editingSummary(editor, rotate))
            GroupRow("About", onAbout, icon = Sym.Info, sub = "Album ${remember { Updater.currentVersion(context) }} · updates, diagnostics, licences")
        }
    }
}

private fun editingSummary(editor: String?, rotate: RotateSave) =
    (editor?.let { "Edits in $it" } ?: "Ask which app") + " · " + if (rotate == RotateSave.Ask) "ask how to save" else rotate.label.lowercase()

/**
 * A page of Settings, pushed from it. [columns] is the Library grid's live column count, [snackbar] shows the Design
 * lab's sample message; the rest open the pages that this page's rows lead to.
 */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    columns: MutableIntState,
    onOpenSafe: () -> Unit,
    onHiddenItems: () -> Unit,
    onDisguise: () -> Unit,
    onOpenTrash: () -> Unit,
    onDescribe: () -> Unit,
    onBackup: () -> Unit,
    onIdCards: () -> Unit = {},
    snackbar: SnackbarHostState,
) = SettingsPage(section.title, onBack) {
    when (section) {
        SettingsSection.Lab -> LabPage(snackbar)
        SettingsSection.Library -> LibraryPage(columns)
        SettingsSection.Media -> MediaPage(onBackup)
        SettingsSection.Privacy -> PrivacyPage(onOpenSafe, onHiddenItems, onDisguise, onIdCards)
        SettingsSection.Deleting -> Group(note = "Deleted photos and videos stay in ${Special.Trash.title} for 30 days, then Android erases them.") {
            MediaManagementRow()
            GroupRow(Special.Trash.title, onOpenTrash, icon = Sym.RestoreFromTrash, value = rememberTrash().value?.size?.toString())
        }
        SettingsSection.Smart -> {
            val context = LocalContext.current
            val size = remember { Formatter.formatShortFileSize(context, ModelDownload.TOTAL) }
            val describe by Embeddings.state.collectAsState()
            Group(note = "Everything runs on this phone. Nothing is uploaded.") {
                GroupRow(
                    "Describe-it search", onDescribe, icon = Sym.Search, sub = "Find photos by describing them",
                    // The model's size, or how far its download has got
                    value = (describe as? DescribeState.Downloading)?.let { "${(it.fraction * 100).toInt()}%" } ?: size,
                )
            }
            Group(title = "Memories") { MemoryNotificationRow() }
        }
        SettingsSection.Motion -> MotionPage()
        SettingsSection.Editing -> Group {
            EditorRow()
            RotateSaveRow()
        }
    }
}

/**
 * Appearance, pushed from Settings or the Library's menu: theme, accent, font and look. Every choice applies the moment
 * it's made.
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) = SettingsPage("Appearance", onBack) {
    val a = AlbumTheme.spec.appearance
    Group(title = "Theme") {
        Padded {
            ChoiceGroup(
                ThemeMode.entries.map { mode ->
                    Choice(mode, mode.label) {
                        if (mode == ThemeMode.System) {
                            // Half light, half dark: System follows the phone
                            Box {
                                StyleSwatch(theme = ThemeMode.Light)
                                StyleSwatch(Modifier.drawWithContent { clipRect(left = size.width / 2) { this@drawWithContent.drawContent() } }, theme = ThemeMode.Dark)
                            }
                        } else StyleSwatch(theme = mode)
                    }
                },
                a.theme,
            ) { v -> AppearancePrefs.update { it.copy(theme = v) } }
        }
    }
    val context = LocalContext.current
    remember { DisguisePrefs.load(context) }
    val disguised = DisguisePrefs.chosen.collectAsState().value != null
    Group(title = "Accent", note = if (disguised) "The app icon follows the accent once Album isn't disguised." else "The app icon follows the accent.") {
        Padded { AccentPicker(a.accent) { v -> AppearancePrefs.update { it.copy(accent = v) } } }
    }
    Group(title = "Text") {
        ChipRow("Font", AppFont.entries.map { it to it.label }, a.font, { v -> AppearancePrefs.update { it.copy(font = v) } })
    }
    Group(title = "Look") {
        Padded {
            ChoiceGroup(Look.entries.map { Choice(it, it.label, it.blurb) { StyleSwatch(look = it) } }, a.look) { v ->
                AppearancePrefs.update { it.copy(look = v) }
            }
        }
    }
}

/**
 * Settings > Library and grid: gallery style and columns, the bottom bar's style and what it does while scrolling, then
 * what the Library leaves out.
 */
@Composable
private fun LibraryPage(columns: MutableIntState) {
    val a = AlbumTheme.spec.appearance
    val hidden by AlbumPrefs.hidden.collectAsState()
    Group(title = "Gallery style") {
        Padded {
            ChoiceGroup(GalleryStyle.entries.map { Choice(it, it.label, it.blurb) { StyleSwatch(layout = it) } }, a.gallery) { v ->
                AppearancePrefs.update { it.copy(gallery = v) }
            }
        }
        ChipRow("Columns", Columns.map { it to "$it" }, columns.intValue, { columns.intValue = it }, sub = "Or pinch the grid")
    }
    Group(title = "Bottom bar") {
        ChipRow("Style", BarStyle.entries.map { it to it.label }, a.bar, { v -> AppearancePrefs.update { it.copy(bar = v) } })
        ChipRow("While scrolling", BarScroll.entries.map { it to it.label }, a.barScroll, { v -> AppearancePrefs.update { it.copy(barScroll = v) } })
    }
    Group(title = "What the Library shows") {
        // The same as hiding Android's screenshot folders from their menu in Collections
        GroupSwitch(
            "Screenshots in the Library", "Off: they stay in Collections only.", screenshotsShown(hidden),
            { on -> ScreenshotFolders.forEach { AlbumPrefs.setHidden(it, !on) } }, icon = Sym.ScreenshotMonitor,
        )
        HiddenFoldersRow(hidden)
    }
}

/** Android's screenshot folders, as [folderKey]s. */
internal val ScreenshotFolders = setOf("dcim/screenshots/", "pictures/screenshots/")

/** Whether the Library shows screenshots: neither screenshot folder is hidden from it. */
internal fun screenshotsShown(hidden: Set<String>) = ScreenshotFolders.none { it in hidden }

/** The folders "Hidden folders" lists: hidden from the Library, less the screenshots (their own switch) and Hidden items. */
internal fun listedHidden(hidden: Set<String>, locked: Set<String>) = hidden - ScreenshotFolders - locked

/**
 * How many folders are hidden from the Library (from a folder's menu in Collections). Tapping lists them, and tapping one
 * there shows it in the Library again.
 */
@Composable
private fun HiddenFoldersRow(hidden: Set<String>) {
    val media by Gallery.media.collectAsState()
    val locked by AlbumPrefs.locked.collectAsState()
    val folders = remember(media, hidden, locked) {
        val keys = listedHidden(hidden, locked)
        media?.let { m -> albumsOf(m).filter { it.key in keys } }
    }
    var open by remember { mutableStateOf(false) }
    GroupRow(
        "Hidden folders", folders?.takeIf { it.isNotEmpty() }?.let { { open = true } }, icon = Sym.VisibilityOff,
        value = folders?.size?.toString(),
    )
    if (!open || folders.isNullOrEmpty()) return
    Sheet({ open = false }, "Hidden folders") {
        Text(
            "They stay in Collections. Tap one to show it in the Library again.",
            Modifier.padding(bottom = Space.s2), style = AlbumTheme.type.caption, color = AlbumTheme.colors.inkMuted,
        )
        folders.forEach { album ->
            MenuRow(Sym.Folder, album.name, {
                CrashLog.note("show folder in library")
                AlbumPrefs.setHidden(album.key, false)
                if (folders.size == 1) open = false
            }, sub = itemCount(album.count), trailing = Sym.Visibility)
        }
    }
}

/** Settings > Photos and videos: how videos and motion photos play, then where Album saves and Backup. */
@Composable
private fun MediaPage(onBackup: () -> Unit) {
    Group(title = "Videos") {
        PrefSwitch(PlayPrefs.autoplay, "Play videos by themselves", "When you open a video. Off: tap play.", Sym.PlayArrow)
        PrefSwitch(PlayPrefs.startMuted, "Start videos muted", "Tap the speaker to turn sound on.", Sym.VolumeOff)
        PrefSwitch(PlayPrefs.loopShort, "Loop short clips", "Clips under 15 seconds play again.", Sym.Repeat)
    }
    Group(title = "Photos") {
        PrefSwitch(PlayPrefs.motionHold, "Play motion photos on hold", "Press and hold a Live photo to play it.", Sym.MotionPhotosOn)
    }
    Group {
        SaveLocationRow()
        BackupRow(onBackup)
    }
}

/** An on/off setting in the "settings" prefs, read where it applies (as a video opens, say), so nothing watches it. */
class SwitchPref(private val key: String, private val default: Boolean) {
    fun get(context: Context) = prefs(context).getBoolean(key, default)

    fun set(context: Context, on: Boolean) {
        CrashLog.note("$key $on")
        prefs(context).edit { putBoolean(key, on) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

/** Settings > Photos and videos: how the viewer plays videos and motion photos. */
object PlayPrefs {
    val autoplay = SwitchPref("video_autoplay", true)

    /** Until 1.1.2 the speaker remembered its last state under this key; now it's how each video starts. */
    val startMuted = SwitchPref("video_muted", true)
    val loopShort = SwitchPref("video_loop_short", false)
    val motionHold = SwitchPref("motion_hold", true)

    /** Clips shorter than this loop by themselves while Loop short clips is on. */
    const val SHORT_CLIP_MS = 15_000L

    /** Whether a video [ms] long starts with Loop on. */
    fun loops(context: Context, ms: Long) = loopsShort(loopShort.get(context), ms)
}

/** Whether a clip [ms] long loops by itself: only while [on], and under [PlayPrefs.SHORT_CLIP_MS] (0 is unknown, so no). */
internal fun loopsShort(on: Boolean, ms: Long) = on && ms in 1 until PlayPrefs.SHORT_CLIP_MS

/** A [GroupSwitch] for a [SwitchPref]: shows it as saved, and saves each change at once. */
@Composable
private fun PrefSwitch(pref: SwitchPref, label: String, description: String, icon: Sym) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(pref.get(context)) }
    GroupSwitch(label, description, on, { v ->
        on = v
        pref.set(context, v)
    }, icon = icon)
}

/**
 * Settings > Privacy and safe: Private safe (with its count), ID cards and Hidden items; the privacy PIN, fingerprint
 * unlock and Disguise; then whether Collections shows Recently deleted.
 */
@Composable
private fun PrivacyPage(onOpenSafe: () -> Unit, onHiddenItems: () -> Unit, onDisguise: () -> Unit, onIdCards: () -> Unit) {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    val setUp by PrivacyLock.setUp.collectAsState()
    val fingerprint by PrivacyLock.fingerprint.collectAsState()
    val wrapLost by Safe.wrapLost.collectAsState()
    var hasFingerprint by remember { mutableStateOf(Safe.hasFingerprint(context)) }
    LifecycleResumeEffect(Unit) {
        hasFingerprint = Safe.hasFingerprint(context) // the user may have added one in Android settings
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { Safe.checkKey() }
    var sheet by remember { mutableStateOf<PrivacyPurpose?>(null) }
    val ask = rememberPrivacyAsk()
    val rearm = setUp && (wrapLost || !Safe.isSetUp)
    val a = AlbumTheme.spec.appearance
    val inSafe = Safe.items.collectAsState(null).value?.size

    Group {
        GroupRow("Private safe", onOpenSafe, icon = Sym.Lock, sub = "Encrypted on this phone", value = inSafe?.toString())
        GroupRow("ID cards", onIdCards, icon = Sym.IdCard, sub = "Aadhaar, PAN and passport photos that open with your screen lock")
        GroupRow("Hidden items", onHiddenItems, icon = Sym.VisibilityOff, sub = "People and albums you hid")
    }
    Group(title = "Lock", note = "Private safe and ID cards lock as soon as you leave them, Hidden items 2 minutes after, and all of them whenever Album leaves the screen.") {
        GroupRow(
            "Privacy PIN",
            {
                CrashLog.note(if (setUp) "change pin" else "set pin")
                sheet = if (setUp) PrivacyPurpose.Change else PrivacyPurpose.Unlock
            },
            icon = Sym.Pin,
            sub = if (setUp) "Change your PIN" else "Not set yet. Set one to lock the safe.",
            value = if (setUp) "On" else "Off",
        )
        if (setUp && hasFingerprint && !rearm) {
            GroupSwitch(
                "Unlock with fingerprint", "Uses your phone's fingerprint, never stored by Album.", fingerprint,
                PrivacyLock::setFingerprint, icon = Sym.Fingerprint,
            )
        }
        if (rearm) {
            GroupRow(
                "Turn Forgot PIN back on",
                { ask.run("Turn Forgot PIN back on") { PrivacyLock.rearm(context, CancellationSignal(), onDone = {}) { it?.let { m -> PrivacyLock.notices.trySend(PrivacyLock.Notice(m)) } } } },
                icon = Sym.Fingerprint, sub = "Your screen lock was off for a while, which switched off Forgot PIN and fingerprint unlock",
            )
        }
        DisguiseRow(onDisguise)
    }
    Group(title = "Sharing") {
        val context = LocalContext.current
        remember { SharePrefs.load(context) }
        val strip by SharePrefs.strip.collectAsState()
        GroupSwitch(
            "Remove location when sharing", "GPS and camera details are left out of the copy you send.", strip,
            { v -> SharePrefs.set(v) }, icon = Sym.LocationOff,
        )
    }
    Group(title = "Collections") {
        GroupSwitch(
            "Show Recently deleted in Collections", "Off: it's only here in Settings, under Deleting.", a.trashInCollections,
            { v -> AppearancePrefs.update { it.copy(trashInCollections = v) } }, icon = Sym.RestoreFromTrash,
        )
    }
    sheet?.let { purpose ->
        PrivacySheet(
            if (purpose == PrivacyPurpose.Change) "Change privacy PIN" else "Set a privacy PIN",
            onDone = { sheet = null }, onDismiss = { sheet = null }, purpose = purpose,
        )
    }
}

/** Settings > Motion and feel: Remove animations (the phone's setting, or this override) and haptics, then the tab transition. */
@Composable
private fun MotionPage() {
    val a = AlbumTheme.spec.appearance
    Group(note = if (a.still == null) "Following your phone's Remove animations setting." else "Set here, overriding your phone.") {
        GroupSwitch(
            "Remove animations", "Screens swap with a quick fade; nothing slides, zooms or bounces.",
            AlbumTheme.spec.still, // what's in effect, the phone's setting included
            { v -> AppearancePrefs.update { it.copy(still = v) } },
            icon = Sym.MotionPhotosOn,
        )
        if (a.still != null) GroupRow("Follow the phone again", { AppearancePrefs.update { it.copy(still = null) } }, icon = Sym.Undo, trailing = null)
        GroupSwitch(
            "Haptic feedback", "Ticks on select, pinch and swipe.", a.haptics,
            { v -> AppearancePrefs.update { it.copy(haptics = v) } }, icon = Sym.Vibration,
        )
    }
    Group(title = "Tab transition") {
        ChipRow("Style", TabMotion.entries.map { it to it.label }, a.tabMotion, { v -> AppearancePrefs.update { it.copy(tabMotion = v) } })
    }
    Group(title = "Messages", note = "Messages after an action, like \"Moved 3 items to Trash\". Undo goes when the message does.") {
        MessageTimeRow(a.messageSeconds)
    }
}

/**
 * Settings > Design lab: the design's switchable options, live across the whole app. The bottom bar (with the real bar
 * over a strip of colour), For you, video controls, Settings and messages (with a sample to try), and motion. Reset to
 * defaults puts back only these.
 */
@Composable
private fun LabPage(snackbar: SnackbarHostState) {
    val a = AlbumTheme.spec.appearance
    val scope = rememberCoroutineScope()
    fun save(change: (Appearance) -> Appearance) = AppearancePrefs.update(change)
    Text("Switch between the design options; changes apply to the whole app at once.", style = AlbumTheme.type.body, color = AlbumTheme.colors.inkMuted)
    Group(title = "Bottom bar", note = "Shrink keeps the bar in reach while you scroll: only the current tab and search stay.") {
        BarPreview(a.bar, a.barScroll)
        ChipRow("Style", BarStyle.entries.map { it to it.label }, a.bar, { v -> save { it.copy(bar = v) } })
        ChipRow("While scrolling", BarScroll.entries.map { it to it.label }, a.barScroll, { v -> save { it.copy(barScroll = v) } })
    }
    Group(title = "For you") {
        Padded {
            ChoiceGroup(ForYouStyle.entries.map { Choice(it, it.label, it.blurb) { ForYouSwatch(it) } }, a.forYouStyle) { v -> save { it.copy(forYouStyle = v) } }
        }
    }
    Group(title = "Video controls") {
        Padded {
            ChoiceGroup(VideoControls.entries.map { Choice(it, it.label, it.blurb) { VideoSwatch(it) } }, a.videoControls) { v -> save { it.copy(videoControls = v) } }
        }
    }
    Group(title = "Settings") {
        Padded {
            ChoiceGroup(SettingsStyle.entries.map { Choice(it, it.label, it.blurb) { SettingsSwatch(it) } }, a.settingsStyle) { v -> save { it.copy(settingsStyle = v) } }
        }
    }
    Group(title = "Messages", note = "Toasts and Undo messages.") {
        Padded {
            ChoiceGroup(MessageStyle.entries.map { Choice(it, it.label, it.blurb) { MessageSwatch(it, play = it == a.messageStyle) } }, a.messageStyle) { v -> save { it.copy(messageStyle = v) } }
        }
        // Only a sample: nothing moves to Trash, and Undo only answers
        GroupRow("Show a sample message", {
            scope.launch {
                if (snackbar.showSnackbar("Moved 3 items to Trash", Sym.Delete, "Undo") == SnackbarResult.ActionPerformed) {
                    snackbar.showSnackbar("Restored 3 items", Sym.RestoreFromTrash)
                }
            }
        }, icon = Sym.Undo, trailing = null)
    }
    Group(title = "Motion") {
        Padded { TabMotionPreview() }
        ChipRow("Tab transition", TabMotion.entries.map { it to it.label }, a.tabMotion, { v -> save { it.copy(tabMotion = v) } })
        ChipRow("Speed", listOf(false to "Normal", true to "Slow motion 4×"), AlbumMotion.slow, { AlbumMotion.slow = it })
    }
    Group {
        GroupRow("Reset to defaults", {
            save(::labDefaults)
            AlbumMotion.slow = false
        }, icon = Sym.Undo, trailing = null)
    }
}

/** [a] with the Design lab's options back at their defaults, and everything else as it was. */
internal fun labDefaults(a: Appearance): Appearance = Appearance().let { d ->
    a.copy(
        bar = d.bar, barScroll = d.barScroll, forYouStyle = d.forYouStyle, videoControls = d.videoControls,
        settingsStyle = d.settingsStyle, messageStyle = d.messageStyle, tabMotion = d.tabMotion,
    )
}

/**
 * The design lab's bar preview: the real bottom bar, in [style], over photos that scroll up and back on a loop so the bar
 * shows what it does while scrolling ([mode]): shrinks to the current tab and search, sinks away, or stays. It answers
 * like the real one: a tab takes the active state, search nudges a "Search opens here" note, and the mini tab expands
 * the bar (and restarts the loop). With Remove animations on nothing scrolls and the full bar holds still.
 */
@Composable
private fun BarPreview(style: BarStyle, mode: BarScroll) {
    val s = AlbumTheme.spec
    var active by remember { mutableIntStateOf(0) }
    var searched by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    // A new While scrolling pick plays at once; a new style shows the full bar for a whole turn first
    val lastMode = remember { arrayOf(mode) }
    val loop = rememberLoop(2000, s.still, restart = mode to style, firstMs = if (mode != lastMode[0]) 350 else 2000)
    SideEffect { lastMode[0] = mode }
    LaunchedEffect(loop) { held = false }
    val down = loop && !held // tapping the mini tab brings the full bar back until the loop next turns
    val lift by animateDpAsState(if (down) 56.dp else 0.dp, if (s.still) snap() else tween(700, easing = FastOutSlowInEasing), label = "lab scroll")
    LaunchedEffect(searched) { if (searched > 0) delay(1500).also { searched = 0 } }
    Box(Modifier.fillMaxWidth().height(144.dp).clipToBounds().background(s.colors.canvas)) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).offset(y = -lift), verticalArrangement = Arrangement.spacedBy(s.gutter)) {
            repeat(4) { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.gutter)) {
                    repeat(4) { Box(Modifier.weight(1f).height(60.dp).samplePhoto(r * 4 + it + r, s.tileCorner)) }
                }
            }
        }
        if (searched > 0) {
            Text(
                "Search opens here", Modifier.align(Alignment.TopCenter).padding(top = Space.s2).floating(CircleShape).padding(horizontal = Space.s3, vertical = Space.s1),
                style = AlbumTheme.type.label, color = s.colors.ink,
            )
        }
        val at = Modifier.align(Alignment.BottomCenter).padding(bottom = if (style == BarStyle.Classic) 0.dp else Space.s3)
        // The style is passed explicitly, and the bar keyed on it, so a new pick redraws it at once
        val bar = @Composable { m: Modifier, mini: Boolean ->
            key(style) {
                BottomBar(
                    LabTabs, active, { i, _ -> active = i }, { searched++ }, m, style = style, mini = mini,
                    onExpand = { held = true },
                )
            }
        }
        if (mode == BarScroll.Hide) {
            AnimatedVisibility(
                !down, at,
                enter = if (s.still) EnterTransition.None else slideInVertically(tween(300)) { it * 2 },
                exit = if (s.still) ExitTransition.None else slideOutVertically(tween(300)) { it * 2 },
            ) { bar(Modifier, false) }
        } else {
            bar(at, mode == BarScroll.Shrink && down)
        }
    }
}

private val LabTabs = Tab.entries.map { NavItem(it.sym, it.label) }

/**
 * Flips between false and true every [periodMs] (stretched by Slow motion) so a lab preview can play on a loop. A new
 * [restart] (the option just picked) starts it again from false and flips it after a short beat, so the pick plays at
 * once. With Remove animations on it stops at false, so the preview holds one frame.
 */
@Composable
private fun rememberLoop(periodMs: Long, still: Boolean, restart: Any? = null, firstMs: Long = 350): Boolean {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(still, restart) {
        on = false
        var wait = firstMs
        while (!still) {
            delay((wait * AlbumMotion.scaleFactor.coerceAtLeast(1f)).toLong())
            on = !on
            wait = periodMs
        }
    }
    return !still && on
}

/**
 * The tab transition, played: the real [Tabs] switching between two mock pages (a grid, then a mosaic) and back. Still,
 * it holds the first page.
 */
@Composable
private fun TabMotionPreview() {
    val s = AlbumTheme.spec
    val other = rememberLoop(1500, s.still, restart = s.appearance.tabMotion to AlbumMotion.slow)
    val shape = RoundedCornerShape(Space.radiusMd)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(140.dp).aspectRatio(3f / 4f).clip(shape).border(1.dp, s.colors.line, shape)) {
            // A fresh one per motion, so Pager and the others don't carry each other's state
            key(s.appearance.tabMotion) {
                Tabs(if (other) Tab.Collections else Tab.Library, s, Offset.Unspecified, {}) { t ->
                    StyleSwatch(layout = if (t == Tab.Library) GalleryStyle.Grid else GalleryStyle.Mosaic)
                }
            }
        }
    }
}

/** A lab miniature: a 3:4 phone in the app's canvas (or the viewer's black), with a swatch's margins. */
@Composable
private fun LabSwatch(dark: Boolean = false, content: @Composable BoxScope.() -> Unit) = Box(
    Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(if (dark) AlbumTheme.colors.void else AlbumTheme.colors.canvas)
        .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
    content = content,
)

private val MiniCard = RoundedCornerShape(6.dp)

/** For you: Calm is a big hero card over a strip of small cards; Stories is a row of rings over a hero card. */
@Composable
private fun ForYouSwatch(style: ForYouStyle) = LabSwatch {
    val c = AlbumTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwatchTitle()
        if (style == ForYouStyle.Stories) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) {
                    Box(Modifier.weight(1f).aspectRatio(1f).border(1.5.dp, c.flash, CircleShape).padding(2.5.dp).samplePhoto(it + 4, CircleShape))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).samplePhoto(0, MiniCard))
        } else {
            Box(Modifier.fillMaxWidth().weight(1.6f).samplePhoto(0, MiniCard))
            Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { Box(Modifier.weight(1f).fillMaxHeight().samplePhoto(it + 4, MiniCard)) }
            }
        }
        SwatchBar()
    }
}

/**
 * Video controls on the viewer's black: Clean is one glass row (play, a track, the time); Filmstrip is a glass panel
 * with the clock, a strip of frames under the flash playhead, and a big play button.
 */
@Composable
private fun VideoSwatch(style: VideoControls) = LabSwatch(dark = true) {
    val c = AlbumTheme.colors
    val glass = Color.White.copy(alpha = 0.16f)
    val clean = style == VideoControls.Clean
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).align(if (clean) Alignment.Center else Alignment.TopCenter).padding(top = if (clean) 0.dp else 10.dp).background(SamplePhotos[3]))
    if (clean) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp).background(glass, CircleShape).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.PlayArrow, null, filled = true, size = 10.dp, tint = Color.White)
            Box(Modifier.weight(1f).height(2.dp).background(Color.White.copy(alpha = 0.35f), CircleShape)) {
                Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(c.flash, CircleShape))
            }
            Box(Modifier.size(10.dp, 3.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
        }
    } else {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(glass, MiniCard).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("0:12 / 0:40", style = AlbumTheme.type.caption.copy(fontSize = 7.sp, lineHeight = 8.sp), color = Color.White, maxLines = 1)
            Box(Modifier.fillMaxWidth().height(12.dp)) {
                Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    repeat(6) { Box(Modifier.weight(1f).fillMaxHeight().samplePhoto(it + 2, RoundedCornerShape(1.dp))) }
                }
                Box(Modifier.align(Alignment.Center).width(2.dp).height(16.dp).background(c.flash, CircleShape))
            }
            Box(Modifier.size(20.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Sym.PlayArrow, null, filled = true, size = 12.dp, tint = Color.Black)
            }
        }
    }
}

/** Settings: Grouped puts the rows on cards; Plain leaves them on the canvas, split by hairlines. */
@Composable
private fun SettingsSwatch(style: SettingsStyle) = LabSwatch {
    val c = AlbumTheme.colors
    @Composable
    fun Rows(n: Int) = repeat(n) { i ->
        if (i > 0 && style == SettingsStyle.Plain) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(c.inkMuted, CircleShape))
            Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.7f else 0.5f).height(4.dp).background(c.ink.copy(alpha = 0.6f), CircleShape))
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwatchTitle()
        listOf(3, 2).forEach { n ->
            Box(Modifier.fillMaxWidth().height(3.dp))
            Column(
                Modifier.fillMaxWidth().then(if (style == SettingsStyle.Grouped) Modifier.background(c.surface, MiniCard).padding(4.dp) else Modifier),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { Rows(n) }
        }
    }
}

/** Messages over a mock screen: the chip rises above the bar, the island drops at the top, the snackbar runs full width. */
@Composable
private fun MessageSwatch(style: MessageStyle, play: Boolean) = LabSwatch {
    val c = AlbumTheme.colors
    val s = AlbumTheme.spec
    // The picked style shows its message arriving, again every few seconds; the others hold theirs still
    val arrive = remember { Animatable(1f) }
    LaunchedEffect(play, s.still) {
        arrive.snapTo(1f)
        while (play && !s.still) {
            arrive.snapTo(0f)
            delay(200)
            arrive.animateTo(1f, tween(440, easing = FastOutSlowInEasing))
            delay(2600)
        }
    }
    val island = style == MessageStyle.Island
    val enter = Modifier.graphicsLayer {
        val k = arrive.value
        alpha = k
        if (island) {
            transformOrigin = TransformOrigin(0.5f, 0f)
            scaleX = 0.25f + 0.75f * k
            scaleY = 0.7f + 0.3f * k
        } else translationY = (1f - k) * 10.dp.toPx()
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwatchTitle()
        repeat(2) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.gutter)) {
                repeat(3) { Box(Modifier.weight(1f).aspectRatio(1f).samplePhoto(r * 3 + it, s.tileCorner)) }
            }
        }
        Box(Modifier.weight(1f))
        SwatchBar()
    }
    fun Modifier.line(w: Float, color: Color) = fillMaxWidth(w).height(3.dp).background(color, CircleShape)
    when (style) {
        MessageStyle.Island -> Row(
            Modifier.align(Alignment.TopCenter).then(enter).fillMaxWidth(0.7f).height(13.dp).background(c.ink, CircleShape).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).line(1f, c.canvas))
            Box(Modifier.size(12.dp, 7.dp).background(c.flash, CircleShape))
        }
        MessageStyle.Chip -> Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).then(enter).fillMaxWidth(0.6f).height(13.dp).floating(CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.line(0.7f, c.ink)) }
        MessageStyle.Snackbar -> Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).then(enter).fillMaxWidth().height(14.dp).floating(MiniCard, fill = c.surfaceHigh).padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).line(0.8f, c.ink))
            Box(Modifier.size(12.dp, 3.dp).background(c.flashInk, CircleShape))
        }
    }
}

/** "0.3 s", "2.5 s", "4 s": a message time as Settings shows it. */
internal fun secondsLabel(s: Float): String {
    val tenths = Math.round(s * 10)
    return (if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}") + " s"
}

/** How long messages stay, 0.3 to 10 s in tenths. The slider moves freely and saves when let go. */
@Composable
private fun MessageTimeRow(saved: Float) {
    val c = AlbumTheme.colors
    var seconds by remember(saved) { mutableFloatStateOf(saved) }
    Column(Modifier.fillMaxWidth().padding(horizontal = GroupSide, vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Sym.Timer)
            Text("Show messages for", Modifier.weight(1f), style = AlbumTheme.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
            Text(secondsLabel(seconds), style = AlbumTheme.type.meta, color = c.inkMuted)
        }
        Slider(
            seconds,
            { seconds = Math.round(it * 10) / 10f },
            Modifier.semantics { stateDescription = secondsLabel(seconds) },
            valueRange = MESSAGE_SECONDS_MIN..MESSAGE_SECONDS_MAX,
            onValueChangeFinished = { AppearancePrefs.update { it.copy(messageSeconds = seconds) } },
            colors = SliderDefaults.colors(thumbColor = c.flash, activeTrackColor = c.flash, inactiveTrackColor = c.surfaceHigh),
        )
    }
}

/**
 * A page of Settings, or one pushed from it: the collapsed top bar over a scrolling column of groups, 24dp apart. Its
 * groups follow the Design lab's Settings style ([LocalPlainGroups]).
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    gap: Dp = Space.s6,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).systemBarsPadding()) {
        CollapsedTopBar(title, onBack = onBack)
        CompositionLocalProvider(LocalPlainGroups provides (AlbumTheme.spec.appearance.settingsStyle == SettingsStyle.Plain)) {
            Column(
                Modifier.verticalScroll(scroll).padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s8),
                verticalArrangement = Arrangement.spacedBy(gap),
                content = content,
            )
        }
    }
}

/**
 * Something other than rows in a [Group] (a row of swatches, a paragraph): the card's 16dp on every side, or 8dp above and
 * below in a Plain group.
 */
@Composable
private fun Padded(content: @Composable () -> Unit) =
    Box(Modifier.padding(if (LocalPlainGroups.current) PaddingValues(vertical = Space.s2) else PaddingValues(Space.s4))) { content() }

// ---------- Appearance controls ----------

internal class Choice<T>(val value: T, val label: String, val sublabel: String? = null, val preview: @Composable () -> Unit)

/**
 * A row of visual choices where exactly one is picked: theme, look and gallery style. The picked one gets a 2dp flash-ink
 * outline and a flash check, so the choice never rests on colour alone. Choosing applies at once.
 */
@Composable
internal fun <T> ChoiceGroup(options: List<Choice<T>>, value: T, onChange: (T) -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val shape = RoundedCornerShape(Space.radiusMd)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        options.forEach { o ->
            val on = o.value == value
            Column(
                Modifier.weight(1f).clip(shape).selectable(on, role = Role.RadioButton, onClick = { onChange(o.value) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.padding(bottom = Space.s1).fillMaxWidth().clip(shape).border(if (on) 2.dp else 1.dp, if (on) c.flashInk else c.line, shape)) {
                    o.preview()
                    if (on) {
                        // A 24dp flash disc 4dp in from the corner, with its 2dp on-flash ring outside it
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(Space.check + 4.dp)
                                .background(c.flash, CircleShape).border(2.dp, c.onFlash, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Sym.Check, null, size = Space.iconSm, tint = c.onFlash) }
                    }
                }
                Text(o.label, style = type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                o.sublabel?.let { Text(it, style = type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * The accent discs. The picked one gets a ring and a check; Wallpaper (Android's dynamic colours) shows as a disc of three
 * accents since its real colours depend on the wallpaper.
 */
@Composable
private fun AccentPicker(value: Accent, onChange: (Accent) -> Unit) {
    val c = AlbumTheme.colors
    val mode = resolvedTheme(AlbumTheme.spec.appearance.theme)
    fun fill(accent: Accent) = albumColors(mode, accent).flash
    val wallpaper = Brush.sweepGradient(
        0f to fill(Accent.Coral), 1 / 3f to fill(Accent.Coral),
        1 / 3f to fill(Accent.Sky), 2 / 3f to fill(Accent.Sky),
        2 / 3f to fill(Accent.Amber), 1f to fill(Accent.Amber),
    )
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
        Accent.entries.forEach { accent ->
            val on = accent == value
            Column(
                Modifier.widthIn(min = Space.target).clip(RoundedCornerShape(Space.radiusMd))
                    .selectable(on, role = Role.RadioButton, onClick = { onChange(accent) }).padding(vertical = Space.s1),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                // A 40dp disc inside a 2dp ring with a 2dp gap
                Box(
                    Modifier.size(Space.target).border(2.dp, if (on) c.ink else Color.Transparent, CircleShape).padding(Space.s1)
                        .background(if (accent == Accent.Wallpaper) wallpaper else SolidColor(fill(accent)), CircleShape)
                        .border(1.dp, c.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // Wallpaper isn't a real accent here, so albumColors gives it lime's on-colour, which reads on all three
                    if (on) Icon(Sym.Check, null, size = Space.iconSm, tint = albumColors(mode, accent).onFlash)
                }
                Text(accent.label, style = AlbumTheme.type.caption, color = c.ink)
            }
        }
    }
}

// Stand-ins for photos in the swatches: the design's sample skies. They are pictures, not UI, so they don't follow the theme.
private val SamplePhotos = listOf(0xFFF2B279, 0xFF6B3B2A, 0xFFFFFFFF, 0xFFE9C9A0, 0xFFE86A3A, 0xFF9DB8A0, 0xFFD9C3B0, 0xFFA7A9AC, 0xFFCFDCE6)
    .map { Color(it) }

// Justified rows: each photo's share of its row's width, as its aspect ratio would give it
private val JustifiedRows = listOf(listOf(1.5f, 0.75f, 1f), listOf(1f, 1.4f), listOf(0.75f, 1.5f, 0.8f))

/**
 * A small live thumbnail of the app (title bar, a three-column grid, the bottom bar's flash pill) in a [theme], [look] and
 * gallery [layout]; anything left null follows the app. It's drawn inside an [AlbumTheme] of its own, so its colours,
 * gutter and tile corners are the real tokens for that appearance. The bars' sizes are the design's, scaled to a miniature.
 */
@Composable
private fun StyleSwatch(modifier: Modifier = Modifier, theme: ThemeMode? = null, look: Look? = null, layout: GalleryStyle? = null) {
    val a = AlbumTheme.spec.appearance
    AlbumTheme(a.copy(theme = theme ?: a.theme, look = look ?: a.look)) {
        val s = AlbumTheme.spec
        val c = s.colors
        val g = s.gutter
        fun Modifier.photo(i: Int) = samplePhoto(i, s.tileCorner)
        Column(
            modifier.fillMaxWidth().aspectRatio(3f / 4f).background(c.canvas).padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SwatchTitle()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val cell = (maxWidth - g * 2) / 3
                // Square cells placed on a three-column grid; whatever doesn't fit the swatch's height is cut off
                fun Modifier.at(col: Int, row: Int, span: Int = 1) =
                    offset(x = (cell + g) * col, y = (cell + g) * row).size(cell * span + g * (span - 1))
                when (layout ?: a.gallery) {
                    GalleryStyle.Grid -> repeat(9) { Box(Modifier.at(it % 3, it / 3).photo(it)) }
                    // The day's best shot at 2x2, then the rest around it
                    GalleryStyle.Mosaic -> listOf(Triple(0, 0, 2), Triple(2, 0, 1), Triple(2, 1, 1), Triple(0, 2, 1), Triple(1, 2, 1), Triple(2, 2, 1))
                        .forEachIndexed { i, (col, row, span) -> Box(Modifier.at(col, row, span).photo(i)) }
                    GalleryStyle.Justified -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(g)) {
                        var i = 0
                        JustifiedRows.forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(g)) {
                                row.forEach { share -> Box(Modifier.weight(share).fillMaxHeight().photo(i++)) }
                            }
                        }
                    }
                }
            }
            SwatchBar()
        }
    }
}

/** A swatch's title bar. */
@Composable
private fun SwatchTitle() = Box(Modifier.fillMaxWidth(0.45f).height(6.dp).background(AlbumTheme.colors.ink.copy(alpha = 0.85f), CircleShape))

/** A swatch's bottom bar with its flash pill. */
@Composable
private fun SwatchBar() = Box(Modifier.fillMaxWidth().background(AlbumTheme.colors.surfaceHigh, CircleShape).padding(2.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.fillMaxWidth(0.4f).height(6.dp).background(AlbumTheme.colors.flash, CircleShape))
}

/** Sample photo [i], clipped to [shape]. */
private fun Modifier.samplePhoto(i: Int, shape: Shape) = clip(shape).background(SamplePhotos[i % SamplePhotos.size])

private fun Modifier.samplePhoto(i: Int, corner: Dp) = samplePhoto(i, RoundedCornerShape(corner))

// ---------- Deleting ----------

/** Whether Album has Android's Media management access, looked at again whenever the screen comes back. */
@Composable
private fun rememberCanManageMedia(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(MediaStore.canManageMedia(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = MediaStore.canManageMedia(context) // the user changes it in system settings
        onPauseOrDispose {}
    }
    return allowed
}

/**
 * Android's Media management access. With it, MediaStore's trash, restore and favourite requests complete without a
 * system pop-up: that's why moving to trash offers Undo, and why Delete forever asks inside Album. The switch only shows
 * the state; Android's own settings page changes it.
 */
@Composable
private fun MediaManagementRow() {
    val context = LocalContext.current
    GroupSwitch(
        "Delete and favourite without pop-ups",
        "Needs Media management access. Deleted items still wait 30 days in Trash.",
        rememberCanManageMedia(),
        {
            CrashLog.note("open media management")
            context.startSafely(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")))
        },
        icon = Sym.CheckCircle,
    )
}

// ---------- Editing ----------

/** The remembered editing app's name: null when Edit asks every time, while loading, and once that app is uninstalled. */
@Composable
private fun editorName(): String? {
    val context = LocalContext.current
    EditorPrefs.load(context)
    val chosen by EditorPrefs.chosen.collectAsState()
    return produceState<String?>(null, chosen) {
        value = chosen?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrNull()
            }
        }
    }.value
}

/**
 * Settings > Editing: the remembered editing app, or "Ask every time". Once an app is remembered, tapping forgets it, so
 * Edit asks again; until then there's nothing to tap.
 */
@Composable
private fun EditorRow() {
    val app = editorName()
    GroupRow(
        "Editing app",
        // Nothing to forget while Edit asks every time, so not a button then
        app?.let {
            {
                CrashLog.note("editor forgotten")
                EditorPrefs.set(null)
            }
        },
        icon = Sym.Tune,
        sub = app?.let { "Tap to ask every time" },
        value = app ?: "Ask every time",
    )
}

// ---------- Search ----------

/** Describe-it search, pushed from Settings: what it does, the one-time download and its progress, or Remove. */
@Composable
fun DescribeScreen(onBack: () -> Unit) = SettingsPage("Describe-it search", onBack) {
    Group { DescribeSearchRow(Modifier.padding(horizontal = Space.s4)) }
}

// ---------- Updates ----------

/** Updates, pushed from About: the installed version and the check for a newer one. */
@Composable
fun UpdatesScreen(onBack: () -> Unit) = SettingsPage("Updates", onBack) { UpdateSection() }

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val current = remember { Updater.currentVersion(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Group {
        GroupRow("Album $current", null, icon = Sym.SystemUpdate, sub = Updater.installMessage ?: status ?: "The version on this phone")
        // Download progress is information, not decoration, so it moves even with Remove animations on
        progress?.let { p -> ProgressLine(p, Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s4)) }
    }
    val available = release
    if (available != null && available.notes.isNotBlank()) {
        Group(title = "What's new in ${available.version}") { Padded { Text(available.notes, style = type.caption, color = c.ink) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        AlbumButton(
            if (available == null) "Check for updates" else "Update to ${available.version}",
            onClick = {
                scope.launch {
                    busy = true
                    failed = false
                    Updater.installMessage = null
                    if (available == null) {
                        CrashLog.note("check for updates")
                        status = "Checking…"
                        status = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }.fold(
                            onSuccess = { latest ->
                                when {
                                    latest == null -> "No downloadable update found"
                                    Updater.isNewer(latest.version, current) -> {
                                        release = latest
                                        "Version ${latest.version} is available (you have $current)"
                                    }
                                    else -> "You're on the latest version ($current)"
                                }
                            },
                            onFailure = { "Couldn't reach GitHub. Check your internet and try again." },
                        )
                    } else if (!context.packageManager.canRequestPackageInstalls()) {
                        // One-time Android permission: "Allow from this source" for Album
                        context.startSafely(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        status = "Turn on \"Allow from this source\", come back, and tap Update again"
                    } else {
                        CrashLog.note("update to ${available.version}")
                        status = "Downloading ${available.version}…"
                        progress = 0f
                        status = runCatching {
                            withContext(Dispatchers.IO) { Updater.downloadAndInstall(context, available) { progress = it } }
                        }.fold(
                            onSuccess = { "Confirm on the next screen. Album will close and update." },
                            onFailure = {
                                failed = true
                                "Update failed: ${it.message}"
                            },
                        )
                        progress = null
                    }
                    busy = false
                }
            },
            // The one primary button on this page, and only once there is an update to install
            variant = if (available == null) ButtonVariant.Tonal else ButtonVariant.Primary,
            icon = if (available == null) null else Sym.SystemUpdate,
            enabled = !busy,
        )
        // Only when the in-app install didn't work: the release page, to download it another way
        if (available != null && (failed || Updater.installMessage != null)) {
            AlbumButton(
                "Download page", { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(available.page))) },
                variant = ButtonVariant.Quiet, icon = Sym.OpenInNew,
            )
        }
    }
}

// ---------- About ----------

private const val LINKEDIN = "adityapandey78"
private const val EMAIL = "adityapandey.2402@gmail.com"

/** About, pushed from Settings: Album and its version, who made it and how to reach them, then Updates, Diagnostics and the licences. */
@Composable
fun AboutScreen(onBack: () -> Unit, onUpdates: () -> Unit, onDiagnostics: () -> Unit, onLicences: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val version = remember { Updater.currentVersion(context) }
    SettingsPage("About", onBack) {
        Column(Modifier.padding(top = Space.s8, bottom = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            AlbumLogo()
            Text("Version $version · everything runs on this phone", style = type.caption, color = c.inkMuted)
        }
        // Both open another app (browser, mail); Album itself still goes online only for updates
        Group(title = "Developed by Aditya Pandey") {
            GroupRow(
                "LinkedIn", { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/$LINKEDIN"))) },
                icon = Sym.Link, sub = LINKEDIN, trailing = Sym.OpenInNew,
            )
            GroupRow(
                "Email", { context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL"))) },
                icon = Sym.Mail, sub = EMAIL, trailing = Sym.OpenInNew,
            )
        }
        Group {
            GroupRow("Updates", onUpdates, icon = Sym.SystemUpdate, value = version)
            GroupRow("Diagnostics", onDiagnostics, icon = Sym.BugReport, sub = "Crash logs stay on this phone")
            GroupRow("Open-source licences", onLicences, icon = Sym.Info)
        }
    }
}

/**
 * Album's logo as the design draws it: three tiles and a flash dot on a 48 grid, then the wordmark, [height] tall in ink.
 * In light the dot has an ink ring; in dark and dim it's a little bigger with none.
 */
@Composable
private fun AlbumLogo(height: Dp = Space.s8) {
    val c = AlbumTheme.colors
    val light = resolvedTheme(AlbumTheme.spec.appearance.theme) == ThemeMode.Light
    val word = remember { PathParser().parsePathString(WORDMARK).toPath() }
    Canvas(Modifier.size(height * (LOGO_WIDTH / 48f), height).semantics { contentDescription = "Album" }) {
        scale(size.height / 48f, Offset.Zero) {
            listOf(Offset(2f, 2f), Offset(2f, 26f), Offset(26f, 26f)).forEach { drawRoundRect(c.ink, it, Size(20f, 20f), CornerRadius(5f)) }
            drawCircle(c.flash, if (light) 9f else 10f, Offset(36f, 12f))
            if (light) drawCircle(c.ink, 9f, Offset(36f, 12f), style = Stroke(2f))
            drawPath(word, c.ink)
        }
    }
}

/** The logo's width on its 48-high grid. */
private const val LOGO_WIDTH = 251.067f

// The "Album" wordmark, from the design's Logo
private const val WORDMARK =
    "M60.33 46 75.33 2H87.87L102.73 46H92.33L81.67 9.87H81.33L70.67 46ZM68.4 37.73V30.8H95.87V37.73Z" +
    "M105.53 46V-0.33H115.13V46ZM140.53 46.93Q137.47 46.93 135.17 45.57Q132.87 44.2 131.33 41.53" +
    "Q129.8 38.87 129.07 34.93H128.6L128.73 46H120.27L120.33 32.73L120.27 -0.33H129.93V9.67" +
    "Q129.93 11.47 129.77 13.6Q129.6 15.73 129.3 18.03Q129 20.33 128.6 22.8H129.2Q129.8 18.47 131.33 15.63" +
    "Q132.87 12.8 135.3 11.43Q137.73 10.07 140.93 10.07Q144.87 10.07 147.8 12.07Q150.73 14.07 152.33 18.07" +
    "Q153.93 22.07 153.93 28.13Q153.93 34.6 152.17 38.73Q150.4 42.87 147.33 44.9Q144.27 46.93 140.53 46.93Z" +
    "M137.33 39.13Q139.53 39.13 141.07 37.77Q142.6 36.4 143.4 34.07Q144.2 31.73 144.2 28.87" +
    "Q144.2 25.87 143.43 23.47Q142.67 21.07 141.13 19.63Q139.6 18.2 137.27 18.2Q136.07 18.2 134.9 18.67" +
    "Q133.73 19.13 132.83 20.03Q131.93 20.93 131.27 22.2Q130.6 23.47 130.23 25.03Q129.87 26.6 129.87 28.4" +
    "V28.87Q129.87 31.13 130.43 33.03Q131 34.93 132 36.3Q133 37.67 134.37 38.4Q135.73 39.13 137.33 39.13Z" +
    "M169.47 46.93Q163.67 46.93 160.87 42.83Q158.07 38.73 158.07 30.2V11H167.73V30.07" +
    "Q167.73 34.67 168.97 36.57Q170.2 38.47 172.67 38.47Q174.4 38.47 175.77 37.6Q177.13 36.73 178.17 35" +
    "Q179.2 33.27 179.73 30.53Q180.27 27.8 180.27 24V11H189.87V29.67V46H181.6L181.8 32H181.33" +
    "Q180.87 37.2 179.43 40.5Q178 43.8 175.53 45.37Q173.07 46.93 169.47 46.93ZM195 46V27.53L194.93 11H203.2" +
    "L203 25.8H203.53Q204 20.13 205.37 16.67Q206.73 13.2 209.07 11.63Q211.4 10.07 214.67 10.07" +
    "Q218 10.07 220.1 11.77Q222.2 13.47 223.17 16.93Q224.13 20.4 223.93 25.8H224.53Q224.93 20.2 226.4 16.73" +
    "Q227.87 13.27 230.27 11.67Q232.67 10.07 236.07 10.07Q238.8 10.07 240.8 11.07Q242.8 12.07 244.13 14.07" +
    "Q245.47 16.07 246.13 19.1Q246.8 22.13 246.8 26.27V46H237.2V27.13Q237.2 24.27 236.73 22.33" +
    "Q236.27 20.4 235.27 19.5Q234.27 18.6 232.73 18.6Q230.67 18.6 229.07 20.33Q227.47 22.07 226.53 25.47" +
    "Q225.6 28.87 225.6 33.93V46H216.2V26.87Q216.2 24 215.7 22.2Q215.2 20.4 214.2 19.5Q213.2 18.6 211.67 18.6" +
    "Q209.73 18.6 208.13 20.17Q206.53 21.73 205.57 25.13Q204.6 28.53 204.6 33.93V46Z"

private class Licence(val what: String, val from: String, val licence: String, val file: String, val icon: Sym)

// What Album ships or downloads, who made it, under which licence, and that licence's text (licenses/ in the repo, in the
// app's assets). GeoNames' CC BY 4.0 licence asks for credit people can see, not only in the repo.
private val Licences = listOf(
    Licence("Bricolage Grotesque font", "Bricolage Grotesque Project Authors", "SIL Open Font License 1.1", "bricolage-grotesque-OFL.txt", Sym.TextFields),
    Licence("Figtree font", "Figtree Project Authors", "SIL Open Font License 1.1", "figtree-OFL.txt", Sym.TextFields),
    Licence("Geist Mono font", "Geist Project Authors", "SIL Open Font License 1.1", "geist-mono-OFL.txt", Sym.TextFields),
    Licence("Inter font", "Inter Project Authors", "SIL Open Font License 1.1", "inter-OFL.txt", Sym.TextFields),
    Licence("Nunito font", "Nunito Project Authors", "SIL Open Font License 1.1", "nunito-OFL.txt", Sym.TextFields),
    Licence("Lora font", "Lora Project Authors", "SIL Open Font License 1.1", "lora-OFL.txt", Sym.TextFields),
    Licence("Material Symbols icons", "Google", "Apache License 2.0", "material-symbols-Apache-2.0.txt", Sym.Category),
    Licence("Face detection (YuNet)", "OpenCV Zoo", "MIT License", "yunet-MIT.txt", Sym.Face),
    Licence("Face recognition (EdgeFace-S)", "Idiap Research Institute", "CC BY-NC-SA 4.0, non-commercial", "edgeface-CC-BY-NC-SA-4.0.txt", Sym.Face),
    Licence("Place names", "GeoNames, geonames.org", "CC BY 4.0", "geonames-CC-BY-4.0.txt", Sym.LocationOn),
    Licence("Map", "Natural Earth, naturalearthdata.com", "Public domain", "natural-earth-public-domain.txt", Sym.Map),
    Licence("Photo hashing (PDQ)", "Meta ThreatExchange", "BSD License", "pdq-BSD.txt", Sym.ContentCopy),
    Licence("Describe-it search model", "Meta Perception Encoder, downloaded on request", "Apache License 2.0", "perception-encoder-Apache-2.0.txt", Sym.Search),
    Licence("Describe-it search vocabulary", "OpenAI CLIP", "MIT License", "clip-bpe-vocabulary-MIT.txt", Sym.MenuBook),
)

/** The open-source licences, pushed from About. Each opens its full text. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Licence?>(null) }
    open?.let { l ->
        TextPage(l.what, onBack = { open = null }) { context.assets.open(l.file).bufferedReader().use { it.readText() } }
        return
    }
    SettingsPage("Open-source licences", onBack) {
        Group { Licences.forEach { l -> GroupRow(l.what, { open = l }, icon = l.icon, sub = "${l.from} · ${l.licence}") } }
    }
}

// ---------- Diagnostics ----------

private fun crashLogs(count: Int) = when (count) {
    0 -> "No crash logs"
    1 -> "1 crash log"
    else -> "$count crash logs"
}

/** Diagnostics, pushed from About: the crash logs kept on this phone, to read, export or clear. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState() // above the log viewer's early return, so closing a log comes back to the same place
    var logs by remember { mutableStateOf<List<Pair<File, Long>>?>(null) } // null until read, so "No crash logs" never flashes
    var openLog by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { logs = withContext(Dispatchers.IO) { CrashLog.files().map { it to it.length() } } }

    openLog?.let { file ->
        TextPage(file.name, onBack = { openLog = null }) { file.readText() }
        return
    }
    SettingsPage("Diagnostics", onBack, scroll) {
        logs?.let { list ->
            Group(note = "Crash logs stay on this phone" + if (list.size > 1) ", newest first." else ".") {
                if (list.isEmpty()) GroupRow(crashLogs(0), null, icon = Sym.CheckCircle)
                list.forEach { (file, size) -> GroupRow(file.name, { openLog = file }, icon = Sym.BugReport, value = "${(size + 1023) / 1024} KB") }
            }
            if (list.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    AlbumButton("Export to Downloads", {
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { CrashLog.exportToDownloads(context) }
                            Messages.post(context, name?.let { "Saved to Downloads/$it" } ?: "No logs to export")
                        }
                    }, icon = Sym.Download)
                    AlbumButton("Clear logs", {
                        scope.launch {
                            withContext(Dispatchers.IO) { CrashLog.clear() }
                            logs = emptyList()
                        }
                    })
                }
            }
        }
        if (context.isDebuggable()) {
            AlbumButton("Test crash (debug builds only)", { error("Test crash from Settings") }, Modifier.offset(x = -Space.s3), variant = ButtonVariant.Quiet)
        }
    }
}

/** A page of plain, selectable text: a crash log or a licence. [read] runs off the main thread. */
@Composable
private fun TextPage(title: String, onBack: () -> Unit, read: () -> String) {
    val text by produceState("Loading…", title) {
        value = withContext(Dispatchers.IO) { runCatching(read).getOrElse { "Couldn't read this: ${it.message}" } }
    }
    SettingsPage(title, onBack) {
        SelectionContainer { Text(text, style = AlbumTheme.type.meta, color = AlbumTheme.colors.ink) }
    }
}
