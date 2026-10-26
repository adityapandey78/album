package com.aditya.album

import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The privacy PIN's screens: the PIN pad, the flows that set, check, change and reset the PIN (in a sheet over an action,
// or as the locked state of a private page), the Settings rows, and the host that shows their results.

/** What a privacy flow is for: opening privacy (setting up a PIN first if there's none), or changing the PIN. */
enum class PrivacyPurpose { Unlock, Change }

private enum class Why { SetUp, Forgot, Change }

private sealed interface Step {
    /** The current PIN, or the fingerprint. */
    data object Enter : Step

    /** A new PIN: the first one, after Forgot PIN, or Change PIN. */
    data class Choose(val why: Why) : Step

    data class Repeat(val why: Why, val first: String) : Step

    /** Setting up: Android's prompt makes the safe key (or opens a v1.0 safe's) for [pin]. */
    data class Confirm(val pin: String) : Step

    /** Setting up found a v1.0 safe whose key Android erased: it has to be reset before [pin] can be saved. */
    data class Lost(val pin: String) : Step

    /** Forgot PIN, but the screen lock was off since the PIN was set, so nothing else can open the safe key. */
    data object CantRecover : Step
}

/** What setup says about the safe of a v1.0 phone whose key Android erased. */
private const val LOST_SETUP_NOTE =
    "Android erased Private safe's key when the screen lock was turned off, so what's inside can't be opened any more. " +
        "Reset the safe to set your privacy PIN."

/**
 * Asks for the privacy PIN in a sheet titled after the action ([title]: "Hide Asha"), offering the fingerprint, Forgot
 * PIN, and setting a PIN first if there's none. [onDone] runs with privacy unlocked.
 */
@Composable
fun PrivacySheet(title: String, onDone: () -> Unit, onDismiss: () -> Unit, purpose: PrivacyPurpose = PrivacyPurpose.Unlock) {
    Sheet(onDismiss) { PrivacyFlow(title, purpose, onDone, onCancel = onDismiss, page = false) }
}

/**
 * A private page while privacy is locked: [icon], [title] and [intro] above the PIN pad, with the top bar's Back. The
 * page shows its content once [Safe.key] is set.
 */
@Composable
fun PrivacyGate(title: String, icon: Sym, intro: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding()) { CollapsedTopBar(title, onBack = onBack) }
        PrivacyFlow(title, PrivacyPurpose.Unlock, onDone = {}, onCancel = null, page = true, icon = icon, intro = intro)
    }
}

/**
 * Call from a page that shows private things (Private safe, Hidden items, a hidden album or person): screenshots and the
 * recents preview are blocked while it's on screen, and privacy stays unlocked while it is. [lockOnLeave]: leaving the
 * page locks privacy at once (Private safe), rather than [PrivacyLock.WINDOW_MS] later.
 */
@Composable
fun PrivatePage(lockOnLeave: Boolean = false) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        PrivacyLock.hold(activity?.window)
        onDispose {
            PrivacyLock.release(activity?.window)
            // Turning the phone only rebuilds the screen, so the page stays open
            if (lockOnLeave && activity?.isChangingConfigurations != true) PrivacyLock.lock()
        }
    }
}

/** Runs private actions: see [run]. Get one with [rememberPrivacyAsk]. */
@Stable
class PrivacyAsk internal constructor() {
    internal var pending by mutableStateOf<Pair<String, () -> Unit>?>(null)

    /**
     * Runs [action] now while privacy is unlocked, else after the PIN or fingerprint in a sheet titled [title] ("Hide
     * Asha"), which sets up a PIN first if there's none.
     */
    fun run(title: String, action: () -> Unit) {
        if (PrivacyLock.isOpen) {
            PrivacyLock.touch()
            action()
        } else {
            pending = title to action
        }
    }
}

@Composable
fun rememberPrivacyAsk(): PrivacyAsk {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    val ask = remember { PrivacyAsk() }
    ask.pending?.let { (title, action) ->
        PrivacySheet(title, onDone = {
            ask.pending = null
            action()
        }, onDismiss = { ask.pending = null })
    }
    return ask
}

/**
 * Call once in the main screen: shows what hiding, unhiding and PIN changes did in [snackbar] (with Undo), and offers to
 * turn Forgot PIN back on when Android erased its key. Also starts the privacy lock, which locks whenever Album leaves the
 * screen.
 */
@Composable
fun PrivacyHost(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    remember { PrivacyLock.init(context) }
    LaunchedEffect(snackbar) {
        for (n in PrivacyLock.notices) {
            val undo = n.undo
            if (undo == null) {
                snackbar.showSnackbar(n.text)
            } else if (snackbar.showSnackbar(n.text, "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed && PrivacyLock.isOpen) {
                CrashLog.note("undo hide")
                undo() // only while privacy is still unlocked: Undo of a hide shows things again
            }
        }
    }
    if (PrivacyLock.rearmOffer.collectAsState().value) {
        ConfirmBar(
            title = "Turn Forgot PIN back on?",
            body = "Your screen lock was off for a while, which switched off Forgot PIN and fingerprint unlock. Confirm with " +
                "your fingerprint or screen lock to turn them back on.",
            confirmLabel = "Turn on",
            onConfirm = {
                PrivacyLock.rearmOffer.value = false
                PrivacyLock.rearm(context, CancellationSignal(), onDone = {}) { it?.let { m -> PrivacyLock.notices.trySend(PrivacyLock.Notice(m)) } }
            },
            onCancel = { PrivacyLock.rearmOffer.value = false },
            icon = Sym.Fingerprint,
            tone = ConfirmTone.Flash,
            cancelLabel = "Not now",
        )
    }
}

/**
 * Settings > Privacy: Private safe ([onOpenSafe], when given) and Hidden items ([onHiddenItems]), which Collections can
 * leave out, then setting or changing the privacy PIN and fingerprint unlock. [rowModifier] lines the rows up with the
 * page's others.
 */
@Composable
fun PrivacyRows(onHiddenItems: () -> Unit, rowModifier: Modifier = Modifier, onOpenSafe: (() -> Unit)? = null) {
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

    onOpenSafe?.let { MenuRow(Sym.Lock, "Private safe", it, rowModifier, sub = "Encrypted photos that open only with your PIN or fingerprint") }
    MenuRow(Sym.VisibilityOff, "Hidden items", onHiddenItems, rowModifier, sub = "People and albums that open only with your PIN or fingerprint")
    MenuRow(
        Sym.Shield, // the design's icon for privacy
        if (setUp) "Change privacy PIN" else "Set a privacy PIN",
        {
            CrashLog.note(if (setUp) "change pin" else "set pin")
            sheet = if (setUp) PrivacyPurpose.Change else PrivacyPurpose.Unlock
        },
        rowModifier,
        sub = if (setUp) "Asks for your current PIN or fingerprint first" else "Opens Private safe and Hidden items, apart from your screen lock",
    )
    if (setUp && hasFingerprint && !rearm) {
        SwitchRow(
            "Unlock with fingerprint", "Instead of typing your PIN. Forgot PIN uses your fingerprint either way.", fingerprint,
            PrivacyLock::setFingerprint, icon = Sym.Fingerprint,
        )
    }
    if (rearm) {
        MenuRow(
            Sym.Fingerprint, "Turn Forgot PIN back on",
            { ask.run("Turn Forgot PIN back on") { PrivacyLock.rearm(context, CancellationSignal(), onDone = {}) { it?.let { m -> PrivacyLock.notices.trySend(PrivacyLock.Notice(m)) } } } },
            rowModifier, sub = "Your screen lock was off for a while, which switched off Forgot PIN and fingerprint unlock",
        )
    }
    sheet?.let { purpose ->
        PrivacySheet(
            if (purpose == PrivacyPurpose.Change) "Change privacy PIN" else "Set a privacy PIN",
            onDone = { sheet = null }, onDismiss = { sheet = null }, purpose = purpose,
        )
    }
}

/**
 * The privacy PIN's steps. Unlock: the PIN (or fingerprint, offered at once), with Forgot PIN; with no PIN yet it sets
 * one: choose, repeat, then Android's prompt once, so the fingerprint or screen lock can reset it later. Change: the
 * current PIN, then a new one. [page] lays it out as a private page's locked state, else for a sheet with [onCancel].
 */
@Composable
private fun PrivacyFlow(
    title: String,
    purpose: PrivacyPurpose,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
    page: Boolean,
    icon: Sym = Sym.Lock,
    intro: String? = null,
) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(if (PrivacyLock.setUp.value) Step.Enter else Step.Choose(Why.SetUp)) }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableLongStateOf(0L) }
    var length by remember { mutableStateOf<Int?>(null) }
    var held by remember { mutableStateOf<ByteArray?>(null) } // the safe key, proven for Forgot PIN or Change PIN
    var signal by remember { mutableStateOf(CancellationSignal()) }
    var resetting by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val startLocks = remember { PrivacyLock.lockCount.value }
    val locks by PrivacyLock.lockCount.collectAsState()
    val wrapLost by Safe.wrapLost.collectAsState()
    // Asks Android once per step, not on every digit
    val canFingerprint = remember(step, wrapLost) { PrivacyLock.fingerprintReady(context) }

    LaunchedEffect(step) {
        if (step == Step.Enter) {
            length = withContext(Dispatchers.IO) { PrivacyLock.pinLength() }
            wait = withContext(Dispatchers.IO) { PrivacyLock.waitNow() }
        }
    }
    LaunchedEffect(Unit) { Safe.checkKey() } // so Forgot PIN and the fingerprint know whether their key is still there
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            delay(1_000)
            wait = PrivacyLock.waitNow()
        }
    }
    // Leaving Album locks privacy: a key proven for Forgot PIN or Change PIN goes too, and it starts again
    LaunchedEffect(locks) {
        if (locks != startLocks && held != null) {
            held?.fill(0)
            held = null
            typed = ""
            note = null
            step = Step.Enter
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            signal.cancel()
            held?.fill(0)
        }
    }

    fun reject(text: String?) {
        note = text
        haptics.reject()
        if (!still) scope.launch {
            for (x in listOf(-12f, 12f, -8f, 8f, 0f)) shake.animateTo(x, spring(stiffness = Spring.StiffnessHigh))
        }
    }

    fun newSignal() = CancellationSignal().also {
        signal.cancel()
        signal = it
    }

    fun finish(key: ByteArray) {
        PrivacyLock.open(key)
        onDone()
    }

    // The current PIN or the fingerprint was right
    fun proven(key: ByteArray) = when (purpose) {
        PrivacyPurpose.Unlock -> finish(key)
        PrivacyPurpose.Change -> {
            held = key
            step = Step.Choose(Why.Change)
        }
    }

    fun fingerprint() {
        busy = true
        note = null
        Safe.prompt(context, title, null, Safe.Proof.Fingerprint, newSignal(), onKey = {
            busy = false
            proven(it)
        }) {
            busy = false
            if (it == KEY_ERASED) reject("Your fingerprint can't open this any more. Enter your PIN.") else if (it != null) reject(it)
        }
    }

    fun forgot() {
        note = null
        when {
            !Safe.hasScreenLock(context) -> reject(NEEDS_SCREEN_LOCK)
            !PrivacyLock.canRecover(context) -> step = Step.CantRecover
            else -> {
                CrashLog.note("forgot pin")
                busy = true
                Safe.prompt(context, "Reset privacy PIN", "Then choose a new PIN", Safe.Proof.Recover, newSignal(), onKey = {
                    busy = false
                    held = it
                    step = Step.Choose(Why.Forgot)
                }) {
                    busy = false
                    if (it == KEY_ERASED) step = Step.CantRecover else if (it != null) reject(it)
                }
            }
        }
    }

    fun setUp(pin: String) {
        busy = true
        note = null
        PrivacyLock.setUp(context, pin, newSignal(), onKey = {
            busy = false
            finish(it)
        }) {
            busy = false
            if (it == KEY_ERASED) step = Step.Lost(pin) else note = it ?: "Album needs this once, so your PIN can be reset if you forget it."
        }
    }

    fun submit(pin: String) {
        if (step != Step.Enter) typed = "" // the current PIN's dots stay full while it's checked
        when (val s = step) {
            Step.Enter -> {
                busy = true
                scope.launch {
                    val r = PrivacyLock.check(pin)
                    busy = false
                    typed = ""
                    when (r) {
                        is PinCheck.Right -> {
                            PrivacyLock.afterPin(context)
                            proven(r.key)
                        }
                        is PinCheck.Wrong -> {
                            reject(wrongPinNote(r.strikes))
                            wait = waitAfter(r.strikes)
                        }
                        is PinCheck.Wait -> wait = r.ms
                        PinCheck.Broken -> reject("This PIN can't be checked on this phone any more. Use Forgot PIN.")
                    }
                }
            }
            is Step.Choose -> step = Step.Repeat(s.why, pin)
            is Step.Repeat -> when {
                pin != s.first -> {
                    step = Step.Choose(s.why)
                    reject("Those PINs didn't match. Try again.")
                }
                s.why == Why.SetUp -> step = Step.Confirm(pin)
                else -> {
                    val key = held ?: return
                    busy = true
                    scope.launch {
                        runCatching { PrivacyLock.savePin(key, pin) }
                            .onSuccess {
                                held = null
                                CrashLog.note(if (s.why == Why.Forgot) "pin reset" else "pin changed")
                                PrivacyLock.notices.trySend(PrivacyLock.Notice("Privacy PIN changed"))
                                finish(key)
                            }
                            .onFailure {
                                busy = false
                                reject("Couldn't save your PIN. Try again.")
                            }
                    }
                }
            }
            else -> {}
        }
    }

    // The fingerprint is offered as soon as the PIN is asked for; the pad stays for anyone who'd rather type
    LaunchedEffect(step) {
        when (val s = step) {
            Step.Enter -> if (canFingerprint && withContext(Dispatchers.IO) { PrivacyLock.waitNow() } == 0L) fingerprint()
            is Step.Confirm -> setUp(s.pin)
            else -> {}
        }
    }

    val max = when (val s = step) {
        Step.Enter -> length ?: PIN_LENGTHS.last
        is Step.Repeat -> s.first.length
        else -> PIN_LENGTHS.last
    }
    val (heading, body) = when (val s = step) {
        Step.Enter -> when {
            purpose == PrivacyPurpose.Change -> title to "Enter your current PIN"
            page -> title to intro
            canFingerprint -> title to "Use your fingerprint or enter your privacy PIN"
            else -> title to "Enter your privacy PIN"
        }
        is Step.Choose -> when (s.why) {
            Why.SetUp -> "Set a privacy PIN" to if (Safe.isSetUp) {
                "Private safe now opens with a PIN of its own, or your fingerprint. 4 to 8 digits."
            } else {
                "Album asks for it before opening Private safe or anything you hide. 4 to 8 digits."
            }
            else -> "Choose a new PIN" to "4 to 8 digits"
        }
        is Step.Repeat -> "Enter it again" to "The same PIN once more"
        is Step.Confirm -> "Confirm it's you" to "Use your fingerprint or screen lock once, so they can reset this PIN if you forget it."
        is Step.Lost -> "Private safe can't be opened" to LOST_SETUP_NOTE
        Step.CantRecover -> "Forgot PIN can't help here" to "Forgot PIN checks it's you with your screen lock, which was off for a " +
            "while after you set this PIN. If you remember the PIN, it still opens everything. Resetting erases what's in " +
            "Private safe and starts again with a new PIN. Hidden people and albums stay hidden."
    }
    val padShown = step == Step.Enter || step is Step.Choose || step is Step.Repeat
    val hint = when {
        wait > 0 -> "Too many tries. Try again in ${waitText(wait)}."
        note != null -> note
        page && step == Step.Enter -> "Enter your privacy PIN"
        else -> null
    }

    @Composable
    fun Header(modifier: Modifier) = Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterVertically),
    ) {
        // The badge only where there's room for it above the pad: a phone held sideways keeps the words
        if (page && LocalConfiguration.current.screenHeightDp >= 600) {
            Box(Modifier.padding(bottom = Space.s2).size(88.dp).background(c.flashSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (busy) Sym.Fingerprint else icon, null, size = 44.dp, tint = c.flashInk)
            }
        }
        Text(heading, style = AlbumTheme.type.titleLg, color = c.ink, textAlign = TextAlign.Center)
        body?.let { Text(it, style = AlbumTheme.type.body, color = c.inkMuted, textAlign = TextAlign.Center) }
    }

    @Composable
    fun ColumnScope.Controls() {
        if (padShown) {
            val fixed = step != Step.Enter || length != null
            PinDots(typed.length, if (step is Step.Choose) maxOf(PIN_LENGTHS.first, typed.length) else max, fixed, Modifier.graphicsLayer { translationX = shake.value * density })
        }
        // One line kept for it even when empty, so the pad doesn't jump when a message comes and goes
        Text(
            hint.orEmpty(),
            Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = AlbumTheme.type.caption,
            color = if (note != null || wait > 0) c.ink else c.inkMuted,
            textAlign = TextAlign.Center,
        )
        when (val s = step) {
            Step.Enter, is Step.Choose, is Step.Repeat -> {
                val enabled = !busy && wait == 0L
                PinPad(
                    onDigit = { d ->
                        if (enabled && typed.length < max) {
                            typed += d
                            note = null
                            // A known length is checked at its last digit; a new PIN goes on with Next, or at the most digits
                            if (typed.length == max) submit(typed)
                        }
                    },
                    erase = when {
                        typed.isNotEmpty() -> "Delete" to { typed = typed.dropLast(1) }
                        onCancel != null -> "Cancel" to onCancel
                        s is Step.Repeat -> "Back" to { step = Step.Choose(s.why) }
                        else -> null
                    },
                    fingerprint = if (s == Step.Enter && canFingerprint) ({ if (!busy) fingerprint() }) else null,
                    enabled = enabled,
                )
                Box(Modifier.fillMaxWidth().heightIn(min = Space.target + Space.s2), contentAlignment = Alignment.Center) {
                    when {
                        s == Step.Enter && purpose == PrivacyPurpose.Unlock -> AlbumButton("Forgot PIN?", ::forgot, variant = ButtonVariant.Quiet, enabled = !busy)
                        s is Step.Choose -> AlbumButton(
                            "Next", { submit(typed) }, Modifier.widthIn(min = 160.dp), ButtonVariant.Primary, enabled = typed.length >= PIN_LENGTHS.first && !busy,
                        )
                    }
                }
            }
            is Step.Confirm -> if (!busy) AlbumButton("Continue", { setUp(s.pin) }, variant = ButtonVariant.Primary, icon = Sym.Fingerprint)
            is Step.Lost, Step.CantRecover -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                val back = if (s is Step.Lost) onCancel else ({ step = Step.Enter })
                back?.let { AlbumButton(if (s is Step.Lost) "Cancel" else "Back", it, Modifier.weight(1f)) }
                AlbumButton(
                    if (s is Step.Lost) "Reset safe" else "Reset", { resetting = true }, Modifier.weight(1.5f), ButtonVariant.Danger, icon = Sym.DeleteForever,
                )
            }
        }
    }

    if (page) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Space.s4)) {
            Header(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.s4).padding(top = Space.s4))
            Column(Modifier.fillMaxWidth().padding(bottom = Space.s4), horizontalAlignment = Alignment.CenterHorizontally) { Controls() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(Modifier.fillMaxWidth().padding(bottom = Space.s4))
            Controls()
        }
    }

    if (resetting) {
        val count = Safe.items.collectAsState(null).value?.size
        ConfirmBar(
            title = "Reset Private safe?",
            body = (count?.takeIf { it > 0 }?.let { "Its ${itemCount(it)} can't be opened any more, by Album or anyone else. " } ?: "") +
                "Resetting erases ${if (count == 0) "the safe" else "them"} from this phone and starts again with a new PIN. Hidden people " +
                "and albums stay hidden. This cannot be undone.",
            confirmLabel = "Hold to reset",
            onConfirm = {
                resetting = false
                val pin = (step as? Step.Lost)?.pin
                busy = true
                scope.launch {
                    Safe.reset().join()
                    busy = false
                    step = if (pin != null) Step.Confirm(pin) else Step.Choose(Why.SetUp)
                }
            },
            onCancel = { resetting = false },
            icon = Sym.DeleteForever,
            hold = true,
        )
    }
}

/**
 * The PIN's dots: [count] filled of [slots]. [fixed]: the length is known, so the empty ones show too; while a new PIN is
 * being chosen, only the minimum shows empty. TalkBack hears how many are in, never which.
 */
@Composable
private fun PinDots(count: Int, slots: Int, fixed: Boolean, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    Row(
        modifier.heightIn(min = Space.s6).semantics { contentDescription = "$count of $slots digits" },
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until if (fixed) slots else maxOf(slots, count)) {
            val on = i < count
            val size by animateDpAsState(if (on) 14.dp else 12.dp, spring(dampingRatio = 0.5f), label = "dot")
            Box(Modifier.size(size).then(if (on) Modifier.background(c.ink, CircleShape) else Modifier.border(1.5.dp, c.lineStrong, CircleShape)))
        }
    }
}

/**
 * The digits 1 to 9 and 0 on surface-high pills, like the design's tonal Button but with the digit in title-lg. The
 * bottom row: [fingerprint] (when it's offered), 0, and [erase] (Delete, Cancel or Back; nothing when null).
 */
@Composable
private fun PinPad(onDigit: (Int) -> Unit, erase: Pair<String, () -> Unit>?, fingerprint: (() -> Unit)?, enabled: Boolean) {
    // Keys shrink on short screens (landscape, large fonts) so the pad still fits above the navigation bar
    val height = (LocalConfiguration.current.screenHeightDp * 0.075f).dp.coerceIn(44.dp, 64.dp)
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                row.forEach { d -> PinKey("$d", Modifier.weight(1f).height(height), enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(height), contentAlignment = Alignment.Center) {
                fingerprint?.let { AlbumIconButton(Sym.Fingerprint, "Use fingerprint", it, tint = AlbumTheme.colors.flashInk) }
            }
            PinKey("0", Modifier.weight(1f).height(height), enabled) { onDigit(0) }
            Box(Modifier.weight(1f).height(height), contentAlignment = Alignment.Center) {
                erase?.let { (label, action) ->
                    Text(
                        label,
                        Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = action).padding(horizontal = Space.s4, vertical = Space.s3),
                        style = AlbumTheme.type.label,
                        color = AlbumTheme.colors.ink,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One key: presses like the design's Button (scales to 0.96, squares toward radius-md). TalkBack reads the digit. */
@Composable
private fun PinKey(digit: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed && !still) Space.radiusMd else 100.dp, tween(160), label = "corner")
    val scale by animateFloatAsState(if (pressed && !still) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(c.surfaceHigh, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = digit }
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        contentAlignment = Alignment.Center,
    ) { Text(digit, Modifier.clearAndSetSemantics {}, style = AlbumTheme.type.titleLg, color = c.ink) }
}
