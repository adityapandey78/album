package com.aditya.album

import android.app.ActivityManager.TaskDescription
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
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
 * A look for the home screen, each an app found on most work phones. [label] is the name under its icon and in recent
 * apps; [hold] is what to hold on its first screen to open Album (the calculator takes the PIN and = instead). Icons
 * from tools/app_icon.py.
 */
enum class Disguise(val label: String, val hold: String, val icon: Int, val background: Int, val foreground: Int) {
    Calculator("Calculator", "", R.mipmap.ic_disguise_calculator, R.drawable.ic_disguise_calculator_background, R.drawable.ic_disguise_calculator_foreground),
    Clock("Clock", "the time", R.mipmap.ic_disguise_clock, R.drawable.ic_disguise_clock_background, R.drawable.ic_disguise_clock_foreground),
    Compass("Compass", "the dial", R.mipmap.ic_disguise_compass, R.drawable.ic_disguise_compass_background, R.drawable.ic_disguise_compass_foreground),
    Notes("Notes", "the Notes title", R.mipmap.ic_disguise_notes, R.drawable.ic_disguise_notes_background, R.drawable.ic_disguise_notes_foreground),
    Files("Files", "Internal storage", R.mipmap.ic_disguise_files, R.drawable.ic_disguise_files_background, R.drawable.ic_disguise_files_foreground),
    Scanner("Scanner", "the scan button", R.mipmap.ic_disguise_scanner, R.drawable.ic_disguise_scanner_background, R.drawable.ic_disguise_scanner_foreground),
    Authenticator("Authenticator", "the code", R.mipmap.ic_disguise_authenticator, R.drawable.ic_disguise_authenticator_background, R.drawable.ic_disguise_authenticator_foreground),
    Workspace("Workspace", "the logo", R.mipmap.ic_disguise_workspace, R.drawable.ic_disguise_workspace_background, R.drawable.ic_disguise_workspace_foreground),
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

/** The disguise as it opens: the calculator or another app's first screen (DisguiseFronts.kt). Both lead to the PIN. */
@Composable
private fun DisguiseFront(disguise: Disguise) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val pinLength by produceState<Int?>(null) { value = withContext(Dispatchers.IO) { PrivacyLock.pinLength() } }
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
    if (asking) {
        // Never offers to reset the safe: from here, anyone holding the phone could
        PrivacySheet("Unlock", onDone = {
            asking = false
            DisguisePrefs.unlock()
        }, onDismiss = { asking = false }, resettable = false)
    }
}

/** Settings > Privacy: opens [DisguiseScreen]. */
@Composable
fun DisguiseRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { DisguisePrefs.load(context) }
    val chosen by DisguisePrefs.chosen.collectAsState()
    MenuRow(
        Sym.PhoneAndroid, "Disguise Album", onOpen, modifier,
        sub = chosen?.let { "Looks like ${it.label} on your home screen" } ?: "Look like a calculator or a work app on your home screen",
    )
}

/**
 * Settings > Privacy > Disguise Album: Album's own icon or a disguise, each shown as its icon and name. Turning one on
 * first says how to get back in, and both on and off ask for the privacy PIN (setting one first if there's none).
 */
@Composable
fun DisguiseScreen(onBack: () -> Unit) = SettingsPage("Disguise Album", onBack) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    remember { DisguisePrefs.load(context) }
    val chosen by DisguisePrefs.chosen.collectAsState()
    val setUp by PrivacyLock.setUp.collectAsState()
    val fingerprint = remember(setUp) { PrivacyLock.fingerprintReady(context) }
    var confirming by remember { mutableStateOf<Disguise?>(null) }
    var asking by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // A SectionHead's type and space above, on the page's own margin
    @Composable
    fun Head(text: String) = Text(text, Modifier.padding(top = Space.s4), style = type.titleMd, color = c.ink)

    fun pick(disguise: Disguise?) {
        when {
            disguise == chosen -> {}
            disguise == null -> asking = "Turn off disguise" to {
                DisguisePrefs.choose(null)
                PrivacyLock.notices.trySend(PrivacyLock.Notice("Album's own icon comes back when you leave Album"))
            }
            else -> confirming = disguise
        }
    }

    Text(
        "Album can look like another app on your home screen, so it isn't seen as a gallery. Its icon and name change when " +
            "you leave Album.",
        style = type.body, color = c.inkMuted,
    )
    if (!setUp) Text("Only your privacy PIN opens a disguised Album, so choosing a disguise sets one first.", style = type.body, color = c.inkMuted)
    (listOf<Disguise?>(null) + Disguise.entries).chunked(3).forEach { row ->
        ChoiceGroup(row.map { Choice(it, it?.label ?: "Album") { IconPreview(it) } }, chosen, ::pick)
    }

    chosen?.let { d ->
        Head("How to open Album")
        Text(howToOpen(d, fingerprint), style = type.body, color = c.ink)
    }
    Head("Good to know")
    listOf(
        DRAWER_NOTE,
        "Recent apps show the disguise's name, with no preview of Album.",
        "While disguised, Album isn't offered in Open with for other apps' photos, the Memories widget is removed, and " +
            "organising photos shows no notification.",
        "Android's own settings still call the app Album: Apps, notifications, storage and battery.",
    ).forEach { Text(it, style = type.body, color = c.inkMuted) }

    confirming?.let { d ->
        val widget = remember {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MemoryWidgetReceiver::class.java)).isNotEmpty()
        }
        ConfirmBar(
            title = "Disguise Album as ${d.label}?",
            body = (if (setUp) "" else "First, set a privacy PIN. ") + "When you leave Album, its icon and name change to ${d.label}. " +
                howToOpen(d, fingerprint) + (if (widget) " Your Memories widget is removed from the home screen." else "") +
                " " + DRAWER_NOTE,
            confirmLabel = if (setUp) "Disguise" else "Set a PIN",
            onConfirm = {
                confirming = null
                asking = "Disguise as ${d.label}" to {
                    DisguisePrefs.choose(d)
                    PrivacyLock.notices.trySend(PrivacyLock.Notice("Album looks like ${d.label} once you leave it"))
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

/** How to get past [d]'s front; the fingerprint only when the PIN pad offers it. */
private fun howToOpen(d: Disguise, fingerprint: Boolean) = when (d) {
    Disguise.Calculator -> "To open Album, type your privacy PIN, then =." + if (fingerprint) " Or hold = to use your fingerprint." else ""
    else -> "To open Album, hold ${d.hold} for a moment, then enter your privacy PIN" + if (fingerprint) " or use your fingerprint." else "."
}

/** A choice's preview: the icon as the home screen shows it, Album's own with the accent's sun. */
@Composable
private fun IconPreview(d: Disguise?) {
    Box(Modifier.fillMaxWidth().height(88.dp).background(AlbumTheme.colors.surface).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (d == null) LogoMark(size = 56.dp) else DisguiseIcon(d, 56.dp)
    }
}

/** [d]'s launcher icon at [size]. Launchers show the middle 72 of an icon's 108dp, so its layers are drawn 1.5 times as big. */
@Composable
internal fun DisguiseIcon(d: Disguise, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(painterResource(d.background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(d.foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}
