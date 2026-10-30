package com.aditya.album

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.edit
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Album's design system in code: the tokens of the "Album" design system (tokens.json) and the four Appearance choices.
// Screens read colours, type and shape from AlbumTheme.colors / .type / .spec, never from literals.

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark"), Dim("Dim") }

enum class Accent(val label: String) { Lime("Lime"), Coral("Coral"), Sky("Sky"), Amber("Amber"), Mono("Mono"), Wallpaper("Wallpaper") }

/** Tile shape and spacing. Air is balanced, Edge square and edge to edge, Soft rounded with more air. */
enum class Look(val label: String, val blurb: String) { Air("Air", "Balanced"), Edge("Edge", "Square"), Soft("Soft", "Rounded") }

enum class GalleryStyle(val label: String, val blurb: String) {
    Grid("Grid", "Squares"), Justified("Justified", "True shapes"), Mosaic("Mosaic", "Best shot big"),
}

/** The typeface for titles and text, Settings > Appearance > Font. Numbers the camera made stay in Geist Mono. */
enum class AppFont(val label: String, val blurb: String) {
    Album("Album", "Default"), System("System", "Phone"), Inter("Inter", "Clean"), Nunito("Nunito", "Rounded"), Lora("Lora", "Serif"),
}

/** How tab content changes when you switch tabs in the bottom bar. */
enum class TabMotion(val label: String) {
    Depth("Depth"), Blur("Blur"), Instant("Instant"), Reveal("Reveal from the pill"), Pager("Swipe pager"), Slide("Slide (previous)"),
}

/** The shortest and longest a message can stay, in seconds (Settings, Motion and feel). */
const val MESSAGE_SECONDS_MIN = 0.3f
const val MESSAGE_SECONDS_MAX = 10f

/** How long a message stays until the user picks otherwise: the design's 5.2 s. */
const val MESSAGE_SECONDS_DEFAULT = 5.2f

/** The bottom bar's look. Dot is the default: icons in one capsule, a dot under the current tab, search after a hairline. */
enum class BarStyle(val label: String) { Dot("Dot"), Pill("Pill"), Labels("Labels"), Slider("Slider"), Classic("Classic") }

/** What the bottom bar does while the Library scrolls down: shrink to the current tab and search, hide, or stay. */
enum class BarScroll(val label: String) { Shrink("Shrink"), Hide("Hide"), Stay("Stay") }

/** Design lab: how messages after an action show. Chip rises above the bar; Island drops from the top; Snackbar is the bar. */
enum class MessageStyle(val label: String, val blurb: String) { Snackbar("Snackbar", "Full width"), Island("Island", "At the top"), Chip("Chip", "Above the bar") }

/** Design lab: For you as calm cards, or as story rings. */
enum class ForYouStyle(val label: String, val blurb: String) { Calm("Calm", "Big cards"), Stories("Stories", "Rings on top") }

/** Design lab: the video bar, clean or with a strip of frames. */
enum class VideoControls(val label: String, val blurb: String) { Clean("Clean", "One slim row"), Filmstrip("Filmstrip", "Frames and big play") }

/** Design lab: Settings groups on cards, or plain rows with hairlines. */
enum class SettingsStyle(val label: String, val blurb: String) { Grouped("Grouped", "Rows on cards"), Plain("Plain", "Hairlines") }

@Immutable
data class Appearance(
    val theme: ThemeMode = ThemeMode.System,
    val accent: Accent = Accent.Lime,
    val look: Look = Look.Air,
    val gallery: GalleryStyle = GalleryStyle.Grid,
    val tabMotion: TabMotion = TabMotion.Depth,
    val font: AppFont = AppFont.Album,
    val bar: BarStyle = BarStyle.Dot,
    val barScroll: BarScroll = BarScroll.Shrink,
    /** Remove animations: null follows the phone's setting, true or false is the in-app override. */
    val still: Boolean? = null,
    val haptics: Boolean = true,
    /** Shows Recently deleted at the end of Collections. Settings, under Deleting, always has it. */
    val trashInCollections: Boolean = true,
    /** How long a message (and its Undo) stays: [MESSAGE_SECONDS_MIN] to [MESSAGE_SECONDS_MAX]. */
    val messageSeconds: Float = MESSAGE_SECONDS_DEFAULT,
    val messageStyle: MessageStyle = MessageStyle.Chip,
    val forYouStyle: ForYouStyle = ForYouStyle.Calm,
    val videoControls: VideoControls = VideoControls.Clean,
    val settingsStyle: SettingsStyle = SettingsStyle.Grouped,
)

/** The Appearance settings, saved in the "settings" preferences and applied live. */
object AppearancePrefs {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(Appearance())
    val appearance = state.asStateFlow()

    /** Call before the first frame so the app never flashes the default theme. Safe to call repeatedly. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        fun <T : Enum<T>> read(key: String, all: Array<T>, default: T) = all.find { it.name == prefs.getString(key, null) } ?: default
        // v0.1 to v0.7 had three "looks": Clean becomes Edge, Bold becomes Soft, Material You becomes the Wallpaper accent
        val old = prefs.getString("look", null)
        state.value = Appearance(
            theme = read("theme", ThemeMode.entries.toTypedArray(), ThemeMode.System),
            accent = read("accent", Accent.entries.toTypedArray(), if (old == "You") Accent.Wallpaper else Accent.Lime),
            look = read("look", Look.entries.toTypedArray(), if (old == "Clean") Look.Edge else if (old == "Bold") Look.Soft else Look.Air),
            gallery = read("gallery", GalleryStyle.entries.toTypedArray(), GalleryStyle.Grid),
            tabMotion = read("tab_motion", TabMotion.entries.toTypedArray(), TabMotion.Depth),
            font = read("font", AppFont.entries.toTypedArray(), AppFont.Album),
            bar = read("bar_style", BarStyle.entries.toTypedArray(), BarStyle.Dot),
            barScroll = read("bar_scroll", BarScroll.entries.toTypedArray(), BarScroll.Shrink),
            still = if (prefs.contains("still")) prefs.getBoolean("still", false) else null,
            haptics = prefs.getBoolean("haptics", true),
            // Before 1.1.2 a "hide private places" switch left it out
            trashInCollections = prefs.getBoolean("trash_in_collections", !prefs.getBoolean("hide_private", false)),
            messageSeconds = prefs.getFloat("message_seconds", MESSAGE_SECONDS_DEFAULT).coerceIn(MESSAGE_SECONDS_MIN, MESSAGE_SECONDS_MAX),
            messageStyle = read("message_style", MessageStyle.entries.toTypedArray(), MessageStyle.Chip),
            forYouStyle = read("for_you_style", ForYouStyle.entries.toTypedArray(), ForYouStyle.Calm),
            videoControls = read("video_controls", VideoControls.entries.toTypedArray(), VideoControls.Clean),
            settingsStyle = read("settings_style", SettingsStyle.entries.toTypedArray(), SettingsStyle.Grouped),
        )
    }

    fun update(change: (Appearance) -> Appearance) {
        val a = change(state.value)
        state.value = a
        prefs.edit {
            putString("theme", a.theme.name)
            putString("accent", a.accent.name)
            putString("look", a.look.name)
            putString("gallery", a.gallery.name)
            putString("tab_motion", a.tabMotion.name)
            putString("font", a.font.name)
            putString("bar_style", a.bar.name)
            putString("bar_scroll", a.barScroll.name)
            putBoolean("haptics", a.haptics)
            putBoolean("trash_in_collections", a.trashInCollections)
            putFloat("message_seconds", a.messageSeconds)
            putString("message_style", a.messageStyle.name)
            putString("for_you_style", a.forYouStyle.name)
            putString("video_controls", a.videoControls.name)
            putString("settings_style", a.settingsStyle.name)
            if (a.still == null) remove("still") else putBoolean("still", a.still)
        }
        CrashLog.note("appearance ${a.theme} ${a.accent} ${a.look} ${a.gallery} ${a.tabMotion} ${a.font} ${a.bar} ${a.barScroll} still=${a.still}")
    }
}

/**
 * Design lab > Speed: the duration scale of every Compose animation in Album. MainActivity hands it to the window's
 * recomposer, and Compose reads it each time an animation starts, so Slow motion stretches all of them 4× on top of the
 * phone's animator duration scale. Animations already running keep their speed. Not saved: Album opens at normal speed.
 */
object AlbumMotion : MotionDurationScale {
    var slow by mutableStateOf(false)
    override val scaleFactor: Float get() = ValueAnimator.getDurationScale() * if (slow) 4f else 1f
}

/** Colour roles. Names match the design system's tokens; see its README for which goes where. */
@Immutable
data class AlbumColors(
    val canvas: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    /** The accent as a fill (primary button, active bottom-bar pill, selection check). Never text. */
    val flash: Color,
    val onFlash: Color,
    /** The accent as text or a thin mark (Undo, links, a selected chip's check). */
    val flashInk: Color,
    val flashSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDanger: Color,
    /**
     * Floating chrome: bottom bar, dock, selection bar, messages. The design's 72 to 78%, over a 24dp blur of what's
     * behind where the screen provides one ([LocalBackdrop]); see [Modifier.floating].
     */
    val glass: Color,
    val scrim: Color,
    val dark: Boolean,
) {
    /** Fades under text on photos (tile footers, viewer top bar). 0.56 keeps white text readable on a white photo. */
    val scrimPhoto get() = Color(0x8F000000)
    val onPhoto get() = Color.White

    /** The viewer and video background in every theme. */
    val void get() = Color.Black
}

internal val Ink = Color(0xFF111110)
internal val Paper = Color(0xFFF4F4F0)

private fun base(mode: ThemeMode) = when (mode) {
    ThemeMode.Dark -> AlbumColors(
        canvas = Color.Black, surface = Color(0xFF121212), surfaceHigh = Color(0xFF1F1F1F), line = Color(0xFF2C2C2C),
        lineStrong = Color(0xFF6B6B66), ink = Paper, inkMuted = Color(0xFFA3A39C), flash = Color.Unspecified,
        onFlash = Color.Unspecified, flashInk = Color.Unspecified, flashSoft = Color.Unspecified, danger = Color(0xFFFF8A7A),
        dangerSoft = Color(0xFF2B0E0A), onDanger = Ink, glass = Color(0xB81C1C1C), scrim = Color(0xA3000000), dark = true,
    )
    ThemeMode.Dim -> AlbumColors(
        canvas = Color(0xFF151515), surface = Color(0xFF1E1E1E), surfaceHigh = Color(0xFF2A2A2A), line = Color(0xFF383838),
        lineStrong = Color(0xFF7A7A74), ink = Paper, inkMuted = Color(0xFFABABA4), flash = Color.Unspecified,
        onFlash = Color.Unspecified, flashInk = Color.Unspecified, flashSoft = Color.Unspecified, danger = Color(0xFFFF8A7A),
        dangerSoft = Color(0xFF3A1510), onDanger = Ink, glass = Color(0xBD282828), scrim = Color(0x99000000), dark = true,
    )
    else -> AlbumColors(
        canvas = Color(0xFFF6F6F3), surface = Color.White, surfaceHigh = Color(0xFFEBEBE6), line = Color(0xFFD9D9D2),
        lineStrong = Color(0xFF8C8C84), ink = Ink, inkMuted = Color(0xFF5E5E58), flash = Color.Unspecified,
        onFlash = Color.Unspecified, flashInk = Color.Unspecified, flashSoft = Color.Unspecified, danger = Color(0xFFC42B1C),
        dangerSoft = Color(0xFFFBE7E4), onDanger = Color.White, glass = Color(0xC7FFFFFF), scrim = Color(0x7A000000), dark = false,
    )
}

/** The flash role (fill, on-fill, ink, soft) for an accent in a theme. Wallpaper is handled with the dynamic scheme. */
private fun flashOf(accent: Accent, mode: ThemeMode): List<Color> {
    val light = mode == ThemeMode.Light
    val dim = mode == ThemeMode.Dim
    fun pick(l: Long, d: Long, m: Long = d) = Color(if (light) l else if (dim) m else d)
    return when (accent) {
        Accent.Coral -> listOf(Color(0xFFFF7A59), Ink, pick(0xFFB33A1B, 0xFFFF7A59, 0xFFFF8F73), pick(0xFFFFE6DE, 0xFF2E1209, 0xFF3B1A10))
        Accent.Sky -> listOf(Color(0xFF7CC6FF), Ink, pick(0xFF0B5E9E, 0xFF7CC6FF, 0xFF8FCFFF), pick(0xFFE0F1FF, 0xFF0A2133, 0xFF12293B))
        Accent.Amber -> listOf(Color(0xFFFFC53D), Ink, pick(0xFF7A5200, 0xFFFFC53D, 0xFFFFCC55), pick(0xFFFFF2CC, 0xFF2B2000, 0xFF372A08))
        Accent.Mono -> listOf(if (light) Ink else Paper, if (light) Color.White else Ink, if (light) Ink else Paper, pick(0xFFE2E2DC, 0xFF2A2A2A, 0xFF363636))
        else -> listOf(Color(0xFFD7F94A), Ink, pick(0xFF4A6200, 0xFFD7F94A), pick(0xFFEEF8C8, 0xFF1E2508, 0xFF2A3310))
    }
}

fun albumColors(mode: ThemeMode, accent: Accent, dynamic: ColorScheme? = null): AlbumColors {
    val c = base(mode)
    if (accent == Accent.Wallpaper && dynamic != null) {
        return c.copy(flash = dynamic.primaryContainer, onFlash = dynamic.onPrimaryContainer, flashInk = dynamic.primary, flashSoft = dynamic.secondaryContainer)
    }
    val (flash, on, ink, soft) = flashOf(accent, mode)
    return c.copy(flash = flash, onFlash = on, flashInk = ink, flashSoft = soft)
}

// Variable fonts bundled in res/font so they work offline: Bricolage Grotesque (display), Figtree (sans), Geist Mono (mono),
// and the Font choices Inter, Nunito and Lora (tools/fonts.py).
private fun variable(res: Int, vararg weights: Int, opsz: Float? = null) = FontFamily(
    weights.map { w ->
        val axes = listOfNotNull(FontVariation.weight(w), opsz?.let { FontVariation.Setting("opsz", it) })
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(*axes.toTypedArray()))
    }
)

// Bricolage's optical size follows the text size, as browsers do on their own: tighter and bolder-looking when big
private val bricolage52 = variable(R.font.bricolage_grotesque, 700, opsz = 52f)
private val bricolage34 = variable(R.font.bricolage_grotesque, 700, opsz = 34f)
private val bricolage24 = variable(R.font.bricolage_grotesque, 600, opsz = 24f)
val Figtree = variable(R.font.figtree, 400, 500, 600, 700)
val GeistMono = variable(R.font.geist_mono, 500)
private val inter = variable(R.font.inter, 400, 500, 600, 700)
private val interDisplay = variable(R.font.inter, 600, 700, opsz = 32f) // Inter's display cut, for titles
private val nunito = variable(R.font.nunito, 400, 500, 600, 700)
private val lora = variable(R.font.lora, 400, 500, 600, 700)

/**
 * The text styles of the design system. Sizes are sp, so they follow the phone's font size. [font] is the person's choice:
 * Album pairs Bricolage titles with Figtree text (Edge sets those titles in Figtree too); the others use one family.
 */
@Immutable
class AlbumType(private val edge: Boolean, private val font: AppFont = AppFont.Album) {
    private val text = when (font) {
        AppFont.Album -> Figtree
        AppFont.System -> FontFamily.Default
        AppFont.Inter -> inter
        AppFont.Nunito -> nunito
        AppFont.Lora -> lora
    }
    private fun titles(album: FontFamily) = when {
        font == AppFont.Album -> if (edge) Figtree else album
        font == AppFont.Inter -> interDisplay
        else -> text
    }

    /** One big moment per screen: memories, recaps. */
    val display = TextStyle(fontFamily = titles(bricolage52), fontSize = 52.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    /** Expanded screen titles. */
    val titleXl = TextStyle(fontFamily = titles(bricolage34), fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)
    /** Album, person and sheet titles. */
    val titleLg = TextStyle(fontFamily = titles(bricolage24), fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    /** Date headers, collapsed titles, card titles. */
    val titleMd = TextStyle(fontFamily = text, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = text, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** Buttons, chips, album names. */
    val label = TextStyle(fontFamily = text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    /** Places under a date, helper lines. */
    val caption = TextStyle(fontFamily = text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    /** Numbers the camera made: durations, counts, sizes, dates in the scrubber. Tabular figures. */
    val meta = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    /** The EXIF line in the details sheet. */
    val metaLg = TextStyle(fontFamily = GeistMono, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}


/** Spacing, radii and sizes from the design system. Gutter and tile corner depend on the Look; see [AlbumSpec]. */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
    /** Touch targets stay this far from a curved display edge. */
    val edgeSafe = 20.dp
    /** Minimum touch target. */
    val target = 48.dp
    /** Height of the bottom bar, selection bar and viewer dock. */
    val bar = 64.dp
    val icon = 24.dp
    val iconSm = 20.dp
    val check = 24.dp
    /** Covers, cards and a selected tile. */
    val radiusMd = 16.dp
    /** Sheets, the confirmation bar. */
    val radiusLg = 28.dp
}

@Immutable
class AlbumSpec(
    val colors: AlbumColors,
    val type: AlbumType,
    val appearance: Appearance,
    /** Gap between grid tiles. */
    val gutter: Dp,
    /** Grid tile corners at rest. */
    val tileCorner: Dp,
    /** Remove animations is on (in Album, or on the phone): screens crossfade, nothing slides or zooms. */
    val still: Boolean,
)

private val LocalAlbum = staticCompositionLocalOf<AlbumSpec> { error("AlbumTheme is missing") }

object AlbumTheme {
    val colors: AlbumColors @Composable @ReadOnlyComposable get() = LocalAlbum.current.colors
    val type: AlbumType @Composable @ReadOnlyComposable get() = LocalAlbum.current.type
    val spec: AlbumSpec @Composable @ReadOnlyComposable get() = LocalAlbum.current
}

/** The phone's "Remove animations" (animator duration scale 0). */
fun phoneAnimationsOff(context: Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** [phoneAnimationsOff], kept up to date while Album is open: turning it on in the phone's settings applies at once. */
@Composable
private fun rememberPhoneAnimationsOff(): Boolean {
    val context = LocalContext.current
    var off by remember { mutableStateOf(phoneAnimationsOff(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off = phoneAnimationsOff(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        off = phoneAnimationsOff(context) // it may have changed between the first read and now
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return off
}

/** Which theme is actually showing: System resolves to Light or Dark (true black). */
@Composable
fun resolvedTheme(mode: ThemeMode): ThemeMode {
    val systemDark = isSystemInDarkTheme()
    return if (mode == ThemeMode.System) if (systemDark) ThemeMode.Dark else ThemeMode.Light else mode
}

@Composable
fun AlbumTheme(content: @Composable () -> Unit) {
    val a by AppearancePrefs.appearance.collectAsState()
    AlbumTheme(a, content)
}

@Composable
fun AlbumTheme(a: Appearance, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = resolvedTheme(a.theme)
    val dynamic = if (a.accent == Accent.Wallpaper) {
        if (mode == ThemeMode.Light) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else null
    val colors = remember(mode, a.accent, dynamic) { albumColors(mode, a.accent, dynamic) }
    val phoneStill = rememberPhoneAnimationsOff()
    val still = a.still ?: phoneStill
    val spec = AlbumSpec(
        colors = colors,
        type = remember(a.look, a.font) { AlbumType(edge = a.look == Look.Edge, a.font) },
        appearance = a,
        gutter = when (a.look) { Look.Edge -> 1.dp; Look.Soft -> 6.dp; Look.Air -> 2.dp },
        tileCorner = when (a.look) { Look.Edge -> 0.dp; Look.Soft -> 12.dp; Look.Air -> 4.dp },
        still = still,
    )
    CompositionLocalProvider(LocalAlbum provides spec) {
        // Material components that are still used (text fields, bottom sheets, menus) take their colours from the same tokens
        MaterialTheme(colorScheme = remember(colors) { materialScheme(colors) }, typography = remember(spec.type) { materialType(spec.type) }) {
            // Screens drawn over the Scaffold aren't inside a Surface; without this their text would default to black
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

private fun materialScheme(c: AlbumColors): ColorScheme {
    val s = if (c.dark) darkColorScheme() else lightColorScheme()
    return s.copy(
        primary = c.flashInk, onPrimary = c.canvas, primaryContainer = c.flash, onPrimaryContainer = c.onFlash,
        secondary = c.flashInk, secondaryContainer = c.flashSoft, onSecondaryContainer = c.ink,
        background = c.canvas, onBackground = c.ink, surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.inkMuted, surfaceContainerLowest = c.canvas, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh, surfaceBright = c.surfaceHigh, surfaceDim = c.canvas,
        inverseSurface = c.ink, inverseOnSurface = c.canvas, inversePrimary = c.flash,
        outline = c.lineStrong, outlineVariant = c.line, error = c.danger, onError = c.onDanger, errorContainer = c.dangerSoft,
        onErrorContainer = c.danger, scrim = c.scrim,
    )
}

private fun materialType(t: AlbumType) = Typography(
    displayLarge = t.display, displayMedium = t.titleXl, displaySmall = t.titleXl,
    headlineLarge = t.titleXl, headlineMedium = t.titleLg, headlineSmall = t.titleLg,
    titleLarge = t.titleLg, titleMedium = t.titleMd, titleSmall = t.label,
    bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
    labelLarge = t.label, labelMedium = t.caption, labelSmall = t.caption,
)
