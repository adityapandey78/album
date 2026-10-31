package com.aditya.album

import android.app.ActivityManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The note on Private safe and ID cards while they hold anything: nothing else on the phone has these photos. */
const val ONLY_COPY_NOTE =
    "Uninstalling Album, clearing its data or resetting the phone deletes these. When you uninstall, tick Keep app data."

/** Settings > Privacy and safe says the same under its first group. */
const val ONLY_COPY_SETTINGS =
    "Private safe and ID cards are the only copy on this phone. Uninstalling Album, clearing its data or resetting the phone " +
        "deletes them. When you uninstall, tick Keep app data."

/** The one sentence the first move into Private safe or ID cards adds, for [n] items. */
fun onlyCopyLine(n: Int) =
    if (n == 1) "It'll be the only copy, so uninstalling Album or clearing its data deletes it."
    else "They'll be the only copy, so uninstalling Album or clearing its data deletes them."

/** What Manage space warns before anything can be erased. */
private const val ERASE_WARNING =
    "These are the only copies. Erasing Album's data deletes them for ever. Move them out first if you want to keep them."

// Hidden for now: back the next time Album starts, so the warning is never gone for good
private var onlyCopyHidden by mutableStateOf(false)

/**
 * The note at the top of Private safe and ID cards while they hold items: these are the only copies, and what erases them.
 * Hide for now puts it away until Album starts again.
 */
@Composable
fun OnlyCopyNote(modifier: Modifier = Modifier) {
    if (onlyCopyHidden) return
    Group(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupRow("Only copy on this phone", null, Modifier.weight(1f), icon = Sym.Info, sub = ONLY_COPY_NOTE)
            AlbumIconButton(Sym.Close, "Hide for now", { onlyCopyHidden = true }, Modifier.padding(end = Space.s1))
        }
    }
}

/**
 * Album's "Manage space", which Android's app settings show instead of Clear storage (manageSpaceActivity). Private safe and
 * ID cards keep the only copy of what's in them, so this says how many items each holds (counts only, from safe.db; nothing
 * is unlocked or shown) and warns before anything erases them. Clear cache is always safe; Erase all Album data asks on a
 * card first, held when anything would be lost. Screenshots are blocked.
 */
class ManageSpaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        AlbumPrefs.load(this)
        AppearancePrefs.load(this)
        Safe.init(this)
        setContent {
            val appearance by AppearancePrefs.appearance.collectAsState()
            // Status bar icons follow Album's theme, as in MainActivity
            val dark = resolvedTheme(appearance.theme) != ThemeMode.Light
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            AlbumTheme(appearance) { ManageSpaceScreen(onClose = ::finish) }
        }
    }
}

/** The bytes in [dir], all the way down. */
private fun sizeOf(dir: File?): Long = dir?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

@Composable
private fun ManageSpaceScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) } // Private safe, ID cards
    var cache by remember { mutableStateOf<Long?>(null) }
    var erasing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            counts = Safe.count(SafeItem.SAFE) to Safe.count(SafeItem.ID_CARDS)
            cache = sizeOf(context.cacheDir) + sizeOf(context.externalCacheDir)
        }
    }
    val kept = counts?.let { it.first + it.second }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            LargeTopBar("Manage space", subtitle = "Album's storage", onBack = onClose)
            Column(
                Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s8).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.s6),
            ) {
                Group(title = "Only copies", note = if (kept == 0) "Nothing is in Private safe or ID cards." else ERASE_WARNING) {
                    GroupRow("Private safe", null, icon = Sym.Lock, value = counts?.let { itemCount(it.first) })
                    GroupRow("ID cards", null, icon = Sym.IdCard, value = counts?.let { itemCount(it.second) })
                }
                Group {
                    GroupRow("Open Album", {
                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let(context::startSafely) // the enabled icon, disguise or not
                        onClose()
                    }, icon = Sym.OpenInNew, sub = "To move items out of Private safe and ID cards")
                    GroupRow("Clear cache", {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                                cache = sizeOf(context.cacheDir) + sizeOf(context.externalCacheDir)
                            }
                            snackbar.showSnackbar("Cache cleared")
                        }
                    }, icon = Sym.CleaningServices, sub = "Thumbnails and temporary files only. Nothing you keep is touched.",
                        value = cache?.let { Formatter.formatShortFileSize(context, it) }, trailing = null)
                }
                Group(note = "Album's settings, collections, people and index go too. Photos in the Library stay.") {
                    GroupRow("Erase all Album data", { if (kept != null) erasing = true }, icon = Sym.DeleteForever, danger = true, trailing = null)
                }
            }
        }
        AlbumSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.s4))
    }

    if (erasing) {
        val (safe, ids) = counts ?: (0 to 0)
        val lost = listOfNotNull(
            "${itemCount(safe)} in Private safe".takeIf { safe > 0 },
            "${itemCount(ids)} in ID cards".takeIf { ids > 0 },
        ).joinToString(" and ")
        ConfirmBar(
            title = "Erase all Album data?",
            body = if (lost.isEmpty()) "Album starts again as if just installed. Photos in the Library stay."
            else "The $lost are deleted for ever, with no other copy. This cannot be undone.",
            confirmLabel = if (lost.isEmpty()) "Erase" else "Hold to erase",
            onConfirm = {
                erasing = false
                CrashLog.note("manage space erase")
                // Android stops Album and deletes everything it keeps; false only if it refused
                if (!context.getSystemService(ActivityManager::class.java).clearApplicationUserData()) {
                    scope.launch { snackbar.showSnackbar("Android didn't erase Album's data. Nothing was changed.") }
                }
            },
            onCancel = { erasing = false },
            icon = Sym.DeleteForever,
            hold = lost.isNotEmpty(),
        )
    }
}
