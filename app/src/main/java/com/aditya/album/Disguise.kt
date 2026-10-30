package com.aditya.album

import android.app.ActivityManager.TaskDescription
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

// Disguise: Album can look like an app found on most work phones (a calculator, clock, compass, notes, files, scanner,
// authenticator or work hub), so it isn't seen as a gallery. LauncherIcon switches the home-screen entry and its name;
// this file has the choice, the gate that shows the disguise's front instead of Album, and the Settings page.

/**
 * A look for the home screen, each an app found on most work phones, in the design's order with Album's extra four after.
 * [label] is the name under its icon and in recent apps; [hold] is what to press and hold on its first screen to open
 * Album, and for how long (the calculator takes the PIN and = instead). Icons from tools/app_icon.py.
 */
enum class Disguise(val label: String, val hold: String, val icon: Int, val background: Int, val foreground: Int) {
    Calculator("Calculator", "", R.mipmap.ic_disguise_calculator, R.drawable.ic_disguise_calculator_background, R.drawable.ic_disguise_calculator_foreground),
    Notes("Notes", "the title for 2 seconds", R.mipmap.ic_disguise_notes, R.drawable.ic_disguise_notes_background, R.drawable.ic_disguise_notes_foreground),
    Files("Files", "the title for 2 seconds", R.mipmap.ic_disguise_files, R.drawable.ic_disguise_files_background, R.drawable.ic_disguise_files_foreground),
    Workspace("Workspace", "the title for 2 seconds", R.mipmap.ic_disguise_workspace, R.drawable.ic_disguise_workspace_background, R.drawable.ic_disguise_workspace_foreground),
    Clock("Clock", "the time for 2 seconds", R.mipmap.ic_disguise_clock, R.drawable.ic_disguise_clock_background, R.drawable.ic_disguise_clock_foreground),
    Compass("Compass", "the dial for 2 seconds", R.mipmap.ic_disguise_compass, R.drawable.ic_disguise_compass_background, R.drawable.ic_disguise_compass_foreground),
    Scanner("Scanner", "the scan button", R.mipmap.ic_disguise_scanner, R.drawable.ic_disguise_scanner_background, R.drawable.ic_disguise_scanner_foreground),
    Authenticator("Authenticator", "the code for 2 seconds", R.mipmap.ic_disguise_authenticator, R.drawable.ic_disguise_authenticator_background, R.drawable.ic_disguise_authenticator_foreground),
}

/** The disguise chosen in Settings, saved in the "disguise" preferences, and whether Album is open past it. */
object DisguisePrefs {
    private const val CHOSEN = "chosen"
    private const val CODE_SECRET = "code_secret"
    private lateinit var prefs: SharedPreferences
    private val chosenNow = MutableStateFlow<Disguise?>(null)

    /** The disguise chosen, or null for Album's own icon. */
    val chosen = chosenNow.asStateFlow()

    private val openNow = MutableStateFlow(false)

    /** Album shows past the disguise's front: from the right PIN (or choosing a disguise) until it leaves the screen. */
    val open = openNow.asStateFlow()

    /** Call before the first frame, so a disguised Album never flashes its own screen. Safe to call repeatedly. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("disguise", Context.MODE_PRIVATE)
        chosenNow.value = Disguise.entries.find { it.name == prefs.getString(CHOSEN, null) }
    }

    /**
     * The disguise in force. Only the privacy PIN opens it, so without one (a reset safe takes its PIN with it) it turns
     * itself off rather than shut you out, and it stays off when a PIN is set again.
     */
    fun active(context: Context): Disguise? {
        load(context)
        PrivacyLock.init(context)
        if (chosen.value != null && !PrivacyLock.setUp.value) {
            CrashLog.note("disguise off: no pin")
            choose(null)
        }
        return chosen.value
    }

    /** Whether a disguise is chosen, for work outside Album's screens (a notification would name it). */
    fun isChosen(context: Context): Boolean {
        load(context)
        return chosen.value != null
    }

    /** Chosen in Settings, so from inside Album: it stays open until it leaves the screen, and the icon switches then. */
    fun choose(disguise: Disguise?) {
        chosenNow.value = disguise
        openNow.value = true
        prefs.edit { if (disguise == null) remove(CHOSEN) else putString(CHOSEN, disguise.name) }
        CrashLog.note("disguise ${disguise?.name ?: "off"}")
    }

    fun unlock() {
        openNow.value = true
    }

    /** The Authenticator disguise's secret: random, made once and kept on this phone, so its codes are real but open nothing. */
    fun codeSecret(): ByteArray {
        prefs.getString(CODE_SECRET, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val secret = ByteArray(20).also(SecureRandom()::nextBytes)
        prefs.edit { putString(CODE_SECRET, Base64.encodeToString(secret, Base64.NO_WRAP)) }
        return secret
    }

    fun lock() {
        openNow.value = false
    }
}

/** What the Calculator disguise treats as a PIN: a bare number of the PIN's [length], as typed, else null. */
fun pinAttempt(typed: String, length: Int?) = typed.takeIf { length != null && it.length == length && isPin(it) }

/**
 * Album's screen, or while disguised the disguise's front until the PIN is right. The front comes back each time Album
 * leaves the screen. Meanwhile recent apps show the disguise's name and no preview of Album.
 */
@Composable
fun DisguiseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    remember { DisguisePrefs.active(context) } // reads the choice before the first frame
    val disguise by DisguisePrefs.chosen.collectAsState()
    val setUp by PrivacyLock.setUp.collectAsState()
    val open by DisguisePrefs.open.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Turning the phone only rebuilds the screen, so Album stays open
        if (activity?.isChangingConfigurations != true) DisguisePrefs.lock()
    }
    DisposableEffect(activity, disguise) {
        // Opened from the disguise's entry, Android already uses its name; this covers Album opened any other way (a
        // notification from before the disguise) and hides the snapshot recent apps would show of Album's own screen
        activity?.setRecentsScreenshotEnabled(disguise == null)
        activity?.setTaskDescription(TaskDescription.Builder().apply { disguise?.let { setLabel(it.label).setIcon(it.icon) } }.build())
        onDispose {}
    }
    // Without a PIN nothing could open the front: DisguisePrefs.active turns the disguise off when Album next leaves
    val d = disguise
    if (d == null || open || !setUp) content() else DisguiseFront(d)
}

/**
 * The disguise as it opens: the calculator or another app's first screen (DisguiseFronts.kt), both under a plain theme so
 * nothing of Album's style shows: the phone's own font and no accent, always dark for the calculator and light for the
 * others, as those apps are, with the status bar to match. Both lead to the PIN, full screen in Album's own font and
 * accent on the front's light or dark.
 */
@Composable
private fun DisguiseFront(disguise: Disguise) {
    val context = LocalContext.current
    val window = LocalActivity.current?.window
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val pinLength by produceState<Int?>(null) { value = withContext(Dispatchers.IO) { PrivacyLock.pinLength() } }
    val dark = disguise == Disguise.Calculator
    val albumDark by rememberUpdatedState(AlbumTheme.colors.dark)
    // The bars' icons follow the front, and Album's theme again once it's gone. Set after MainActivity's own, which runs first.
    val bars = remember(window) { window?.let { WindowCompat.getInsetsController(it, it.decorView) } }
    LaunchedEffect(bars, dark) {
        bars?.isAppearanceLightStatusBars = !dark
        bars?.isAppearanceLightNavigationBars = !dark
    }
    DisposableEffect(bars) {
        onDispose {
            bars?.isAppearanceLightStatusBars = !albumDark
            bars?.isAppearanceLightNavigationBars = !albumDark
        }
    }
    val own = AlbumTheme.spec.appearance.copy(theme = if (dark) ThemeMode.Dark else ThemeMode.Light)
    val plain = own.copy(accent = Accent.Mono, font = AppFont.System)
    AlbumTheme(plain) {
        if (disguise == Disguise.Calculator) {
            CalculatorScreen(pinLength, onPin = { pin ->
                scope.launch {
                    // A wrong PIN counts toward the waits as on the pad, but the calculator only ever shows the number
                    val r = PrivacyLock.check(pin)
                    if (r is PinCheck.Right) {
                        CrashLog.note("disguise opened")
                        PrivacyLock.afterPin(context)
                        PrivacyLock.open(r.key)
                        DisguisePrefs.unlock()
                    }
                }
            }, onHold = { asking = true })
        } else {
            QuietFront(disguise) { asking = true }
        }
    }
    // The design fades the PIN in over 160 ms
    AnimatedVisibility(asking, enter = fadeIn(tween(if (AlbumTheme.spec.still) 0 else 160)), exit = ExitTransition.None) {
        AlbumTheme(own) {
            FrontPin(onDone = {
                asking = false
                DisguisePrefs.unlock()
            }, onCancel = { asking = false })
        }
    }
}

/** Settings > Privacy and safe: opens [DisguiseScreen], with the look in use: the disguise's name, or Album. */
@Composable
fun DisguiseRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { DisguisePrefs.load(context) }
    val chosen by DisguisePrefs.chosen.collectAsState()
    GroupRow(
        "Disguise", onOpen, modifier, icon = Sym.Masks,
        sub = "Look like another app on your home screen", value = chosen?.label ?: "Album",
    )
}

/**
 * Settings > Privacy and safe > Disguise: a grid of looks (Off, Album's own, or a disguise) with a preview of the home
 * screen, how to get in, and a way to try the front. Turning one on first says how to get back in, and both on and off ask
 * for the privacy PIN (setting one first if there's none).
 */
@Composable
fun DisguiseScreen(onBack: () -> Unit) = SettingsPage("Disguise", onBack, gap = Space.s2) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    remember { DisguisePrefs.load(context) }
    val chosen by DisguisePrefs.chosen.collectAsState()
    val setUp by PrivacyLock.setUp.collectAsState()
    val fingerprint = remember(setUp) { PrivacyLock.fingerprintReady(context) }
    var confirming by remember { mutableStateOf<Disguise?>(null) }
    var asking by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun pick(disguise: Disguise?) {
        when {
            disguise == chosen -> {}
            disguise == null -> asking = CHANGE_IT to {
                DisguisePrefs.choose(null)
                PrivacyLock.notices.trySend(PrivacyLock.Notice("Album looks like Album again after you leave it"))
            }
            else -> confirming = disguise
        }
    }

    Text("Album can look like another app on your home screen. Your photos stay locked behind your PIN.", style = type.body, color = c.inkMuted)
    if (!setUp) Text("Only your privacy PIN opens a disguised Album, so choosing a disguise sets one first.", style = type.body, color = c.inkMuted)
    Group(Modifier.padding(top = Space.s4), title = "Look like") { DisguiseGrid(chosen, ::pick) }
    Group(
        Modifier.padding(top = Space.s4), title = "On your home screen",
        note = chosen?.let { "The icon and name switch when Album leaves the screen. Android's own settings still call it Album." },
    ) {
        HomePreview(chosen)
        chosen?.let { GroupRow("How to get in", null, icon = Sym.Login, sub = howToOpen(it, fingerprint)) }
    }
    if (chosen != null) {
        // Shows the front now, as Album will open next time; the PIN (or = on the calculator) opens Album again, at its start.
        // Inset like the design's wrapped button, 48dp under the group
        AlbumButton("Try the front screen", {
            CrashLog.note("disguise try")
            DisguisePrefs.lock()
        }, Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = 40.dp), icon = Sym.Visibility)
    }
    Group(Modifier.padding(top = Space.s4), title = "Good to know") {
        GroupRow("Home screen", null, icon = Sym.Home, sub = DRAWER_NOTE)
        GroupRow("Recent apps", null, icon = Sym.History, sub = "They show the disguise's name, with no preview of Album.")
        GroupRow(
            "Other apps and the widget", null, icon = Sym.OpenInNew,
            sub = "While disguised, Album isn't offered in Open with for other apps' photos, the Memories widget is removed, and " +
                "organising photos shows no notification.",
        )
        GroupRow("Android settings", null, icon = Sym.Settings, sub = "Apps, notifications, storage and battery still call it Album.")
    }

    confirming?.let { d ->
        ConfirmBar(
            title = "Disguise Album as ${d.label}?",
            // Two sentences at most: the home-screen and widget notes are on the page itself, under Good to know
            body = (if (setUp) "" else "First, set a privacy PIN. ") + "When you leave Album, its icon and name change to ${d.label}. " +
                howToOpen(d, fingerprint),
            confirmLabel = if (setUp) "Disguise" else "Set a PIN",
            onConfirm = {
                confirming = null
                asking = CHANGE_IT to {
                    DisguisePrefs.choose(d)
                    PrivacyLock.notices.trySend(PrivacyLock.Notice("Album will look like ${d.label} after you leave it"))
                }
            },
            onCancel = { confirming = null },
            icon = Sym.VisibilityOff,
            tone = ConfirmTone.Flash,
        )
    }
    // Always the PIN, even while privacy is unlocked: this changes what anyone sees of Album on the home screen
    asking?.let { (title, action) ->
        PrivacySheet(title, onDone = {
            asking = null
            action()
        }, onDismiss = { asking = null })
    }
}

private const val DRAWER_NOTE = "Some home screens move the new icon to the app drawer. Drag it back from there."

/** What the PIN sheet for turning a disguise on or off says it's for: "Needed to change the disguise." */
private const val CHANGE_IT = "Change the disguise"

/** How to get past [d]'s front; the fingerprint only when the PIN pad offers it. */
private fun howToOpen(d: Disguise, fingerprint: Boolean) = when (d) {
    Disguise.Calculator -> "Type your PIN, then =." + if (fingerprint) " Or hold = to use your fingerprint." else ""
    else -> "Press and hold ${d.hold}, then enter your PIN."
}

/** The looks to choose from, three across: Off (Album's own icon) and each disguise, the chosen one outlined in flash-ink. */
@Composable
private fun DisguiseGrid(chosen: Disguise?, onPick: (Disguise?) -> Unit) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusLg)
    Column(Modifier.padding(Space.s4).selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        (listOf<Disguise?>(null) + Disguise.entries).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                row.forEach { d ->
                    val on = d == chosen
                    Column(
                        Modifier.weight(1f).clip(shape).border(2.dp, if (on) c.flashInk else Color.Transparent, shape)
                            .background(if (on) c.flashSoft else Color.Transparent).selectable(on, role = Role.RadioButton, onClick = { onPick(d) })
                            .padding(horizontal = Space.s1, vertical = Space.s3),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.s2),
                    ) {
                        HomeIcon(d, 52.dp)
                        // "Authenticator" shrinks a little rather than lose its end
                        BasicText(
                            d?.label ?: "Off", style = AlbumTheme.type.label.copy(color = c.ink), maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = AlbumTheme.type.label.fontSize),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A home screen with three other apps and [d]'s icon and name (Album's own when null); the icon pops in as it changes. */
@Composable
private fun HomePreview(d: Disguise?) {
    val name = d?.label ?: "Album"
    val still = AlbumTheme.spec.still
    val pop = remember(d) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(d) { pop.animateTo(1f, tween(260, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.4f))) }
    Row(
        Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3).clip(RoundedCornerShape(Space.radiusLg))
            .background(Brush.linearGradient(listOf(Color(0xFF3D4A5C), Color(0xFF8F7A68))))
            .padding(horizontal = Space.s2, vertical = Space.s4).clearAndSetSemantics { contentDescription = "Home screen preview: $name" },
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        listOf("Camera", "Clock", "Maps").forEach { HomeApp(it) { Box(Modifier.size(44.dp).background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(14.dp))) } }
        HomeApp(name) {
            // The design's pop: from 0.8 and clear, overshooting a little on the way to full size
            Box(Modifier.graphicsLayer {
                val scale = 0.8f + 0.2f * pop.value
                scaleX = scale
                scaleY = scale
                alpha = pop.value.coerceIn(0f, 1f)
            }) { HomeIcon(d, 44.dp) }
        }
    }
}

/** An app on [HomePreview]'s home screen: its [icon] and [name] in white, as a launcher shows them. */
@Composable
private fun HomeApp(name: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Column(modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        icon()
        Text(name, style = AlbumTheme.type.caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [d]'s icon at [size] as the home screen shows it, or Album's own (the logo on its near-black tile) when null. */
@Composable
private fun HomeIcon(d: Disguise?, size: Dp) {
    if (d != null) return DisguiseIcon(d, size)
    Box(Modifier.size(size).shadow(1.dp, iconShape(size)).clip(iconShape(size)).background(Ink).clearAndSetSemantics {}) { LogoMark(size = size) }
}

/** A launcher icon's rounded square, as the design draws them: radius 16 on 52. */
private fun iconShape(size: Dp) = RoundedCornerShape(size * 16 / 52)

/**
 * [d]'s launcher icon at [size], with the design's slight shadow. Launchers show the middle 72 of an icon's 108dp, so its
 * layers are drawn 1.5 times as big.
 */
@Composable
internal fun DisguiseIcon(d: Disguise, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).shadow(1.dp, iconShape(size)).clip(iconShape(size)), contentAlignment = Alignment.Center) {
        Image(painterResource(d.background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(d.foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}
