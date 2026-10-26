package com.aditya.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// Rotate mode: the photo turns on screen a quarter at a time and nothing is written until Save. Save then asks how
// (unless "Don't ask again" or Settings said), and the viewer updates the original or saves a copy (see Rotate.kt).

/** Rotate mode over photo [m]: [turns] quarter turns clockwise (negative: anticlockwise) shown on screen, not saved yet. */
@Stable
class RotateMode(val m: Media, turns: Int) {
    var turns by mutableIntStateOf(turns)

    /** Whether its original can be turned in place, losing nothing (see turnsInPlace); null until checked. */
    var inPlace by mutableStateOf<Boolean?>(null)

    /** While saving, the bar waits. */
    var saving by mutableStateOf(false)

    /** Quarter turns clockwise Save would apply: 0 to 3. */
    val net get() = Math.floorMod(turns, 4)
}

/**
 * Rotate mode's bar, where the dock was: turn left or right, Discard, and Save once there's a turn to save. The rotate
 * buttons tell TalkBack how the photo is turned now.
 */
@Composable
fun RotateBar(mode: RotateMode, onTurn: (quarters: Int) -> Unit, onDiscard: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val idle = !mode.saving
    val now = Modifier.semantics { stateDescription = turnWords(mode.turns) }
    Row(
        // TalkBack says "Rotate" as the bar appears
        modifier.semantics { paneTitle = "Rotate" }.height(Space.bar).floating().padding(horizontal = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumIconButton(Sym.RotateLeft, "Rotate left", { onTurn(-1) }, now, enabled = idle)
        AlbumIconButton(Sym.RotateRight, "Rotate right", { onTurn(1) }, now, enabled = idle)
        AlbumButton("Discard", onDiscard, Modifier.padding(start = Space.s2), ButtonVariant.Quiet, enabled = idle)
        AlbumButton(if (mode.saving) "Saving" else "Save", onSave, variant = ButtonVariant.Primary, enabled = idle && mode.net != 0)
    }
}

/**
 * "Save rotated photo": a copy (the primary), and Update original when [inPlace] says the original can be turned without
 * losing anything; otherwise one line says why only a copy is offered. "Don't ask again" makes the pick what Save does
 * from then on (see [RotatePrefs], also in Settings). [onSave] gets Copy or Original and the box.
 */
@Composable
fun SaveRotatedSheet(inPlace: Boolean, onDismiss: () -> Unit, onSave: (choice: RotateSave, always: Boolean) -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    var always by rememberSaveable { mutableStateOf(false) }
    Sheet(onDismiss, title = "Save rotated photo") {
        Text(
            if (inPlace) "Both keep full quality. A copy leaves the original as it is."
            else "This photo can only be turned by saving it again, so it's saved as a rotated copy. The original stays as it is.",
            Modifier.padding(bottom = Space.s4),
            style = type.body,
            color = c.inkMuted,
        )
        // Stacked, so the labels never cut short at a large font size
        val big = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            AlbumButton("Save as a copy", { onSave(RotateSave.Copy, always) }, big, ButtonVariant.Primary)
            if (inPlace) AlbumButton("Update original", { onSave(RotateSave.Original, always) }, big)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.s4)
                .drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
                .padding(top = Space.s2).heightIn(min = 56.dp).clip(RoundedCornerShape(Space.radiusMd))
                .toggleable(always, role = Role.Checkbox) { always = it }.padding(horizontal = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectCheck(always, Modifier.padding(horizontal = Space.s2), onSurface = true)
            Column(Modifier.weight(1f)) {
                Text("Don't ask again", style = type.body, color = c.ink)
                Text("Change it in Settings, under Saving rotated photos", style = type.caption, color = c.inkMuted)
            }
        }
    }
}

/** For Settings > Behaviour: what Save in rotate mode does. Tapping opens the three choices. */
@Composable
fun RotateSaveRow(modifier: Modifier = Modifier) {
    RotatePrefs.load(LocalContext.current)
    val choice by RotatePrefs.choice.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    MenuRow(Sym.RotateRight, "Saving rotated photos", { choosing = true }, modifier, sub = choice.label)
    if (!choosing) return
    val c = AlbumTheme.colors
    Sheet({ choosing = false }, "Saving rotated photos") {
        RotateSave.entries.forEach { option ->
            val on = option == choice
            MenuRow(
                when (option) {
                    RotateSave.Ask -> Sym.Help
                    RotateSave.Copy -> Sym.ContentCopy
                    RotateSave.Original -> Sym.Image
                },
                option.label,
                {
                    CrashLog.note("rotate save ${option.name}")
                    RotatePrefs.set(option)
                    choosing = false
                },
                // The check carries the choice; the tint only backs it up
                if (on) Modifier.background(c.flashSoft, RoundedCornerShape(Space.radiusMd)) else Modifier,
                sub = when (option) {
                    RotateSave.Ask -> null
                    RotateSave.Copy -> "The original stays as it is"
                    RotateSave.Original -> "Lossless where possible, otherwise a copy"
                },
                trailing = if (on) Sym.Check else null,
            )
        }
    }
}

/** How the photo in rotate mode is turned, [quarters] clockwise: TalkBack hears it on the rotate buttons. */
fun turnWords(quarters: Int) = when (Math.floorMod(quarters, 4)) {
    1 -> "Turned right"
    2 -> "Upside down"
    3 -> "Turned left"
    else -> "Not turned"
}

/** The snackbar once an original was turned [quarters] clockwise (1 to 3). */
fun rotatedMessage(quarters: Int) = when (Math.floorMod(quarters, 4)) {
    1 -> "Rotated right"
    3 -> "Rotated left"
    else -> "Rotated 180°"
}

/**
 * The snackbar once a rotated copy went to [album]: with its [megapixels] when it had to be smaller than the original,
 * and a [note] when Update original was asked for but a copy was all that could be done.
 */
fun copyMessage(album: String, megapixels: Int? = null, note: String? = null): String {
    val saved = if (megapixels == null) "Saved a rotated copy to $album" else "Saved a $megapixels MP rotated copy to $album"
    return if (note == null) saved else "$saved. $note"
}
