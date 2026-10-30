package com.aditya.album

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

// The design system's components (Button, IconButton, Chip, Badge, Switch, Sheet, ConfirmBar, Snackbar, TopBar, ...).
// Every colour, size and shape comes from AlbumTheme; see Theme.kt.

// ---------- Icon ----------

// Material Symbols Rounded, light (weight 300) at rest and filled at weight 500 when active.
private fun symbols(fill: Boolean, small: Boolean) = FontFamily(
    Font(
        R.font.symbols,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("FILL", if (fill) 1f else 0f),
            FontVariation.weight(if (fill) 500 else if (small) 400 else 300),
            FontVariation.Setting("opsz", if (small) 20f else 24f),
            FontVariation.Setting("GRAD", 0f),
        ),
    )
)

private val symbolFonts = listOf(false, true).flatMap { small -> listOf(false, true).map { fill -> symbols(fill, small) } }

/**
 * A Material Symbols icon. [contentDescription] only when the icon stands alone and means something; inside a labelled
 * button leave it null. [filled] for the active or chosen state; the change crossfades.
 */
@Composable
fun Icon(
    sym: Sym,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = Space.icon,
    tint: Color = LocalContentColor.current,
) {
    val small = size < Space.icon
    val fontSize = with(LocalDensity.current) { size.toSp() } // icons keep their size when the phone's font size grows
    val a11y = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }
    } else Modifier.clearAndSetSemantics {} // the glyph is a private-use character; never read it out
    Crossfade(filled, modifier.size(size).then(a11y), animationSpec = tween(200), label = "icon") { fill ->
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            BasicText(
                sym.glyph,
                style = TextStyle(
                    color = tint, fontSize = fontSize, lineHeight = fontSize,
                    fontFamily = symbolFonts[(if (small) 2 else 0) + if (fill) 1 else 0],
                ),
                maxLines = 1,
            )
        }
    }
}

// ---------- Haptics ----------

/** Haptic ticks from the design system's table. Silent when Haptic feedback is off in Settings. */
class Haptics(private val view: View, private val on: Boolean) {
    private fun play(constant: Int) {
        if (on) view.performHapticFeedback(constant)
    }

    /** Long-press starts selection. */
    fun longPress() = play(HapticFeedbackConstants.LONG_PRESS)

    /** Each pinch step, each month the scrubber passes. */
    fun tick() = play(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    /** Each tile added while drag-selecting. */
    fun lightTick() = play(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    /** A year boundary in the scrubber. */
    fun clockTick() = play(HapticFeedbackConstants.CLOCK_TICK)

    /** A swipe passes its close or keep/trash threshold. */
    fun threshold() = play(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)

    /** Hold-to-delete completes. */
    fun confirm() = play(HapticFeedbackConstants.CONFIRM)

    /** An action was refused. */
    fun reject() = play(HapticFeedbackConstants.REJECT)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val on = AlbumTheme.spec.appearance.haptics
    return remember(view, on) { Haptics(view, on) }
}

// ---------- Floating chrome ----------

/** Glass with shadow-float, for chrome that floats over photos (bottom bar, dock, selection bar, scrubber). */
@Composable
fun Modifier.floating(shape: Shape = CircleShape): Modifier {
    val c = AlbumTheme.colors
    // Shadows vanish on black, so dark themes add a 1dp light hairline as the design's dark shadow-float does.
    // ponytail: no backdrop blur (it would cost frame time over a scrolling grid); glass is opaque enough to read on.
    val edge = if (c.dark) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape) else Modifier
    return this
        .shadow(if (c.dark) 14.dp else 10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = if (c.dark) 0.7f else 0.18f))
        .then(edge)
        .background(c.glass, shape)
}

/** Scales a control down while pressed, springing back. */
@Composable
private fun Modifier.pressScale(pressed: Boolean, to: Float): Modifier {
    val still = AlbumTheme.spec.still
    val scale by animateFloatAsState(if (pressed && !still) to else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// ---------- Button ----------

enum class ButtonVariant { Primary, Tonal, Quiet, Danger }

/**
 * A pill-shaped text button that squares toward radius-md while pressed. Label: a verb first, in sentence case, naming
 * the result ("Move to trash", "Keep 148"). At most one Primary per screen.
 */
@Composable
fun AlbumButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Tonal,
    icon: Sym? = null,
    enabled: Boolean = true,
) {
    val c = AlbumTheme.colors
    val (bg, fg) = when (variant) {
        ButtonVariant.Primary -> c.flash to c.onFlash
        ButtonVariant.Tonal -> c.surfaceHigh to c.ink
        ButtonVariant.Quiet -> Color.Transparent to c.flashInk
        ButtonVariant.Danger -> c.danger to c.onDanger
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed && !AlbumTheme.spec.still) Space.radiusMd else 100.dp, tween(220), label = "corner")
    val shape = RoundedCornerShape(corner)
    Row(
        modifier
            .pressScale(pressed, 0.96f)
            .defaultMinSize(minHeight = Space.target)
            .clip(shape)
            .background(bg, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .padding(horizontal = if (variant == ButtonVariant.Quiet) Space.s3 else Space.s6),
        horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            icon?.let { Icon(it, null, size = Space.iconSm) }
            Text(text, style = AlbumTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Press and hold to confirm something permanent ("Delete forever"): the danger fill sweeps across in 0.8 s, then
 * [onConfirm] runs with a CONFIRM haptic. Letting go early cancels.
 */
@Composable
fun HoldButton(text: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, icon: Sym = Sym.DeleteForever) {
    val c = AlbumTheme.colors
    val haptics = rememberHaptics()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = CircleShape
    Box(
        modifier
            .defaultMinSize(minHeight = Space.target)
            .clip(shape)
            .background(c.dangerSoft, shape)
            .semantics { role = Role.Button; contentDescription = "$text, press and hold" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val run = scope.launch {
                        progress.animateTo(1f, tween(800, easing = LinearEasing))
                        haptics.confirm()
                        onConfirm()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        run.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        @Composable
        fun Inner(color: Color) = Row(
            Modifier.fillMaxSize().padding(horizontal = Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, size = Space.iconSm, tint = color)
            Text(text, style = AlbumTheme.type.label, color = color, maxLines = 1)
        }
        Inner(c.danger)
        // The filled layer is revealed left to right
        Box(
            Modifier.matchParentSize().drawWithContent {
                val w = size.width * progress.value
                drawContext.canvas.save()
                drawContext.canvas.clipRect(0f, 0f, w, size.height)
                drawRect(c.danger, size = Size(w, size.height))
                drawContent()
                drawContext.canvas.restore()
            },
        ) { Inner(c.onDanger) }
    }
}

// ---------- IconButton ----------

enum class IconButtonVariant { Plain, Tonal, Glass, Flash, Photo }

/** A 48dp round button with one icon. [label] is required: TalkBack reads it. [active] fills the icon (toggles). */
@Composable
fun AlbumIconButton(
    sym: Sym,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: IconButtonVariant = IconButtonVariant.Plain,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val c = AlbumTheme.colors
    val (bg, fg) = when (variant) {
        IconButtonVariant.Plain -> Color.Transparent to c.ink
        IconButtonVariant.Tonal -> c.surfaceHigh to c.ink
        IconButtonVariant.Glass -> c.glass to c.ink
        IconButtonVariant.Flash -> c.flash to c.onFlash
        IconButtonVariant.Photo -> c.scrimPhoto to c.onPhoto
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .pressScale(pressed, 0.88f)
            .size(Space.target)
            .then(if (variant == IconButtonVariant.Glass) Modifier.floating() else Modifier.background(bg, CircleShape))
            .clip(CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        contentAlignment = Alignment.Center,
    ) { Icon(sym, null, filled = active, tint = tint ?: fg) }
}

// ---------- Chip ----------

/**
 * A chip. Selected shows a check in flash-ink on flash-soft; the check, not the tint, carries the state. [toggle] false
 * makes it an action (a suggestion, a thing to open), which TalkBack then calls a button rather than a checkbox. [leading]
 * draws something other than a symbol in the icon's place (an app's own icon), [Space.iconSm] square.
 */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: Sym? = null,
    count: String? = null,
    toggle: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = AlbumTheme.colors
    val shape = CircleShape
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(c.flashSoft, shape) else Modifier.border(1.dp, c.lineStrong, shape))
            .then(if (toggle) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() }) else Modifier.clickable(role = Role.Button, onClick = onClick))
            .padding(start = if (selected || icon != null || leading != null) Space.s3 else Space.s4, end = Space.s4, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            selected -> Icon(Sym.Check, null, size = Space.iconSm, tint = c.flashInk)
            leading != null -> leading()
            icon != null -> Icon(icon, null, size = Space.iconSm, tint = c.ink)
        }
        Text(label, style = AlbumTheme.type.label, color = c.ink, maxLines = 1)
        count?.let { Text(it, style = AlbumTheme.type.meta, color = c.inkMuted) }
    }
}

// ---------- Badge ----------

enum class BadgeTone { Neutral, Photo, Flash, Soft, Danger }

/** One or two words or a number: "HDR", "0:42", "On-device", "Deletes in 3 days". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, tone: BadgeTone = BadgeTone.Neutral, icon: Sym? = null) {
    val c = AlbumTheme.colors
    val (bg, fg) = when (tone) {
        BadgeTone.Neutral -> c.surfaceHigh to c.ink
        BadgeTone.Photo -> c.scrimPhoto to c.onPhoto
        BadgeTone.Flash -> c.flash to c.onFlash
        BadgeTone.Soft -> c.flashSoft to c.flashInk
        BadgeTone.Danger -> c.dangerSoft to c.danger
    }
    Row(
        modifier.heightIn(min = 20.dp).background(bg, CircleShape).padding(horizontal = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, filled = true, size = 14.dp, tint = fg) }
        Text(text, style = if (tone == BadgeTone.Photo) AlbumTheme.type.meta else AlbumTheme.type.caption, color = fg, maxLines = 1)
    }
}

// ---------- Selection check ----------

/**
 * The round selection check on a tile ([onSurface] = false, over a photo) or a date header ([onSurface] = true).
 * [size] shrinks with the tiles: see checkSizeFor.
 */
@Composable
fun SelectCheck(checked: Boolean, modifier: Modifier = Modifier, onSurface: Boolean = false, size: Dp = Space.check) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val pop by animateFloatAsState(if (checked && !still) 1.08f else 1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow), label = "check")
    val (bg, ring) = when {
        checked -> c.flash to if (onSurface) c.flash else c.onFlash
        onSurface -> Color.Transparent to c.lineStrong
        else -> c.scrimPhoto to c.onPhoto
    }
    Box(
        modifier.size(size).scale(pop).background(bg, CircleShape).border(if (size < 20.dp) 1.5.dp else 2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Sym.Check, null, size = size * 2 / 3, tint = c.onFlash) }
}

// ---------- Switch ----------

/** An on/off switch: the thumb grows and shows a check when on, so the state never rests on colour alone. */
@Composable
fun AlbumSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val spec = spring<Dp>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    val x by animateDpAsState(if (checked) 22.dp else 6.dp, spec, label = "thumb")
    val d by animateDpAsState(if (checked) 24.dp else 16.dp, spec, label = "thumbSize")
    val track = if (checked) c.flash else c.surfaceHigh
    Box(
        modifier.size(52.dp, 32.dp).background(track, CircleShape).border(2.dp, if (checked) c.flash else c.lineStrong, CircleShape),
    ) {
        Box(
            Modifier.offset(x = x - 2.dp).align(Alignment.CenterStart).size(d).background(if (checked) c.onFlash else c.inkMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Sym.Check, null, size = 16.dp, tint = c.flash) }
    }
}

/** A settings row: icon, label, one line on what changes, and the switch. The whole row toggles. */
@Composable
fun SwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: Sym? = null,
) {
    val c = AlbumTheme.colors
    Row(
        modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = c.inkMuted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = AlbumTheme.type.body, color = c.ink)
            description?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
        AlbumSwitch(checked)
    }
}

// ---------- Rows and headings ----------

/** A section heading with an optional link on the right ("See all"). */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s6, bottom = Space.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = AlbumTheme.type.titleMd, color = AlbumTheme.colors.ink)
        if (action != null && onAction != null) {
            Text(
                action,
                Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = Space.s2, vertical = Space.s1),
                style = AlbumTheme.type.label,
                color = AlbumTheme.colors.flashInk,
            )
        }
    }
}

/** A row in a menu sheet: icon, label, optional second line, and a trailing chevron (or another icon, or none). */
@Composable
fun MenuRow(
    icon: Sym,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trailing: Sym? = Sym.ChevronRight,
    danger: Boolean = false,
) {
    val c = AlbumTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Space.radiusMd))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) c.danger else c.inkMuted)
        Column(Modifier.weight(1f)) {
            Text(label, style = AlbumTheme.type.body, color = if (danger) c.danger else c.ink)
            sub?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
        trailing?.let { Icon(it, null, tint = c.inkMuted) }
    }
}

/** A centred empty state: icon, title, one line. */
@Composable
fun EmptyState(icon: Sym, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = AlbumTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.s6, vertical = Space.s12),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Icon(icon, null, size = 40.dp, tint = c.inkMuted)
        Text(title, style = AlbumTheme.type.titleMd, color = c.ink)
        Text(body, style = AlbumTheme.type.body, color = c.inkMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.let {
            Spacer(Modifier.size(Space.s4))
            it()
        }
    }
}

// ---------- TopBar ----------

/**
 * The expanded screen title: actions on a row of their own, then the title in title-xl and a subtitle in meta. It sits in
 * the scrolling content (first item of a list or grid) and starts low on purpose, pushing photos into the thumb's half.
 * Pair it with [CollapsedTopBar], shown once this has scrolled away.
 */
@Composable
fun LargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = AlbumTheme.colors
    Column(modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Space.target), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { AlbumIconButton(Sym.ArrowBack, "Back", it, Modifier.offset(x = -Space.s3)) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.offset(x = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s1), content = actions)
        }
        Text(title, Modifier.padding(top = Space.s6), style = AlbumTheme.type.titleXl, color = c.ink)
        subtitle?.let { Text(it, Modifier.padding(top = Space.s1), style = AlbumTheme.type.meta, color = c.inkMuted) }
    }
}

/** The one-row title bar: back, title in title-md with an optional meta subtitle, up to two actions. */
@Composable
fun CollapsedTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = AlbumTheme.colors
    Column(modifier.fillMaxWidth().background(c.canvas)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (onBack == null) Space.s4 else Space.s1, end = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { AlbumIconButton(Sym.ArrowBack, "Back", it) }
            Column(Modifier.weight(1f)) {
                Text(title, style = AlbumTheme.type.titleMd, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = AlbumTheme.type.meta, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), content = actions)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** Shows [CollapsedTopBar]'s content over a screen once [visible], fading and sliding 8dp as in the design. */
@Composable
fun CollapsingBar(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val still = AlbumTheme.spec.still
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (still) fadeIn(tween(0)) else fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 6 },
        exit = if (still) fadeOut(tween(0)) else fadeOut(tween(150)) + slideOutVertically(tween(180)) { -it / 6 },
    ) { content() }
}

// ---------- Sheets ----------

/**
 * A bottom sheet on surface with radius-lg corners and a drag handle. Menus open here, never as drop-downs from the top.
 * A tall sheet stops under the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = AlbumTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The status bar is kept clear outside the sheet, not by padding inside it: Material pads the content by the part
        // of the status bar the sheet covers, so in a sheet within that distance of full height every drag changed its
        // height, which moved where it rests, which changed the padding again, and the sheet shook (Jump to a date)
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = Space.radiusLg, topEnd = Space.radiusLg),
        containerColor = c.surface,
        contentColor = c.ink,
        scrimColor = c.scrim,
        dragHandle = { Box(Modifier.padding(top = Space.s2, bottom = Space.s4).size(32.dp, 4.dp).background(c.lineStrong, CircleShape)) },
    ) {
        Column(Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s8)) {
            title?.let { Text(it, Modifier.padding(bottom = Space.s3), style = AlbumTheme.type.titleLg, color = c.ink) }
            content()
        }
    }
}

enum class ConfirmTone { Danger, Flash }

/**
 * A confirmation that rises from the bottom over a scrim, with big buttons where the thumb is. It replaces centred dialogs.
 * [title] is a question with the count ("Move 12 items to Trash?"), [body] says what happens next. [hold] for anything
 * permanent: the confirm button must be held for 0.8 s. [thumbs] shows up to three of the affected photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmBar(
    title: String,
    body: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    icon: Sym = Sym.Delete,
    tone: ConfirmTone = ConfirmTone.Danger,
    hold: Boolean = false,
    cancelLabel: String = "Cancel",
    thumbs: List<Media> = emptyList(),
) {
    val c = AlbumTheme.colors
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
        containerColor = Color.Transparent,
        contentColor = c.ink,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) }, // the card pads for the navigation bar itself
    ) {
        Column(
            Modifier.padding(horizontal = Space.s3).padding(bottom = 20.dp).navigationBarsPadding()
                .floating(RoundedCornerShape(Space.radiusLg)).background(c.surface, RoundedCornerShape(Space.radiusLg))
                .padding(Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s6),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                if (thumbs.isNotEmpty()) {
                    Box(Modifier.size(64.dp, Space.target)) {
                        thumbs.take(3).reversed().forEachIndexed { i, m ->
                            val k = thumbs.take(3).size - 1 - i // 0 is the front one
                            Thumbnail(
                                m,
                                Modifier.offset(x = (k * 8).dp).graphicsLayer { rotationZ = listOf(0f, -4f, 8f)[k] }.size(Space.target)
                                    .border(2.dp, c.surface, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier.size(Space.target).background(if (tone == ConfirmTone.Flash) c.flashSoft else c.dangerSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = if (tone == ConfirmTone.Flash) c.flashInk else c.danger) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Text(title, style = AlbumTheme.type.titleLg, color = c.ink)
                    body?.let { Text(it, style = AlbumTheme.type.body, color = c.inkMuted) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                AlbumButton(cancelLabel, onCancel, Modifier.weight(1f).heightIn(min = 56.dp))
                val wide = Modifier.weight(1.5f).heightIn(min = 56.dp)
                when {
                    hold -> HoldButton(confirmLabel, onConfirm, wide, icon)
                    tone == ConfirmTone.Flash -> AlbumButton(confirmLabel, onConfirm, wide, ButtonVariant.Primary, icon)
                    else -> AlbumButton(confirmLabel, onConfirm, wide, ButtonVariant.Danger, icon)
                }
            }
        }
    }
}

// ---------- Snackbar ----------

/**
 * Use as the SnackbarHost's snackbar: a pill on surface-high with the message and one way back ("Undo" in flash-ink).
 * Messages are past tense with the count: "Moved 12 items to Trash".
 */
@Composable
fun AlbumSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    Row(
        modifier.padding(horizontal = Space.s4).fillMaxWidth().heightIn(min = Space.target)
            .floating(CircleShape).background(c.surfaceHigh, CircleShape)
            .padding(start = Space.s4, end = Space.s1, top = Space.s1, bottom = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(data.visuals.message, Modifier.weight(1f), style = AlbumTheme.type.body, color = c.ink)
        data.visuals.actionLabel?.let { action ->
            Row(
                Modifier.heightIn(min = 40.dp).clip(CircleShape).clickable(role = Role.Button) { data.performAction() }.padding(horizontal = Space.s4),
                horizontalArrangement = Arrangement.spacedBy(Space.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (action == "Undo") Icon(Sym.Undo, null, size = Space.iconSm, tint = c.flashInk)
                Text(action, style = AlbumTheme.type.label, color = c.flashInk)
            }
        }
    }
}

// ---------- Logo ----------

/**
 * The Album mark, drawn as the home-screen icon shows it (tools/app_icon.py): a fanned stack of prints on a near-black
 * circle, the top one a photo with a sun in the accent. The same on every ground, so it reads as the app's own icon.
 */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val sun = logoSun(AlbumTheme.spec.appearance.accent)
    Canvas(modifier.size(size).semantics { contentDescription = "Album" }) {
        // The icon's 108-unit grid as a launcher shows it: its middle 72 units, the drawing scaled about (54, 55) as
        // tools/app_icon.py scales it (SCALE)
        val u = this.size.width / 72f
        val k = 0.9f
        fun at(x: Float, y: Float) = Offset(((x - 54) * k + 36) * u, ((y - 55) * k + 36) * u)
        val print = Size(50 * k * u, 50 * k * u)
        val corner = CornerRadius(10 * k * u)
        val pivot = at(54f, 55f)
        drawCircle(Ink)
        rotate(-14f, pivot) { drawRoundRect(Color(0xFF5F5E5A), at(29f, 30f), print, corner) }
        rotate(8f, pivot) { drawRoundRect(Color(0xFFB4B2A9), at(29f, 30f), print, corner) }
        drawRoundRect(Paper, at(29f, 32f), print, corner)
        val mountains = Path().apply {
            listOf(34f to 78f, 48f to 61f, 57f to 71f, 64f to 65f, 74f to 78f).forEachIndexed { i, (x, y) ->
                val p = at(x, y)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(mountains, Ink)
        drawCircle(sun, 7 * k * u, at(64f, 46f))
    }
}

/** The logo's sun: the accent's fill as on a dark ground. Mono's is the paper itself, so there the sun is cut out. */
@Composable
private fun logoSun(accent: Accent): Color = when (accent) {
    Accent.Wallpaper -> colorResource(android.R.color.system_accent1_400) // as the launcher icon's
    Accent.Mono -> Ink
    else -> albumColors(ThemeMode.Dark, accent).flash
}

/** Content padding that clears the floating bottom bar: bar height plus space-8, plus the navigation bar. */
fun bottomClearance(navigationBar: Dp) = PaddingValues(bottom = Space.bar + Space.s8 + 20.dp + navigationBar)

/** A photo or video thumbnail, cropped to fill, on surface-high while it loads. */
@Composable
fun Thumbnail(m: Media, modifier: Modifier = Modifier) {
    AsyncImage(Thumb(m.uri), null, modifier.background(AlbumTheme.colors.surfaceHigh), contentScale = ContentScale.Crop)
}

// ---------- Bottom bar ----------

class NavItem(val sym: Sym, val label: String)

/**
 * The main navigation: a floating glass pill of destinations plus a separate search disc, both in the thumb's reach. The
 * active destination is a flash pill with a filled icon and its label; the others show only their icon. [onSelect] gets
 * the tapped item's centre in window coordinates (the Reveal tab motion grows from there).
 */
@Composable
fun BottomBar(
    items: List<NavItem>,
    active: Int,
    onSelect: (index: Int, center: Offset) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    Row(modifier.fillMaxWidth().padding(horizontal = Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(Space.bar).floating().padding(horizontal = Space.s2).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val on = i == active
                val grow by animateFloatAsState(
                    if (on) 2.2f else 1f,
                    if (still) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    label = "pill",
                )
                var center by remember { mutableStateOf(Offset.Zero) }
                Row(
                    Modifier.weight(grow).height(Space.target)
                        .onGloballyPositioned { center = it.boundsInWindow().center }
                        .clip(CircleShape)
                        .background(if (on) c.flash else Color.Transparent, CircleShape)
                        .selectable(on, role = Role.Tab, onClick = { onSelect(i, center) })
                        .semantics { contentDescription = item.label },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.sym, null, filled = on, tint = if (on) c.onFlash else c.ink)
                    if (on) {
                        Text(
                            item.label, Modifier.padding(start = Space.s2), style = AlbumTheme.type.label, color = c.onFlash,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
        Box(
            Modifier.size(Space.bar).floating().clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Search", onClick = onSearch)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.Center,
        ) { Icon(Sym.Search, null, tint = c.ink) }
    }
}

// ---------- Selection bar ----------

class SelAction(val sym: Sym, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** [SelectionBar]'s height (count row, gap, action bar), and the room a grid leaves under its last row for it. */
val SelectionBarHeight = Space.target + Space.s2 + Space.bar
val SelectionClearance = SelectionBarHeight + Space.s4 + Space.s8

/**
 * Replaces the bottom bar while selecting: the count and Select all above, labelled actions below, all within one thumb's
 * reach. Pass null [onSelectAll] to hide it. A danger action sits on a danger-soft capsule so it reads on any photo.
 */
@Composable
fun SelectionBar(
    count: Int,
    actions: List<SelAction>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    allLabel: String = "Select all",
) {
    val c = AlbumTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.floating().padding(end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                AlbumIconButton(Sym.Close, "Cancel selection", onClose)
                Text(
                    "$count selected", style = AlbumTheme.type.label, color = c.ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            onSelectAll?.let {
                Box(
                    Modifier.height(Space.target).floating().clip(CircleShape).clickable(role = Role.Button, onClick = it).padding(horizontal = Space.s4),
                    contentAlignment = Alignment.Center,
                ) { Text(allLabel, style = AlbumTheme.type.label, color = c.ink) }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(Space.bar).floating().padding(horizontal = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { a ->
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                Column(
                    Modifier.weight(1f).height(Space.bar - Space.s2).pressScale(pressed, 0.92f).clip(CircleShape)
                        .background(if (a.danger) c.dangerSoft else Color.Transparent, CircleShape)
                        .clickable(source, indication = null, role = Role.Button, onClick = a.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    val tint = if (a.danger) c.danger else c.ink
                    Icon(a.sym, null, tint = tint)
                    Text(a.label, style = AlbumTheme.type.caption, color = tint, maxLines = 1)
                }
            }
        }
    }
}
