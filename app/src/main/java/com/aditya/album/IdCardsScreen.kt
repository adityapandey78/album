package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

/** What the open page says while the privacy PIN keeps no copy of ID cards' key. */
private const val NO_COPY_NOTE =
    "Only your screen lock opens these. If it's ever turned off, Android erases their key and they can't be opened. A privacy PIN keeps a second way in."

/** Moving in, on a phone with nothing to guard ID cards with. */
private const val NO_LOCK_MOVE = "ID cards open with your screen lock. Set one in Android settings first."

private const val PIN_NOTE = "This phone has no screen lock, so ID cards open with your privacy PIN."
private const val REARM_NOTE = "Your screen lock was off for a while, which erased its key. Enter your privacy PIN, then your screen lock, to turn it back on."
private const val LOST_NOTE = "Android erased their key when the screen lock was turned off, and no privacy PIN kept a copy."

/** Android's screen lock settings, where a phone without one gets one. */
private fun screenLockSettings() = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)

/** Moves photos and videos into ID cards: see [rememberMoveToIdCards]. */
@Stable
class MoveToIdCards internal constructor(private val start: (List<Media>, Boolean) -> Unit) {
    /** [ask]: confirm first with the photos (For you's suggestion, where a false positive mustn't leave on one tap). */
    operator fun invoke(items: List<Media>, ask: Boolean = false) = start(items, ask)
}

/**
 * Returns the [MoveToIdCards] of this screen. The move is asked for once: Android's prompt titled with it (the screen lock
 * or fingerprint, which makes ID cards' key the first time), or the privacy PIN in a sheet on a phone without a screen
 * lock. ID cards stay locked: the key goes only to the move, which wipes it. The results, Android's delete pop-up and the
 * progress card are shown by [rememberMoveToSafe], which every screen using this has too. [onStart] runs once it begins.
 */
@Composable
fun rememberMoveToIdCards(snackbar: SnackbarHostState, onStart: () -> Unit = {}): MoveToIdCards {
    val context = LocalContext.current
    remember { IdCards.init(context) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val started by rememberUpdatedState(onStart)
    var confirming by remember { mutableStateOf<List<Media>?>(null) }
    var pinFor by remember { mutableStateOf<Pair<List<Media>, IdGate>?>(null) }

    fun title(items: List<Media>) = "Move ${itemCount(items.size)} to ID cards"

    fun move(items: List<Media>, key: ByteArray) {
        CrashLog.note("id cards move in ${items.size}")
        started()
        Safe.moveIn(context.applicationContext, items, key, SafeItem.ID_CARDS) // the job wipes the key when done
    }

    fun fail(message: String?) {
        if (message == null) return // cancelled
        haptics.reject()
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun unlock(items: List<Media>) = scope.launch {
        when (val gate = IdCards.gate(context)) {
            IdGate.ScreenLock -> IdCards.prompt(context, title(items), "They leave the Library and search", CancellationSignal(), onKey = { move(items, it) }, onFail = ::fail)
            IdGate.Pin, IdGate.PinThenScreenLock -> pinFor = items to gate
            IdGate.NoLock -> {
                haptics.reject()
                if (snackbar.showSnackbar(NO_LOCK_MOVE, "Settings") == SnackbarResult.ActionPerformed) context.startSafely(screenLockSettings())
            }
            IdGate.Lost -> fail("Android erased ID cards' key, so nothing can go in. Open ID cards to reset them.")
        }
    }

    confirming?.let { items ->
        ConfirmBar(
            title = "Move ${countOf(items.size, "photo")} to ID cards?",
            body = idMoveNote(remember { Safe.hasScreenLock(context) }),
            confirmLabel = "Move to ID cards",
            onConfirm = {
                confirming = null
                unlock(items)
            },
            onCancel = { confirming = null },
            icon = Sym.IdCard,
            tone = ConfirmTone.Flash,
            thumbs = items.take(3),
        )
    }
    pinFor?.let { (items, gate) ->
        PrivacySheet(title(items), onDone = {}, onDismiss = { pinFor = null }, resettable = false, onKey = { safeKey ->
            pinFor = null
            IdCards.unlockWithPin(context, safeKey, gate == IdGate.PinThenScreenLock, title(items), CancellationSignal(), { move(items, it) }, ::fail)
        })
    }
    return remember {
        MoveToIdCards { items, ask ->
            when {
                !context.canSend(items.size, MAX_REQUEST, "move") -> {}
                // Without it Android hands over photos with their GPS blanked, and deleting the original would lose the place
                context.checkSelfPermission(ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                    haptics.reject()
                    scope.launch { snackbar.showSnackbar(SAFE_NEEDS_LOCATION) }
                }
                ask -> confirming = items
                else -> unlock(items) // Android's prompt, named after the move, is the one deliberate step
            }
        }
    }
}

/**
 * ID cards, a pushed page: ID photos kept like Private safe's, behind the phone's screen lock instead of the privacy PIN.
 * Locked: the way in this phone has ([IdGate]). Open: the grid, long-press to select, then Share, Move out or Delete
 * forever; tap to view. Screenshots and the recents preview are blocked while it's on screen, and it locks when you leave
 * the page and whenever Album leaves the screen. Opening it never opens Private safe or Hidden items, nor they it.
 */
@Composable
fun IdCardsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    remember { IdCards.init(context) }
    val key by IdCards.key.collectAsState()
    var prompted by rememberSaveable { mutableStateOf(false) } // Android's prompt comes up by itself once per visit
    PrivatePage()
    DisposableEffect(activity) {
        onDispose { if (activity?.isChangingConfigurations != true) IdCards.lock() } // turning the phone keeps it open
    }
    Box(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).pointerInput(Unit) {}) { // catches taps meant for the screen underneath
        when (val k = key) {
            null -> LockedIdCards(onBack, autoPrompt = !prompted, onPrompted = { prompted = true })
            else -> OpenIdCards(k, onBack)
        }
    }
    Safe.progress.collectAsState().value?.takeIf { it.inSafe }?.let { SafeProgress(it) }
}

/**
 * ID cards while locked, by [IdGate], laid out as the PIN screen: the unlock button for Android's prompt (shown by itself
 * when [autoPrompt], since the screen lock is their only way in), the privacy PIN pad on a phone without a screen lock,
 * the way to Android's settings with neither, or Reset when nothing can open them any more. Asked again whenever Album
 * comes back, say from Android's settings.
 */
@Composable
private fun LockedIdCards(onBack: () -> Unit, autoPrompt: Boolean, onPrompted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var gate by remember { mutableStateOf<IdGate?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf(CancellationSignal()) }
    val snackbar = remember { SnackbarHostState() }
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { gate = IdCards.gate(context) }
        onPauseOrDispose { job.cancel() }
    }
    DisposableEffect(Unit) { onDispose { signal.cancel() } }

    fun newSignal() = CancellationSignal().also {
        signal.cancel()
        signal = it
    }

    fun fail(message: String?) {
        busy = false
        if (message == null) return // cancelled
        haptics.reject()
        if (message == KEY_ERASED) scope.launch { gate = IdCards.gate(context) } // now the PIN's way, or Reset
        else if (gate == IdGate.ScreenLock) note = message
        else scope.launch { snackbar.showSnackbar(message) }
    }

    fun unlock() {
        busy = true
        note = null
        IdCards.prompt(context, "Open ID cards", null, newSignal(), onKey = {
            busy = false
            IdCards.open(it)
        }, onFail = ::fail)
    }

    LaunchedEffect(gate) {
        if (gate == IdGate.ScreenLock && autoPrompt) {
            onPrompted()
            unlock()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val g = gate) {
            null -> {} // a moment, while the key files are read
            IdGate.Pin, IdGate.PinThenScreenLock -> PrivacyGate("ID cards", Sym.IdCard, if (g == IdGate.Pin) PIN_NOTE else REARM_NOTE, onBack) { safeKey ->
                IdCards.unlockWithPin(context, safeKey, g == IdGate.PinThenScreenLock, "Open ID cards", newSignal(), IdCards::open, ::fail)
            }
            IdGate.ScreenLock -> LockedState("ID cards", onBack, Sym.IdCard, "ID cards are locked", "They open with your screen lock or fingerprint.", note) {
                AlbumButton("Unlock with screen lock", ::unlock, Modifier.fillMaxWidth(), ButtonVariant.Primary, icon = Sym.Fingerprint, enabled = !busy)
            }
            IdGate.NoLock -> LockedState("ID cards", onBack, Sym.IdCard, "Set a screen lock first", "ID cards open with your phone's screen lock or fingerprint, and this phone has no screen lock.", note) {
                AlbumButton("Open screen lock settings", { context.startSafely(screenLockSettings()) }, Modifier.fillMaxWidth(), ButtonVariant.Primary, icon = Sym.Settings)
            }
            IdGate.Lost -> LockedState("ID cards", onBack, Sym.Error, "ID cards can't be opened", LOST_NOTE, note) {
                AlbumButton("Reset ID cards", { resetting = true }, Modifier.fillMaxWidth(), ButtonVariant.Danger, icon = Sym.DeleteForever)
            }
        }
        AlbumSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4))
    }
    if (resetting) {
        val count = Safe.idItems.collectAsState(null).value?.size
        ConfirmBar(
            title = "Reset ID cards?",
            body = (count?.takeIf { it > 0 }?.let { "Their ${itemCount(it)} can't be opened any more, by Album or anyone else. " } ?: "") +
                "Resetting erases them from this phone and starts ID cards again. Private safe isn't touched. This cannot be undone.",
            confirmLabel = "Hold to reset",
            onConfirm = {
                resetting = false
                scope.launch {
                    IdCards.reset(context).join()
                    gate = IdCards.gate(context)
                }
            },
            onCancel = { resetting = false },
            icon = Sym.DeleteForever,
            hold = true,
        )
    }
}

/**
 * ID cards while open: Private safe's grid with its day headers, tiles and viewer, a note while the privacy PIN keeps no
 * copy of the key, and Share, Move out and Delete forever for a selection.
 */
@Composable
private fun OpenIdCards(key: ByteArray, onBack: () -> Unit) {
    val context = LocalContext.current
    val spec = AlbumTheme.spec
    val c = spec.colors
    val scope = rememberCoroutineScope()
    val items by Safe.idItems.collectAsState(null)
    val list = items.orEmpty()
    val copied by IdCards.pinCopy.collectAsState()
    val pin by PrivacyLock.setUp.collectAsState()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDelete by remember { mutableStateOf<List<SafeItem>?>(null) }
    var keeping by remember { mutableStateOf(false) } // the PIN sheet that keeps a copy of the key
    var sharing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) { for (message in Safe.safeMessages) snackbar.showSnackbar(message) }
    val chosen = list.filter { it.id in selected } // ignores items that left meanwhile
    val selecting = chosen.isNotEmpty()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { if (selecting) selected = emptySet() else onBack() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(c.canvas).statusBarsPadding()) {
                CollapsedTopBar("ID cards", subtitle = "${itemCount(list.size)} · locks when you leave", onBack = onBack) {
                    AlbumIconButton(Sym.Lock, "Lock now", {
                        CrashLog.note("id cards lock now")
                        IdCards.lock()
                    })
                }
            }
            // Turning off the screen lock would lose them: said before it happens, with the way to keep a second way in
            if (!copied) {
                Group(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s3)) {
                    GroupRow(if (pin) "Keep a copy for your PIN" else "Set a privacy PIN", { keeping = true }, icon = Sym.Pin, sub = NO_COPY_NOTE)
                }
            }
            when {
                items == null -> {} // first load takes a few ms
                list.isEmpty() -> EmptyState(
                    Sym.IdCard, "No ID cards yet",
                    "Aadhaar, PAN, passport or licence photos you move here leave the Library and search, and open only with your screen lock.",
                )
                else -> SafeGrid(list, key, chosen.mapTo(HashSet()) { it.id }, { selected = it }, { viewing = it }, bottom + if (selecting) SelectionClearance else Space.s8)
            }
        }
        AnimatedVisibility(
            selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4),
            enter = dockRise(),
            exit = ExitTransition.None,
        ) {
            SelectionBar(
                chosen.size,
                listOf(
                    SelAction(Sym.Share, "Share") {
                        if (!sharing && context.canSend(chosen.size, MAX_SHARE, "share")) {
                            CrashLog.note("id cards share ${chosen.size}")
                            val picked = chosen
                            sharing = true
                            scope.launch {
                                runCatching { IdCards.share(context, picked, key.copyOf()) } // wipes its copy of the key
                                    .onSuccess {
                                        selected = emptySet()
                                        context.startSafely(it)
                                    }
                                    .onFailure {
                                        CrashLog.note("id cards share failed: ${it.javaClass.simpleName}")
                                        snackbar.showSnackbar("Couldn't share ${if (picked.size == 1) "it" else "them"}. Nothing was changed.")
                                    }
                                sharing = false
                            }
                        }
                    },
                    SelAction(Sym.LockOpen, "Move out") {
                        CrashLog.note("id cards move out ${chosen.size}")
                        Safe.moveOut(context.applicationContext, chosen, key.copyOf()) // the job wipes its copy when done
                        selected = emptySet()
                    },
                    SelAction(Sym.DeleteForever, "Delete forever", danger = true) { toDelete = chosen },
                ),
                onClose = { selected = emptySet() },
                onSelectAll = if (chosen.size < list.size) ({ selected = list.mapTo(HashSet()) { it.id } }) else null,
            )
        }
        AlbumSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = Space.s4 + if (selecting) SelectionBarHeight + Space.s2 else 0.dp),
        )

        viewing?.let { id ->
            val start = list.indexOfFirst { it.id == id }
            if (start >= 0) SafeViewer(list, start, key, onClose = { viewing = null }) // else moved out or deleted meanwhile
        }
    }

    toDelete?.let { doomed ->
        ConfirmBar(
            title = "Delete ${itemCount(doomed.size)} forever?",
            body = "${if (doomed.size == 1) "It" else "They"} will be erased from this phone. This cannot be undone.",
            confirmLabel = "Hold to delete",
            onConfirm = {
                CrashLog.note("id cards delete forever ${doomed.size}")
                toDelete = null
                selected = emptySet()
                Safe.delete(doomed)
            },
            onCancel = { toDelete = null },
            icon = Sym.DeleteForever,
            hold = true,
        )
    }
    // The safe key the PIN opens seals a copy of this key; privacy itself stays locked
    if (keeping) {
        PrivacySheet("Keep a way into ID cards", onDone = {}, onDismiss = { keeping = false }, resettable = false, onKey = { safeKey ->
            keeping = false
            IdCards.keepCopyWith(safeKey)
            safeKey.fill(0)
        })
    }
}
