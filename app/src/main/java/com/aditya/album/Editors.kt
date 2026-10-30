package com.aditya.album

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Edit hands a photo or video to another app's editor. The first time it asks which ("Edit with"); ticking
// "Don't ask again" remembers that app until it's uninstalled or reset in Settings.

private const val GOOGLE_PHOTOS = "com.google.android.apps.photos"

/** The editing app picked with "Don't ask again", in the "settings" prefs. */
object EditorPrefs {
    private const val KEY = "editor_package"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow<String?>(null)

    /** Package name of the remembered editing app; null asks every time. */
    val chosen = state.asStateFlow()

    /** Safe to call repeatedly; the "settings" prefs are already loading from AlbumApp. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        state.value = prefs.getString(KEY, null)
    }

    fun set(pkg: String?) {
        state.value = pkg
        prefs.edit { if (pkg == null) remove(KEY) else putString(KEY, pkg) }
    }
}

/** ACTION_EDIT for [uri], for the app [pkg] (null: any editor). [write] lets the editor save over the original. */
private fun editIntent(uri: Uri, mime: String, write: Boolean, pkg: String?): Intent =
    Intent(Intent.ACTION_EDIT).setDataAndType(uri, mime).setPackage(pkg).addFlags(
        if (write) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )

/** An app that can edit a file: package, name and icon. */
private class Editor(val pkg: String, val label: String, val icon: ImageBitmap)

/** What the "Edit with" sheet is asking about. */
private class EditChoice(val m: Media, val mime: String, val generation: Long?, val editors: List<Editor>)

private fun PackageManager.editActivities(intent: Intent) = queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))

/** Apps that can edit [uri] of type [mime], Google Photos first and the rest by name. Blocking: call off the main thread. */
private fun editorsFor(context: Context, uri: Uri, mime: String): List<Editor> {
    val pm = context.packageManager
    val px = (40 * context.resources.displayMetrics.density).toInt()
    return pm.editActivities(editIntent(uri, mime, write = false, pkg = null))
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName } // an app with two edit screens is still one choice
        .filter { it.packageName != context.packageName }
        .map { Editor(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(px, px).asImageBitmap()) }
        .sortedWith(compareBy({ it.pkg != GOOGLE_PHOTOS }, { it.label.lowercase() }))
}

/**
 * Starts [uri] in [pkg]'s editor, else in any editor (Android asks which when there are several); false when no app on
 * the phone can edit it. Write access comes first so an edit can replace the original; where Album can't hand that out
 * (another app's file, a file from another app's provider) the editor gets read access and saves a copy.
 */
private fun openEditor(uri: Uri, mime: String, pkg: String?, start: (Intent) -> Unit): Boolean {
    for (target in listOf(pkg, null).distinct()) {
        for (write in listOf(true, false)) {
            try {
                // Not Intent.createChooser for the fallback: a chooser always opens, even empty, so it could never tell
                // that no app can edit this. A plain implicit intent gets Android's own picker or ActivityNotFoundException.
                start(editIntent(uri, mime, write, target))
                return true
            } catch (e: ActivityNotFoundException) {
                break // uninstalled meanwhile, or can't edit this type: try the next
            } catch (e: SecurityException) {
                CrashLog.note("edit: can't grant write access ($e)") // again, read-only
            }
        }
    }
    return false
}

/**
 * Returns a function that opens a photo or video in an editor: the remembered one if it can still edit this file, else
 * whichever the person picks in the "Edit with" sheet, which this draws while it asks. Says so in [onMessage] when
 * nothing on the phone can edit it. If the editor saved over the file, every cached copy reloads once the person is back.
 */
@Composable
fun rememberEdit(onMessage: (String) -> Unit): (Media) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable: Android may recreate the viewer while the editor is open
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var before by rememberSaveable { mutableStateOf<Long?>(null) }
    var asking by remember { mutableStateOf<EditChoice?>(null) }
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = editing?.let(Uri::parse)
        val generation = before
        editing = null
        // Only MediaStore files have a generation; anything else is never written back by an editor Album opens
        if (uri == null || generation == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            // MediaStore rescans a file once the editor closes it; most editors save a copy instead, so this times out
            val changed = withContext(Dispatchers.IO) {
                withTimeoutOrNull(3_000) { Gallery.changes(resolver).first { runCatching { generationOf(resolver, uri) }.getOrNull() != generation } }
            } != null
            if (changed) {
                CrashLog.note("edit saved over the original")
                reloadEdited(context, uri)
            }
        }
    }
    fun open(m: Media, mime: String, generation: Long?, pkg: String?) {
        editing = m.uri.toString()
        before = generation
        if (!openEditor(m.uri, mime, pkg, editor::launch)) {
            editing = null
            onMessage("No app on this phone can edit this")
        }
    }

    asking?.let { choice ->
        EditWithSheet(choice.editors, onDismiss = { asking = null }) { pkg, always ->
            CrashLog.note("edit with ${if (always) "always " else ""}$pkg")
            if (always) EditorPrefs.set(pkg)
            asking = null
            open(choice.m, choice.mime, choice.generation, pkg)
        }
    }
    return { m ->
        CrashLog.note("edit")
        EditorPrefs.load(context)
        scope.launch {
            val resolver = context.contentResolver
            val (type, generation) = withContext(Dispatchers.IO) {
                runCatching { resolver.getType(m.uri) }.getOrNull() to runCatching { generationOf(resolver, m.uri) }.getOrNull()
            }
            val mime = type ?: if (m.isVideo) "video/*" else "image/*"
            val saved = EditorPrefs.chosen.value
            val editors = withContext(Dispatchers.IO) {
                // The remembered app goes straight in while it's installed and can edit this type; otherwise ask again
                if (saved != null && context.packageManager.editActivities(editIntent(m.uri, mime, write = false, pkg = saved)).isNotEmpty()) null
                else editorsFor(context, m.uri, mime)
            }
            when {
                editors == null -> open(m, mime, generation, saved)
                editors.isEmpty() -> open(m, mime, generation, null) // Android's own picker, or "No app on this phone can edit this"
                else -> asking = EditChoice(m, mime, generation, editors)
            }
        }
    }
}

/** Lists the apps that can edit, with a "Don't ask again" box under them; [onPick] gets the app and the box. */
@Composable
private fun EditWithSheet(editors: List<Editor>, onDismiss: () -> Unit, onPick: (pkg: String, always: Boolean) -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    var always by rememberSaveable { mutableStateOf(false) }
    Sheet(onDismiss, title = "Edit with") {
        editors.forEach { e ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Space.radiusMd))
                    .clickable(role = Role.Button) { onPick(e.pkg, always) }.padding(horizontal = Space.s1),
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(e.icon, null, Modifier.size(40.dp))
                Text(e.label, Modifier.weight(1f), style = type.body, color = c.ink)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.s2)
                .drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
                .padding(top = Space.s2).heightIn(min = 56.dp).clip(RoundedCornerShape(Space.radiusMd))
                .toggleable(always, role = Role.Checkbox) { always = it }.padding(horizontal = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectCheck(always, Modifier.padding(horizontal = Space.s2), onSurface = true)
            Column(Modifier.weight(1f)) {
                Text("Don't ask again", style = type.body, color = c.ink)
                Text("Change it in Settings, under Editing app", style = type.caption, color = c.inkMuted)
            }
        }
    }
}
