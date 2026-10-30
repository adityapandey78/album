package com.aditya.album

import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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

    /** Forgot PIN: what happens, before Android's prompt checks it's you. */
    data object Forgot : Step

    /** A new PIN: the first one, after Forgot PIN, or Change PIN. */
    data class Choose(val why: Why) : Step

    data class Repeat(val why: Why, val first: String) : Step

    /** Setting up: Android's prompt makes the safe key (or opens a v1.0 safe's) for [pin]. */
    data class Confirm(val pin: String) : Step

    /** Setting up, the PIN saved: whether the pad offers the fingerprint too. Only on a phone with one. */
    data object Offer : Step

    /** Setting up found a v1.0 safe whose key Android erased: it has to be reset before [pin] can be saved. */
    data class Lost(val pin: String) : Step

    /** Forgot PIN, but the screen lock was off since the PIN was set, so nothing else can open the safe key. */
    data object CantRecover : Step
}

/** What setup says about the safe of a v1.0 phone whose key Android erased. */
private const val LOST_SETUP_NOTE =
    "Android erased Private safe's key when the screen lock was turned off, so what's inside can't be opened any more. " +
        "Reset the safe to set your privacy PIN."

/** What choosing a PIN says, the first time and after Change PIN or Forgot PIN. */
private const val CHOOSE_NOTE = "4 to 8 digits. It opens the Private safe and Hidden items."

/** The design's shake curve, on each swing of a wrong PIN's dots. */
private val ShakeEase = CubicBezierEasing(0.36f, 0.07f, 0.19f, 0.97f)

/**
 * Asks for the privacy PIN in a sheet for an action ([title]: "Hide Asha", said as "Needed to hide Asha."), offering the
 * fingerprint, Forgot PIN, and setting a PIN first if there's none. [onDone] runs with privacy unlocked. [resettable] false: Forgot PIN never
 * offers to reset the safe, for a sheet anyone could open (a disguise's way in), where a reset would be a way past it.
 * [onKey]: the safe key goes there instead and privacy stays locked (ID cards' way in on a phone without a screen lock).
 */
@Composable
fun PrivacySheet(
    title: String,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    purpose: PrivacyPurpose = PrivacyPurpose.Unlock,
    resettable: Boolean = true,
    onKey: ((ByteArray) -> Unit)? = null,
) {
    Sheet(onDismiss) { PrivacyFlow(title, purpose, onDone, onCancel = onDismiss, page = false, resettable = resettable, onKey = onKey) }
}

/**
 * A private page while privacy is locked: "Enter your PIN" with [icon] and [intro] ("The Private safe is locked.") above
 * the PIN pad, under the top bar's [title] and Back. The page shows its content once [Safe.key] is set. [onKey] as in
 * [PrivacySheet].
 */
@Composable
fun PrivacyGate(title: String, icon: Sym, intro: String, onBack: () -> Unit, onKey: ((ByteArray) -> Unit)? = null) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding()) { CollapsedTopBar(title, onBack = onBack) }
        // ID cards' gate ([onKey]) never offers to reset Private safe
        PrivacyFlow(title, PrivacyPurpose.Unlock, onDone = {}, onCancel = null, page = true, icon = icon, intro = intro, resettable = onKey == null, onKey = onKey)
    }
}

/**
 * A disguise's way in: the PIN pad full screen over its front, on Album's own canvas and font, with no top bar or
 * subtitle; Back closes it. Never offers to reset the safe: from a front, anyone holding the phone could.
 */
@Composable
fun FrontPin(onDone: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    // The design's 40dp under the status bar: 28 here, and the pad's own 12 above its badge
    Box(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).pointerInput(Unit) {}.statusBarsPadding().padding(top = 28.dp)) {
        PrivacyFlow("Unlock", PrivacyPurpose.Unlock, onDone, onCancel, page = true, resettable = false)
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
            body = "Your screen lock was off for a while, so Forgot PIN and fingerprint unlock switched off. Confirm with your " +
                "fingerprint or screen lock.",
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
 * The privacy PIN's steps, laid out as the design's PIN screen: a badge, a title and a line on what it's for, the dots, a
 * line for errors, the round keys, then the step's button (Unlock, Next or Set PIN) and Forgot PIN. Unlock: the PIN, or
 * the fingerprint key; with no PIN yet it sets one: choose, repeat, then Android's prompt once, so the fingerprint or
 * screen lock can reset it later, then the offer to unlock with the fingerprint too. Change: the current PIN, then a new
 * one. [page] lays it out as a private page's locked state (72dp keys), else for a sheet (64dp keys) with [onCancel].
 * [onKey]: where the safe key goes instead of privacy.
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
    resettable: Boolean = true,
    onKey: ((ByteArray) -> Unit)? = null,
) {
    val context = LocalContext.current
    val still = AlbumTheme.spec.still
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(if (PrivacyLock.setUp.value) Step.Enter else Step.Choose(Why.SetUp)) }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) } // Android's fingerprint prompt is up
    var wait by remember { mutableLongStateOf(0L) }
    var held by remember { mutableStateOf<ByteArray?>(null) } // the safe key, proven for Forgot PIN or Change PIN, or just set up
    var signal by remember { mutableStateOf(CancellationSignal()) }
    var resetting by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val startLocks = remember { PrivacyLock.lockCount.value }
    val locks by PrivacyLock.lockCount.collectAsState()
    val wrapLost by Safe.wrapLost.collectAsState()
    // Asks Android once per step, not on every digit
    val canFingerprint = remember(step, wrapLost) { PrivacyLock.fingerprintReady(context) }

    LaunchedEffect(step) {
        if (step == Step.Enter) wait = withContext(Dispatchers.IO) { PrivacyLock.waitNow() }
    }
    LaunchedEffect(Unit) { Safe.checkKey() } // so Forgot PIN and the fingerprint know whether their key is still there
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            delay(1_000)
            wait = PrivacyLock.waitNow()
        }
    }
    // Leaving Album locks privacy: a key proven for Forgot PIN or Change PIN (or a new PIN's, at the offer) goes too, and it
    // starts again
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
        // The design's shake: 8dp each way over 360 ms, each swing on its curve
        if (!still) scope.launch {
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                0f at 0 using ShakeEase
                -8f at 72 using ShakeEase
                8f at 144 using ShakeEase
                -8f at 216 using ShakeEase
                8f at 288 using ShakeEase
            })
        }
    }

    fun newSignal() = CancellationSignal().also {
        signal.cancel()
        signal = it
    }

    fun finish(key: ByteArray) {
        if (onKey != null) return onKey(key) // ID cards: the safe key opens their copy, and privacy stays locked
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
        scanning = true
        note = null
        Safe.prompt(context, title, null, Safe.Proof.Fingerprint, newSignal(), onKey = {
            busy = false
            scanning = false
            proven(it)
        }) {
            busy = false
            scanning = false
            if (it == KEY_ERASED) reject("Your fingerprint can't open this any more. Enter your PIN.") else if (it != null) reject(it)
        }
    }

    // Forgot PIN? checks it can work, then says what happens before Android's prompt
    fun forgot() {
        note = null
        when {
            !Safe.hasScreenLock(context) -> reject(NEEDS_SCREEN_LOCK)
            !PrivacyLock.canRecover(context) -> step = Step.CantRecover
            else -> {
                typed = ""
                step = Step.Forgot
            }
        }
    }

    fun recover() {
        CrashLog.note("forgot pin")
        busy = true
        note = null
        scanning = Safe.hasFingerprint(context) // Recover takes the fingerprint when there is one, else the screen lock
        Safe.prompt(context, "Reset privacy PIN", "Then choose a new PIN", Safe.Proof.Recover, newSignal(), onKey = {
            busy = false
            scanning = false
            held = it
            step = Step.Choose(Why.Forgot)
        }) {
            busy = false
            scanning = false
            if (it == KEY_ERASED) step = Step.CantRecover else if (it != null) reject(it)
        }
    }

    // A new PIN is saved: says so, then opens. [fingerprint]: the offer's answer, null when it wasn't offered.
    fun pinSet(key: ByteArray, fingerprint: Boolean?) {
        fingerprint?.let(PrivacyLock::setFingerprint)
        PrivacyLock.notices.trySend(PrivacyLock.Notice(if (fingerprint == true) "PIN and fingerprint set" else "PIN set"))
        finish(key)
    }

    fun setUp(pin: String) {
        busy = true
        note = null
        PrivacyLock.setUp(context, pin, newSignal(), onKey = {
            busy = false
            if (Safe.hasFingerprint(context)) {
                held = it
                step = Step.Offer
            } else {
                pinSet(it, null)
            }
        }) {
            busy = false
            if (it == KEY_ERASED) step = Step.Lost(pin) else note = it ?: "Album needs this once, so your PIN can be reset if you forget it."
        }
    }

    // The offer's answer: the key goes on to privacy, so this flow lets go of it first
    fun offered(fingerprint: Boolean) {
        val key = held ?: return
        held = null
        pinSet(key, fingerprint)
    }

    fun submit(pin: String) {
        note = null
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
                            if (onKey == null) PrivacyLock.afterPin(context) // its offer needs privacy open, which ID cards leave locked
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
                    reject("The PINs didn't match. Choose a PIN again.")
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
                                PrivacyLock.notices.trySend(PrivacyLock.Notice("PIN changed"))
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

    // Setup's prompt follows the tap on Set PIN; the fingerprint for unlocking waits for a tap on its key
    LaunchedEffect(step) {
        (step as? Step.Confirm)?.let { setUp(it.pin) }
    }

    val (heading, body) = if (scanning) {
        "Touch the fingerprint sensor" to "Your phone checks it. Album never sees or stores it."
    } else when (val s = step) {
        Step.Enter -> when {
            purpose == PrivacyPurpose.Change -> "Enter your current PIN" to null
            page -> "Enter your PIN" to intro
            else -> "Enter your PIN" to "Needed to ${title.replaceFirstChar { it.lowercase() }}."
        }
        Step.Forgot -> "Forgot your PIN?" to "Confirm it's you with your phone's screen lock or fingerprint, then choose a new PIN. " +
            "Nothing in the safe is lost."
        is Step.Choose -> when {
            s.why != Why.SetUp -> "Choose a new PIN" to CHOOSE_NOTE
            // A v1.0 safe opened with the screen lock alone until now
            Safe.isSetUp -> "Choose a PIN" to "Private safe now opens with a PIN of its own, or your fingerprint. 4 to 8 digits."
            else -> "Choose a PIN" to CHOOSE_NOTE
        }
        is Step.Repeat -> "Enter it again" to "Type the same PIN to confirm it."
        is Step.Confirm -> "Confirm it's you" to "Use your fingerprint or screen lock once, so they can reset this PIN if you forget it."
        Step.Offer -> "Unlock with fingerprint too?" to "Use your phone's fingerprint instead of typing the PIN. You can change this in Settings."
        is Step.Lost -> "Private safe can't be opened" to LOST_SETUP_NOTE
        Step.CantRecover -> "Forgot PIN can't help here" to "Forgot PIN checks it's you with your screen lock, which was off for a " +
            "while after you set this PIN. If you remember the PIN, it still opens everything." + if (resettable) {
                " Resetting erases what's in Private safe and starts again with a new PIN. Hidden people and albums stay hidden."
            } else ""
    }
    val badge = when {
        scanning || step is Step.Confirm || step == Step.Offer -> Sym.Fingerprint
        step == Step.Enter -> icon
        step == Step.Forgot -> Sym.LockReset
        step is Step.Lost -> Sym.Error
        else -> Sym.Lock
    }
    val hint = if (wait > 0) "Too many tries. Try again in ${waitText(wait)}" else note

    PinColumn(page) {
        PinHead(badge, heading, body, scanning)
        // While Android's fingerprint prompt is up, the badge and its two lines are all there is
        if (scanning) return@PinColumn
        val s = step
        if (s == Step.Enter || s is Step.Choose || s is Step.Repeat) {
            PinDots(typed.length, wrong = note != null, Modifier.padding(top = Space.s2).graphicsLayer { translationX = shake.value * density })
            // One line kept for it even when empty, so the pad doesn't jump when a message comes and goes
            Hint(hint.orEmpty())
            PinPad(
                if (page) 72.dp else 64.dp,
                enabled = wait == 0L,
                onDigit = { d -> if (!busy && wait == 0L && typed.length < PIN_LENGTHS.last) typed += d },
                erase = when {
                    typed.isNotEmpty() -> "Delete last digit" to { typed = typed.dropLast(1) }
                    s is Step.Repeat -> "Back" to { step = Step.Choose(s.why) }
                    else -> "Delete last digit" to {}
                },
                fingerprint = if (s == Step.Enter && canFingerprint) ({ if (!busy) fingerprint() }) else null,
            )
            // The step's button, as the design draws it: full colour even before 4 digits, when it does nothing
            val ready = typed.length >= PIN_LENGTHS.first && wait == 0L
            val label = when {
                s is Step.Choose -> "Next"
                s is Step.Repeat -> "Set PIN"
                purpose == PrivacyPurpose.Change -> "Next"
                else -> "Unlock"
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumButton(
                    label, { if (ready && !busy) submit(typed) },
                    Modifier.fillMaxWidth().semantics { if (!ready) disabled() }, ButtonVariant.Primary,
                )
                // The design's link 4dp under the button's gap: its text sits where a 20dp link's would
                if (s == Step.Enter) AlbumButton("Forgot PIN?", { if (!busy) forgot() }, Modifier.padding(top = 2.dp), ButtonVariant.Quiet)
            }
            return@PinColumn
        }
        // A step without a pad says something only when there's something to say
        hint?.let { Hint(it) }
        when (s) {
            Step.Forgot -> PinActions {
                AlbumButton("Back", {
                    note = null
                    step = Step.Enter
                }, Modifier.fillMaxWidth(), enabled = !busy)
                AlbumButton("Verify it's you", ::recover, Modifier.fillMaxWidth(), ButtonVariant.Primary, icon = Sym.Fingerprint, enabled = !busy)
            }
            is Step.Confirm -> if (!busy) PinActions {
                AlbumButton("Continue", { setUp(s.pin) }, Modifier.fillMaxWidth(), ButtonVariant.Primary, icon = Sym.Fingerprint)
            }
            Step.Offer -> PinActions {
                AlbumButton("Not now", { offered(false) }, Modifier.fillMaxWidth())
                AlbumButton("Use fingerprint", { offered(true) }, Modifier.fillMaxWidth(), ButtonVariant.Primary, icon = Sym.Fingerprint)
            }
            is Step.Lost, Step.CantRecover -> PinActions {
                val back = if (s is Step.Lost) onCancel else ({ step = Step.Enter })
                back?.let { AlbumButton(if (s is Step.Lost) "Cancel" else "Back", it, Modifier.fillMaxWidth()) }
                if (resettable) {
                    AlbumButton(
                        if (s is Step.Lost) "Reset safe" else "Reset", { resetting = true }, Modifier.fillMaxWidth(), ButtonVariant.Danger,
                        icon = Sym.DeleteForever,
                    )
                }
            }
        }
    }

    if (resetting) {
        val count = Safe.items.collectAsState(null).value?.size
        ConfirmBar(
            title = "Reset Private safe?",
            body = (count?.takeIf { it > 0 }?.let { "Its ${itemCount(it)} ${if (it == 1) "is" else "are"} erased from this phone and can't be opened again. " } ?: "") +
                "You then choose a new PIN. Hidden people and albums stay hidden.",
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
            icon = Sym.LockReset,
            hold = true,
        )
    }
}

/**
 * The PIN screen's column, at most 340dp wide and centred, with 12dp between its parts. [page]: under a top bar, 12dp
 * down and 24dp in, else in a sheet, 8dp down. Scrolls when the phone is held sideways or the font is large, rather than
 * shrinking the keys.
 */
@Composable
private fun PinColumn(page: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        (if (page) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 340.dp).fillMaxWidth()
                .then(
                    if (page) Modifier.navigationBarsPadding().padding(start = Space.s6, end = Space.s6, top = Space.s3, bottom = Space.s6)
                    else Modifier.padding(top = Space.s2),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s3),
            content = content,
        )
    }
}

/** The PIN screen's head: [badge] (pulsing while [scanning]), [title] in title-lg and [body] in muted body text. */
@Composable
private fun PinHead(badge: Sym, title: String, body: String?, scanning: Boolean = false) {
    val c = AlbumTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        PinBadge(badge, scanning)
        Text(title, style = AlbumTheme.type.titleLg, color = c.ink, textAlign = TextAlign.Center)
        body?.let { Text(it, style = AlbumTheme.type.body, color = c.inkMuted, textAlign = TextAlign.Center) }
    }
}

/**
 * A locked page's state without a pad (a safe or ID cards that can't open, ID cards' screen lock), under its top bar
 * ([title] and Back) and laid out as the PIN screen's steps: [badge], [heading] and [body], then [note] in danger when
 * there is one, and [actions], full width and stacked.
 */
@Composable
internal fun LockedState(title: String, onBack: () -> Unit, badge: Sym, heading: String, body: String, note: String? = null, actions: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding()) { CollapsedTopBar(title, onBack = onBack) }
        PinColumn(page = true) {
            PinHead(badge, heading, body)
            note?.let { Hint(it) }
            PinActions(actions)
        }
    }
}

/** A step's buttons when it has no pad (Forgot PIN, setup's prompt and offer, a reset): full width, stacked, the main one last. */
@Composable
private fun PinActions(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(top = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2), content = content)

/** The PIN screen's message line: a wrong PIN or a wait, in danger, read out by TalkBack as it changes. */
@Composable
private fun Hint(text: String) = Text(
    text,
    Modifier.fillMaxWidth().heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Polite },
    style = AlbumTheme.type.caption,
    color = AlbumTheme.colors.danger,
    textAlign = TextAlign.Center,
)

/** The PIN screen's badge: [sym] on a flash-soft disc. It pulses while Android's fingerprint prompt is up ([scanning]). */
@Composable
private fun PinBadge(sym: Sym, scanning: Boolean) {
    val c = AlbumTheme.colors
    val pulse = if (scanning && !AlbumTheme.spec.still) {
        rememberInfiniteTransition(label = "scan")
            .animateFloat(1f, 1.08f, infiniteRepeatable(tween(350, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Box(
        Modifier.size(56.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(c.flashSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(sym, null, tint = c.flashInk) }
}

/**
 * The PIN's dots: [count] filled, never fewer than 4 shown, so they grow with the PIN and never give its length away;
 * ringed in danger after a wrong PIN ([wrong]). TalkBack hears how many are in, never which.
 */
@Composable
private fun PinDots(count: Int, wrong: Boolean, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    Row(
        modifier.height(20.dp).semantics { contentDescription = "${counted(count, "digit", "digits")} entered" },
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until maxOf(PIN_LENGTHS.first, count)) {
            val on = i < count
            val scale by animateFloatAsState(
                if (on) 1.15f else 1f, if (still) snap() else tween(160, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.5f)), label = "dot",
            )
            val fill by animateColorAsState(if (on) c.ink else c.ink.copy(alpha = 0f), if (still) snap() else tween(120), label = "dotFill")
            val ring by animateColorAsState(if (wrong) c.danger else if (on) c.ink else c.lineStrong, if (still) snap() else tween(120), label = "dotRing")
            Box(Modifier.size(12.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(fill, CircleShape).border(2.dp, ring, CircleShape))
        }
    }
}

/**
 * The design's PIN pad: round keys [key] across, 1 to 9 with their phone letters, then [fingerprint] (when it's offered),
 * 0 and [erase] (its label for TalkBack, and what it does: delete a digit, or go back from Enter it again). The digits
 * and the fingerprint fade and stop while not [enabled] (a wait after wrong PINs); erase always works.
 */
@Composable
private fun PinPad(key: Dp, enabled: Boolean, onDigit: (Int) -> Unit, erase: Pair<String, () -> Unit>, fingerprint: (() -> Unit)?) {
    val c = AlbumTheme.colors
    val letters = AlbumTheme.type.caption.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.08.em, fontWeight = FontWeight.Normal)

    @Composable
    fun Digit(d: Int, sub: String) = PinKey(Modifier.size(key), "$d", enabled, onClick = { onDigit(d) }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$d", style = AlbumTheme.type.titleLg, color = c.ink)
            if (sub.isNotEmpty()) Text(sub, style = letters, color = c.inkMuted)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        listOf(listOf("", "ABC", "DEF"), listOf("GHI", "JKL", "MNO"), listOf("PQRS", "TUV", "WXYZ")).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) { row.forEachIndexed { i, sub -> Digit(r * 3 + i + 1, sub) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
            if (fingerprint != null) {
                PinKey(Modifier.size(key), "Use fingerprint", enabled, fill = Color.Transparent, onClick = fingerprint) { Icon(Sym.Fingerprint, null, tint = c.ink) }
            } else {
                Spacer(Modifier.size(key))
            }
            Digit(0, "")
            PinKey(Modifier.size(key), erase.first, fill = Color.Transparent, onClick = erase.second) { Icon(Sym.Backspace, null, tint = c.ink) }
        }
    }
}

/**
 * A round key: it presses down to [pressScale] and darkens to [pressed] over [pressMs], as the design's PIN keys do
 * (0.92 over 120 ms); disabled ones fade. TalkBack reads [description]. The calculator disguise (Calculator.kt) uses it
 * too, with its own colours, press and a hold on "=".
 */
@Composable
internal fun PinKey(
    modifier: Modifier,
    description: String,
    enabled: Boolean = true,
    fill: Color = AlbumTheme.colors.surfaceHigh,
    pressed: Color = AlbumTheme.colors.line,
    pressScale: Float = 0.92f,
    pressMs: Int = 120,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val still = AlbumTheme.spec.still
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down && !still) pressScale else 1f, tween(pressMs), label = "press")
    val ground by animateColorAsState(if (down) pressed else fill, tween(pressMs), label = "fill")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(ground)
            .combinedClickable(source, indication = null, enabled = enabled, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.clearAndSetSemantics {}) { content() } }
}

/** Material's backspace outline (a tag pointing left, with an x), drawn in the calculator's own stroke. */
@Composable
internal fun BackspaceIcon(tint: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(Space.icon)) {
    val u = size.width / 24f
    val tag = Path().apply {
        moveTo(8f * u, 5f * u)
        lineTo(21f * u, 5f * u)
        lineTo(21f * u, 19f * u)
        lineTo(8f * u, 19f * u)
        lineTo(2.5f * u, 12f * u)
        close()
    }
    drawPath(tag, tint, style = Stroke(2f * u, join = StrokeJoin.Round, pathEffect = PathEffect.cornerPathEffect(2f * u)))
    drawLine(tint, Offset(11.5f * u, 9.5f * u), Offset(16.5f * u, 14.5f * u), 2f * u, StrokeCap.Round)
    drawLine(tint, Offset(16.5f * u, 9.5f * u), Offset(11.5f * u, 14.5f * u), 2f * u, StrokeCap.Round)
}
