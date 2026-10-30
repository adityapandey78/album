package com.aditya.album

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.statusBars
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
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
    val fade = if (AlbumTheme.spec.still) snap<Float>() else tween(200)
    Crossfade(filled, modifier.size(size).then(a11y), animationSpec = fade, label = "icon") { fill ->
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

/**
 * What lies behind the floating chrome, recorded by [backdropSource], for [floating] to blur. Provided only around chrome
 * drawn outside that recording (the bottom bar, the selection bar, messages): never inside it, nor around a dialog.
 */
val LocalBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/** Draws the content and records it into [layer] as well, so chrome given it as [LocalBackdrop] can blur it. */
fun Modifier.backdropSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * Glass with shadow-float, for chrome that floats over photos (bottom bar, dock, selection bar, scrubber, messages). Where
 * a [LocalBackdrop] is provided the glass sits on a blur of it, as the design's backdrop-filter does. A [fill] replaces the
 * glass with a solid colour and no blur (the Island and Snackbar messages).
 */
@Composable
fun Modifier.floating(shape: Shape = CircleShape, fill: Color? = null): Modifier {
    val c = AlbumTheme.colors
    // Shadows vanish on black, so dark themes add a 1dp light hairline as the design's dark shadow-float does.
    val edge = if (c.dark) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape) else Modifier
    val backdrop = LocalBackdrop.current
    return this
        .shadow(if (c.dark) 14.dp else 10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = if (c.dark) 0.7f else 0.18f))
        .then(edge)
        .then(if (backdrop != null && fill == null) Modifier.frosted(backdrop, shape) else Modifier)
        .background(fill ?: c.glass, shape)
}

/**
 * The design's `backdrop-filter: blur(24px) saturate(1.4)`: [backdrop], where this element sits over it, blurred and a
 * little more saturated, inside [shape]. The recording is a reference to the backdrop's display list, so scrolling under
 * the bar updates the blur without redrawing the bar.
 */
@Composable
private fun Modifier.frosted(backdrop: GraphicsLayer, shape: Shape): Modifier {
    val blur = rememberGraphicsLayer()
    var at by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { at = it.positionInRoot() }.drawWithCache {
        val r = 24.dp.toPx()
        val pad = r.toInt() // recorded a blur radius wider on every side, so the edges blur real content, not a clamp
        blur.renderEffect = android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP),
            android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.4f) })),
        ).asComposeRenderEffect()
        blur.topLeft = IntOffset(-pad, -pad)
        val clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            val origin = at // read while drawing: moving the bar redraws it
            blur.record(IntSize(size.width.toInt() + 2 * pad, size.height.toInt() + 2 * pad)) {
                translate(pad - origin.x, pad - origin.y) { drawLayer(backdrop) }
            }
            clipPath(clip) { drawLayer(blur) }
        }
    }
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
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
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
    var hint by remember { mutableStateOf(false) } // let go too early: says how, for 1.5 s
    LaunchedEffect(hint) {
        if (hint) {
            delay(1500)
            hint = false
        }
    }
    val label = if (hint) "Press and hold" else text
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
                    hint = false
                    haptics.clockTick()
                    val run = scope.launch {
                        var ticks = 1
                        progress.animateTo(1f, tween(((1f - progress.value) * 800).toInt(), easing = LinearEasing)) {
                            if (value >= ticks / 3f && ticks < 3) {
                                ticks++
                                haptics.clockTick()
                            }
                        }
                        haptics.confirm()
                        onConfirm()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        run.cancel()
                        hint = true
                        haptics.reject()
                        scope.launch { progress.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
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
            Text(label, style = AlbumTheme.type.label, color = color, maxLines = 1)
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

/**
 * A 48dp round button with one icon. [label] is required: TalkBack reads it. [active] fills the icon (toggles), which
 * pops as it turns on, as the design's heart does.
 */
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
    val still = AlbumTheme.spec.still
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !was && !still) {
            // heart-pop: 0.6 to 1.25 at half time, then to rest, each half on the design's springy curve
            pop.snapTo(0.6f)
            pop.animateTo(1.25f, tween(220, easing = Springy))
            pop.animateTo(1f, tween(220, easing = Springy))
        }
        was = active
    }
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
    ) { Icon(sym, null, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, filled = active, tint = tint ?: fg) }
}

/** cubic-bezier(.2, .9, .3, 1.6): the design's pops (heart, tick, nb-pop) overshoot on this. */
private val Springy = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.6f)

/** The design's `ease`, for fades and colour changes. */
internal val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Anything leaving: quick to get out of the way. */
internal val ExitEase = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

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
    val still = AlbumTheme.spec.still
    val shape = CircleShape
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The fill eases in, the start padding makes room for the check with a slight overshoot, and the check tick-pops
    val fill by animateColorAsState(if (selected) c.flashSoft else Color.Transparent, if (still) snap() else tween(180), label = "chipFill")
    val start by animateDpAsState(
        if (selected) Space.s3 else Space.s4, if (still) snap() else tween(220, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)), label = "chipStart",
    )
    val tick = remember { Animatable(1f) }
    var was by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !was && !still) {
            tick.snapTo(0f)
            tick.animateTo(1f, tween(360, easing = Springy))
        }
        was = selected
    }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .pressScale(pressed, 0.95f)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, if (selected) c.flashSoft else c.lineStrong, shape)
            .then(
                if (toggle) Modifier.toggleable(selected, source, null, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.clickable(source, null, role = Role.Button, onClick = onClick),
            )
            .padding(start = start, end = Space.s4, top = Space.s2, bottom = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            selected -> Icon(
                Sym.Check, null,
                Modifier.graphicsLayer { scaleX = tick.value; scaleY = tick.value; rotationZ = -40f * (1f - tick.value) },
                size = Space.iconSm, tint = c.flashInk,
            )
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
    val pop by animateFloatAsState(
        if (checked && !still) 1.08f else 1f, if (still) snap() else tween(220, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.5f)), label = "check",
    )
    val (fill, ring) = when {
        checked -> c.flash to if (onSurface) c.flash else c.onFlash
        onSurface -> Color.Transparent to c.lineStrong
        else -> c.scrimPhoto to c.onPhoto
    }
    val bg by animateColorAsState(fill, if (still) snap() else tween(160), label = "checkFill")
    Box(
        modifier.size(size).scale(pop).background(bg, CircleShape).border(if (size < 20.dp) 1.5.dp else 2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Sym.Check, null, size = size * 5 / 6, tint = c.onFlash) }
}

// ---------- Switch ----------

/**
 * An on/off switch: the thumb grows and shows a check when on, so the state never rests on colour alone. While its row
 * is [pressed] the thumb squashes wider, as the design's does.
 */
@Composable
fun AlbumSwitch(checked: Boolean, modifier: Modifier = Modifier, pressed: Boolean = false) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val spec = if (still) snap() else tween<Dp>(280, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.25f))
    val x by animateDpAsState(if (checked) 22.dp else 6.dp, spec, label = "thumb")
    val d by animateDpAsState(if (checked) 24.dp else 16.dp, spec, label = "thumbSize")
    val colour = if (still) snap<Color>() else tween(200)
    val track by animateColorAsState(if (checked) c.flash else c.surfaceHigh, colour, label = "track")
    val border by animateColorAsState(if (checked) c.flash else c.lineStrong, colour, label = "trackBorder")
    Box(
        modifier.size(52.dp, 32.dp).background(track, CircleShape).border(2.dp, border, CircleShape),
    ) {
        // The design measures the thumb inside the 2dp border
        Box(
            Modifier.offset(x = x + 2.dp).align(Alignment.CenterStart).size(d)
                .graphicsLayer { scaleX = if (pressed && !still) 1.25f else 1f }
                .background(if (checked) c.onFlash else c.inkMuted, CircleShape),
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
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().toggleable(checked, source, null, role = Role.Switch, onValueChange = onChange).padding(vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = c.inkMuted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = AlbumTheme.type.body, color = c.ink)
            description?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
        AlbumSwitch(checked, pressed = pressed)
    }
}

// ---------- Groups (Settings, For you, Clean up, Collections) ----------

/**
 * Settings pages provide true here when the Design lab's Settings style is Plain: groups lose their card, icons their
 * disc, and rows keep 4dp at the sides. Groups elsewhere (For you, Clean up, Collections) stay on cards.
 */
val LocalPlainGroups = staticCompositionLocalOf { false }

/** A group row's padding at the sides: 16dp on a card, 4dp in a Plain group. */
internal val GroupSide: Dp @Composable @ReadOnlyComposable get() = if (LocalPlainGroups.current) Space.s1 else Space.s4

/**
 * Rows on one surface card with hairlines between them, an optional title above and a note below: how Settings, For you,
 * Clean up and Collections list things. Put [GroupRow], [GroupSwitch] and [ChipRow] inside, or anything padded like them.
 * Inside [LocalPlainGroups] there's no card, only the hairlines between rows.
 */
@Composable
fun Group(modifier: Modifier = Modifier, title: String? = null, note: String? = null, content: @Composable () -> Unit) {
    val c = AlbumTheme.colors
    val shape = RoundedCornerShape(Space.radiusLg)
    // Where the hairlines go: found while placing the rows, drawn over them
    var cuts by remember { mutableStateOf(emptyList<Int>()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        title?.let { Text(it, Modifier.padding(horizontal = Space.s1).semantics { heading() }, style = AlbumTheme.type.label, color = c.inkMuted) }
        Layout(
            content,
            Modifier.fillMaxWidth().then(if (LocalPlainGroups.current) Modifier else Modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape))
                .drawWithContent {
                    drawContent()
                    cuts.forEach { drawRect(c.line, Offset(0f, it.toFloat()), Size(size.width, 1.dp.toPx())) }
                },
        ) { rows, constraints ->
            val placed = rows.map { it.measure(constraints.copy(minHeight = 0)) }
            layout(constraints.maxWidth, placed.sumOf { it.height }) {
                var y = 0
                val at = ArrayList<Int>()
                placed.forEach { p ->
                    if (y > 0 && p.height > 0) at += y
                    p.place(0, y)
                    y += p.height
                }
                cuts = at
            }
        }
        note?.let { Text(it, Modifier.padding(horizontal = Space.s1), style = AlbumTheme.type.caption, color = c.inkMuted) }
    }
}

/**
 * The icon at the start of a group row: on a surface-high disc (danger-soft for a danger row). In a Plain group, a muted
 * icon in a 28dp column with no disc.
 */
@Composable
fun RowIcon(sym: Sym, danger: Boolean = false) {
    val c = AlbumTheme.colors
    if (LocalPlainGroups.current) {
        return Box(Modifier.size(28.dp, 36.dp), contentAlignment = Alignment.Center) { Icon(sym, null, tint = if (danger) c.danger else c.inkMuted) }
    }
    Box(Modifier.size(36.dp).background(if (danger) c.dangerSoft else c.surfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
        Icon(sym, null, tint = if (danger) c.danger else c.ink)
    }
}

private val RowLabel @Composable get() = AlbumTheme.type.body.copy(fontWeight = FontWeight.Medium)

/**
 * A row in a [Group]: an icon on a disc (or [leading], such as [Thumbs] or an app's icon), a label and a second line, a
 * value in mono ([valueColor]), and a chevron that nudges right while pressed. Null [onClick] makes it a plain line of
 * information.
 */
@Composable
fun GroupRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: Sym? = null,
    sub: String? = null,
    value: String? = null,
    trailing: Sym? = if (onClick != null) Sym.ChevronRight else null,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    valueColor: Color = AlbumTheme.colors.inkMuted,
) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val nudge by animateDpAsState(
        if (pressed && !still) 3.dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "chevron",
    )
    // Pressed, the row fills surface-high (no ripple), as the design's rows do
    val fill by animateColorAsState(if (pressed) c.surfaceHigh else Color.Transparent, if (still) snap() else tween(150), label = "rowFill")
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.background(fill).clickable(source, null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = GroupSide, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> RowIcon(icon, danger)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = RowLabel, color = if (danger) c.danger else c.ink)
            sub?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
        value?.let { Text(it, style = AlbumTheme.type.meta, color = valueColor, maxLines = 1) }
        trailing?.let { Icon(it, null, Modifier.offset(x = nudge), tint = c.inkMuted) }
    }
}

/** A switch row in a [Group]: icon disc, label, one line on what changes, the switch. The whole row toggles. */
@Composable
fun GroupSwitch(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: Sym? = null,
) {
    val c = AlbumTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(checked, source, null, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = GroupSide, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A Plain group's switch rows show the bare muted icon, as the design's switch row does off a card
        icon?.let { if (LocalPlainGroups.current) Icon(it, null, tint = c.inkMuted) else RowIcon(it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = AlbumTheme.type.body, color = c.ink)
            description?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
        AlbumSwitch(checked, pressed = pressed)
    }
}

/** A choice in a [Group]: the label on its own line, then one chip per option, wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val c = AlbumTheme.colors
    Column(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = GroupSide, vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = RowLabel, color = c.ink)
            sub?.let { Text(it, style = AlbumTheme.type.caption, color = c.inkMuted) }
        }
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) { options.forEach { (v, name) -> Chip(name, onClick = { onChange(v) }, selected = v == value) } }
    }
}

/** Up to three overlapping thumbnails, for a row that stands for a set of photos; [ring] is the ground they sit on. */
@Composable
fun Thumbs(
    items: List<Media>,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    ring: Color = if (LocalPlainGroups.current) AlbumTheme.colors.canvas else AlbumTheme.colors.surface,
) {
    val shown = items.take(3)
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    // 32dp each with a 2dp ring, overlapping by 12dp
    Box(modifier.padding(start = Space.s1).width(36.dp + 20.dp * (shown.size - 1).coerceAtLeast(0)).height(36.dp)) {
        shown.forEachIndexed { i, m ->
            Box(Modifier.offset(x = 20.dp * i).size(36.dp).background(ring, shape).padding(2.dp).clip(shape)) { Thumbnail(m, Modifier.fillMaxSize()) }
        }
    }
}

// ---------- Rows and headings ----------

/** A section heading with an optional link on the right ("See all"). */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s6, bottom = Space.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Title and link share a baseline, as the design's heading does
        Text(title, Modifier.alignByBaseline(), style = AlbumTheme.type.titleMd, color = AlbumTheme.colors.ink)
        if (action != null && onAction != null) {
            Text(
                action,
                Modifier.alignByBaseline().clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = Space.s2, vertical = Space.s1),
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
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(Space.radiusMd)
    Row(
        // Pressed, the row fills surface-high at once (no ripple)
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (pressed) c.surfaceHigh else Color.Transparent, shape)
            .clickable(source, null, role = Role.Button, onClick = onClick).padding(horizontal = Space.s1),
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
            onBack?.let { AlbumIconButton(Sym.ArrowBack, "Back", it) } // at the 16dp margin, where the collapsed bar has it
            Spacer(Modifier.weight(1f))
            Row(Modifier.offset(x = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s1), content = actions)
        }
        Text(title, Modifier.padding(top = Space.s6), style = AlbumTheme.type.titleXl, color = c.ink)
        subtitle?.let { Text(it, Modifier.padding(top = Space.s1), style = AlbumTheme.type.meta, color = c.inkMuted) }
    }
}

/** [CollapsedTopBar]'s height under the status bar: 8dp, the 48dp row, 12dp and the hairline. Content under it starts here. */
val CollapsedBarHeight = Space.s2 + Space.target + Space.s3 + 1.dp

/**
 * The one-row title bar: back, title in title-md with an optional meta subtitle, up to two actions. [CollapsedBarHeight]
 * tall: back centred 40dp in, the title 72dp in, the last action centred 28dp from the edge.
 */
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
            Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3).heightIn(min = Space.target),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { AlbumIconButton(Sym.ArrowBack, "Back", it) }
            Column(Modifier.weight(1f)) {
                Text(title, style = AlbumTheme.type.titleMd, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = AlbumTheme.type.meta, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            // Into the margin, as on the large bar: the icon's own padding is the space
            Row(Modifier.offset(x = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s1), content = actions)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** How far a screen scrolls before its [CollapsedTopBar] shows: once the large title is going. */
val CollapseAfter = 70.dp

/** Whether [state] has scrolled past [CollapseAfter]. Derived, so scrolling recomposes only when the answer changes. */
@Composable
fun rememberCollapsed(state: LazyGridState): State<Boolean> {
    val past = with(LocalDensity.current) { CollapseAfter.roundToPx() }
    return remember(state, past) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > past } }
}

/** [rememberCollapsed] for a list. */
@Composable
fun rememberCollapsed(state: LazyListState): State<Boolean> {
    val past = with(LocalDensity.current) { CollapseAfter.roundToPx() }
    return remember(state, past) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > past } }
}

/** Shows [CollapsedTopBar]'s content over a screen once [visible], fading in 180ms and sliding 8dp in 220ms both ways. */
@Composable
fun CollapsingBar(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val still = AlbumTheme.spec.still
    val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(
        visible,
        modifier,
        enter = if (still) EnterTransition.None else fadeIn(tween(180, easing = Ease)) + slideInVertically(tween(220, easing = Ease)) { -rise },
        exit = if (still) ExitTransition.None else fadeOut(tween(180, easing = Ease)) + slideOutVertically(tween(220, easing = Ease)) { -rise },
    ) { content() }
}

// ---------- Sheets ----------

private val SheetEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

/**
 * A bottom sheet on surface with radius-lg corners and a drag handle. Menus open here, never as drop-downs from the top.
 * It rises from 110% of its height in 320ms over a scrim fading in 200ms; the scrim, Back or a drag down (56dp, or a
 * flick) closes it, sliding down in 220ms. Its content takes at most 82% of the screen, so tall content scrolls inside.
 * A caller that removes it (a row picked) makes it vanish at once, as in the design. Remove animations: no motion.
 */
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val shown = remember { Animatable(if (still) 1f else 0f) } // 0: 110% of its height below, 1: in place
    val scrim = remember { Animatable(if (still) 1f else 0f) }
    var drag by remember { mutableFloatStateOf(0f) } // px pulled down
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch { scrim.animateTo(1f, tween(200, easing = Ease)) }
        shown.animateTo(1f, tween(320, easing = SheetEase))
    }
    fun close() {
        if (leaving) return
        leaving = true
        scope.launch {
            if (!still) {
                launch { scrim.animateTo(0f, tween(220, easing = Ease)) }
                shown.animateTo(0f, tween(220, easing = ExitEase))
            }
            dismiss()
        }
    }
    val threshold = with(density) { 56.dp.toPx() }
    val flick = with(density) { 125.dp.toPx() }
    // Let go: past 56dp or flicked down it closes, otherwise it springs back
    fun settle(velocity: Float) {
        if (drag > threshold || velocity > flick) close()
        else scope.launch { animate(drag, 0f, velocity, if (still) snap() else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> drag = v } }
    }
    // Content that scrolls hands the sheet the pull once it's at its top, and takes it back first when pushed up again
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || drag <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val used = maxOf(available.y, -drag)
                drag += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                drag += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    Dialog(
        onDismissRequest = ::close, // Back
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Album draws its own scrim and motion; the window adds neither
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
            SideEffect {
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cap = maxHeight * 0.82f
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = scrim.value }.background(c.scrim)
                    .pointerInput(Unit) { detectTapGestures { close() } }
                    .semantics(mergeDescendants = true) {
                        traversalIndex = 1f // after the sheet's own content
                        contentDescription = "Close sheet"
                        onClick { close(); true }
                    },
            )
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) // a tall sheet stops under the status bar
                    .widthIn(max = 640.dp).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - shown.value) * 1.1f * size.height + drag }
                    .background(c.surface, RoundedCornerShape(topStart = Space.radiusLg, topEnd = Space.radiusLg))
                    .nestedScroll(pull)
                    .draggable(
                        rememberDraggableState { d -> drag = (drag + d).coerceAtLeast(0f) },
                        Orientation.Vertical,
                        enabled = !leaving,
                        onDragStopped = { v -> settle(v) },
                    )
                    .semantics { paneTitle = title ?: "Bottom sheet"; isTraversalGroup = true }
                    .padding(bottom = Space.s8 + Space.s2)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) {
                CompositionLocalProvider(LocalContentColor provides c.ink) {
                    Column(Modifier.heightIn(max = cap).padding(horizontal = Space.s4)) {
                        Box(
                            Modifier.align(Alignment.CenterHorizontally).padding(top = Space.s2, bottom = Space.s4)
                                .size(32.dp, 4.dp).background(c.lineStrong, CircleShape),
                        )
                        title?.let { Text(it, Modifier.padding(bottom = Space.s3), style = AlbumTheme.type.titleLg, color = c.ink) }
                        content()
                    }
                }
            }
        }
    }
}

enum class ConfirmTone { Danger, Flash }

/** How many confirmations are open: the selection bar fades its count row away under one. */
internal object ConfirmShown {
    var count by mutableIntStateOf(0)
}

/**
 * A decision card: the selection bar grown taller. It sits where the bottom bar sits (16dp sides, the bar's radius, the
 * navigation bar plus 16dp below), its head (a photo or two, [face], or [icon] on a disc; the title and body) above, and
 * its two buttons exactly where the bar's actions were, so the thumb stays put. [fromBar] grows it out of a selection
 * bar; otherwise it rises a little. Danger is danger-soft (Trash can undo it), Flash a neutral change, [hold] for what
 * nothing can undo. Cancel by tapping the scrim, Back, or dragging the card down. A confirmed action runs once the card
 * has folded away, and the confirm button ignores taps for its first 300 ms so a double tap on the bar can't go through.
 */
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
    fromBar: Boolean = false,
    face: FaceCrop? = null,
) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val grow = remember { Animatable(if (fromBar && !still) 0f else 1f) } // bar height to full height
    val rise = remember { Animatable(if (!fromBar && !still) 0f else 1f) } // 32dp below and clear to in place
    val head = remember { Animatable(if (fromBar && !still) 0f else 1f) }
    val buttons = remember { Animatable(if (fromBar && !still) 0f else 1f) }
    val fade = remember { Animatable(if (still) 0f else 1f) } // Remove animations: the card only fades
    val scrim = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var cardHeight by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val enter = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
    DisposableEffect(Unit) {
        ConfirmShown.count++
        onDispose { ConfirmShown.count-- }
    }
    LaunchedEffect(Unit) {
        launch { delay(300); armed = true }
        launch { scrim.animateTo(1f, if (still) tween(150, easing = LinearEasing) else tween(200, easing = Ease)) }
        if (still) fade.animateTo(1f, tween(150))
        else if (fromBar) {
            launch { grow.animateTo(1f, enter) }
            launch { head.animateTo(1f, tween(280, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f))) }
            buttons.animateTo(1f, tween(120, easing = LinearEasing))
        } else rise.animateTo(1f, enter)
    }
    // Runs the way back, then [then]: a cancel, or the confirmed action
    fun leave(swiped: Boolean = false, then: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            val out = tween<Float>(200, easing = FastOutSlowInEasing)
            launch { scrim.animateTo(0f, tween(180, easing = LinearEasing)) }
            when {
                still -> fade.animateTo(0f, tween(120))
                swiped -> {
                    launch { fade.animateTo(0f, out) }
                    drag.animateTo(cardHeight * 1.2f, out)
                }
                fromBar -> {
                    launch { head.animateTo(0f, tween(100)) }
                    grow.animateTo(0f, out)
                }
                else -> {
                    launch { fade.animateTo(0f, out) }
                    rise.animateTo(0.25f, out) // 24dp of the 32
                }
            }
            then()
        }
    }
    val cancel = { leave(then = onCancel) }
    val confirm = {
        if (armed) {
            haptics.confirm()
            leave(then = onConfirm)
        }
    }

    Dialog(
        onDismissRequest = cancel, // Back
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        // Album draws its own scrim and motion; the window adds neither
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
            SideEffect {
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = scrim.value }.background(c.scrim.copy(alpha = c.scrim.alpha * 0.66f))
                    .pointerInput(Unit) { detectTapGestures { cancel() } },
            )
            val shape = RoundedCornerShape(Space.bar / 2)
            val bar = with(density) { Space.bar.roundToPx() }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = Space.s4, end = Space.s4, bottom = Space.s4)
                    .widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer {
                        alpha = fade.value * if (!fromBar && !still) rise.value.coerceIn(0f, 1f) else 1f
                        translationY = drag.value + if (!fromBar && !still) (1f - rise.value) * 32.dp.toPx() else 0f
                    }
                    .onGloballyPositioned { cardHeight = it.size.height }
                    .floating(shape).background(c.surface, shape).clip(shape)
                    .layout { m, cs ->
                        val p = m.measure(cs.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val h = (bar + (p.height - bar) * grow.value).toInt().coerceAtLeast(0)
                        layout(p.width, h) { p.place(0, h - p.height) } // bottom-anchored: the buttons never move
                    }
                    .semantics { paneTitle = title },
            ) {
                ConfirmHead(
                    title, body, thumbs, face, icon, tone,
                    Modifier.graphicsLayer {
                        alpha = ((head.value - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        translationY = (1f - head.value) * 8.dp.toPx()
                    }.pointerInput(Unit) {
                        // Drag the head down to cancel; upward it barely moves
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag.value > 56.dp.toPx()) leave(swiped = true, then = onCancel)
                                else scope.launch { drag.animateTo(0f, enter) }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f, enter) } },
                        ) { _, dy -> scope.launch { drag.snapTo(drag.value + if (drag.value + dy > 0) dy else dy / 6f) } }
                    },
                )
                ConfirmButtons(confirmLabel, cancelLabel, icon, tone, hold, cancel, confirm, Modifier.graphicsLayer { alpha = buttons.value })
            }
        }
    }
}

/** The card's head: a photo or two, a face or an icon on a disc, then the title and body, balanced so no word dangles. */
@Composable
private fun ConfirmHead(title: String, body: String?, thumbs: List<Media>, face: FaceCrop?, icon: Sym, tone: ConfirmTone, modifier: Modifier) {
    val c = AlbumTheme.colors
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.size(44.dp)) {
            when {
                face != null -> FaceImage(face, Modifier.size(44.dp).clip(CircleShape))
                thumbs.size == 1 -> Thumbnail(thumbs[0], Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                thumbs.size > 1 -> {
                    val r = RoundedCornerShape(10.dp)
                    Thumbnail(thumbs[1], Modifier.align(Alignment.TopEnd).size(38.dp).clip(r))
                    Box(Modifier.align(Alignment.BottomStart).size(42.dp).offset((-2).dp, 2.dp).background(c.surface, RoundedCornerShape(12.dp)).padding(2.dp)) {
                        Thumbnail(thumbs[0], Modifier.size(38.dp).clip(r))
                    }
                }
                else -> Box(
                    Modifier.size(44.dp).background(if (tone == ConfirmTone.Flash) c.flashSoft else c.dangerSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = if (tone == ConfirmTone.Flash) c.flashInk else c.danger) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AlbumTheme.type.titleMd.copy(lineBreak = LineBreak.Heading), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            body?.let { Text(it, style = AlbumTheme.type.body.copy(lineBreak = LineBreak.Heading), color = c.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The card's buttons, in the bar's own action insets: Cancel and the confirm, equal halves, or stacked when text is large. */
@Composable
private fun ConfirmButtons(
    confirmLabel: String,
    cancelLabel: String,
    icon: Sym,
    tone: ConfirmTone,
    hold: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier,
) {
    val c = AlbumTheme.colors
    val stacked = LocalDensity.current.fontScale >= 1.3f
    @Composable
    fun Pill(label: String, m: Modifier, bg: Color, fg: Color, sym: Sym?, onClick: () -> Unit) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        // As every button: pressed, it squares toward radius-md
        val corner by animateDpAsState(if (pressed && !AlbumTheme.spec.still) Space.radiusMd else 100.dp, tween(220), label = "corner")
        val shape = RoundedCornerShape(corner)
        Row(
            m.fillMaxHeight().pressScale(pressed, 0.96f).clip(shape)
                .background(if (pressed && bg == Color.Transparent) c.surfaceHigh else bg, shape)
                .clickable(source, null, role = Role.Button, onClick = onClick).padding(horizontal = Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sym?.let { Icon(it, null, size = Space.iconSm, tint = fg) }
            Text(label, style = AlbumTheme.type.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable
    fun Confirm(m: Modifier) = when {
        hold -> HoldButton(confirmLabel, onConfirm, m.fillMaxHeight(), icon)
        tone == ConfirmTone.Flash -> Pill(confirmLabel, m, c.flash, c.onFlash, icon, onConfirm)
        else -> Pill(confirmLabel, m, c.dangerSoft, c.danger, icon, onConfirm)
    }
    if (stacked) {
        Column(modifier.fillMaxWidth().padding(start = Space.s2, end = Space.s2, bottom = Space.s1), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Confirm(Modifier.fillMaxWidth().height(56.dp))
            Pill(cancelLabel, Modifier.fillMaxWidth().height(56.dp), Color.Transparent, c.ink, null, onCancel)
        }
    } else {
        Row(modifier.fillMaxWidth().height(56.dp + Space.s1).padding(start = Space.s2, end = Space.s2, bottom = Space.s1), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Pill(cancelLabel, Modifier.weight(1f), Color.Transparent, c.ink, null, onCancel)
            Confirm(Modifier.weight(1f))
        }
    }
}

// ---------- Snackbar ----------

/**
 * Where messages show: the current one of [state] in the Design lab's message style, for as long as Settings says
 * (Motion and feel, 0.3 to 10 s; longer when TalkBack or the phone's accessibility timeout asks for it), whatever
 * duration the caller passed, except Indefinite, which stays until dismissed. Chip ([AlbumSnackbar]) and Snackbar rise
 * where [modifier] places the host, above the bottom bar; Island drops from the top of the screen whatever the host's
 * place, 8dp under the status bar, or 60dp under it [overViewer] so the viewer's top bar stays clear.
 */
@Composable
fun AlbumSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier, overViewer: Boolean = false) {
    val data = state.currentSnackbarData
    val seconds = AlbumTheme.spec.appearance.messageSeconds
    val still = AlbumTheme.spec.still
    val a11y = LocalAccessibilityManager.current
    val shown = data?.let {
        val ms = (seconds * 1000).toLong()
        if (it.visuals.duration == SnackbarDuration.Indefinite) null
        else a11y?.calculateRecommendedTimeoutMillis(ms, containsIcons = true, containsText = true, containsControls = it.visuals.actionLabel != null) ?: ms
    }
    LaunchedEffect(data, shown) {
        if (data != null && shown != null) {
            delay(shown)
            data.dismiss()
        }
    }
    val density = LocalDensity.current
    val rise = with(density) { 28.dp.roundToPx() }
    val style = AlbumTheme.spec.appearance.messageStyle
    val island = style == MessageStyle.Island
    val islandTop = WindowInsets.statusBars.getTop(density) + with(density) { (if (overViewer) 60.dp else 8.dp).roundToPx() }
    AnimatedContent(
        data,
        if (!island) modifier else modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            layout(p.width, p.height) {
                // Callers place the host at the bottom; the island moves up to the top of the screen from wherever that is
                p.place(0, islandTop - (coordinates?.positionInRoot()?.y ?: 0f).roundToInt())
            }
        },
        transitionSpec = {
            // The design's rise (28dp, 280ms, a slight overshoot); the chip itself settles in inside AlbumSnackbar. The
            // island grows in place instead, inside IslandMessage, and its shadow isn't clipped while it does.
            when {
                still -> EnterTransition.None togetherWith ExitTransition.None
                island -> (EnterTransition.None togetherWith fadeOut(tween(150))) using SizeTransform(clip = false)
                else -> (slideInVertically(tween(280, easing = RiseEase)) { rise } + fadeIn(tween(280))) togetherWith fadeOut(tween(150))
            }
        },
        contentAlignment = Alignment.BottomCenter,
        label = "message",
    ) { d ->
        val left = if (d === data) shown else null
        when {
            d == null -> Box(Modifier.fillMaxWidth())
            style == MessageStyle.Island -> IslandMessage(d, left)
            style == MessageStyle.Snackbar -> SnackbarMessage(d, left)
            else -> AlbumSnackbar(d, left)
        }
    }
}

/** island-in's cubic-bezier(.2, .9, .3, 1.2): the island grows out of the top with a small overshoot. */
private val IslandEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)

/** CSS `ease`, for the island's contents fading in late. */
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * The Island message: a dark ink pill under the status bar with the icon bare, the message in canvas and the action on a
 * flash pill. It grows in from a quarter of its width (440ms, a small overshoot) while its contents fade in from half
 * way, and the icon tick-pops. A line counts down the Undo; swipe it up to dismiss it. Shown by [AlbumSnackbarHost].
 */
@Composable
private fun IslandMessage(data: SnackbarData, shownMs: Long?) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val message = data.visuals.message
    val action = data.visuals.actionLabel
    val gone = with(LocalDensity.current) { 60.dp.toPx() }
    val off = remember(data) { Animatable(0f) }
    val left = remember(data) { Animatable(1f) }
    val grow = remember(data) { Animatable(if (still) 1f else 0f) } // island-in's time, 0 to 1 over 440ms
    val pop = remember(data) { Animatable(if (still) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(data) {
        if (!still) {
            launch { grow.animateTo(1f, tween(440, easing = LinearEasing)) }
            launch {
                delay(100)
                pop.animateTo(1f, tween(440, easing = Springy))
            }
        }
        if (action != null && shownMs != null) left.animateTo(0f, tween(shownMs.toInt(), easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.s3), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .graphicsLayer {
                    val k = IslandEase.transform(grow.value)
                    translationY = off.value
                    scaleX = 0.25f + 0.75f * k
                    scaleY = 0.7f + 0.3f * k
                    alpha = (grow.value / 0.4f).coerceAtMost(1f) * (1f - abs(off.value) / (gone * 3.3f)).coerceIn(0.2f, 1f)
                }
                .draggable(
                    rememberDraggableState { d -> scope.launch { off.snapTo((off.value + d).coerceAtMost(0f)) } }, // up only
                    Orientation.Vertical,
                    onDragStopped = { if (abs(off.value) > gone) data.dismiss() else off.animateTo(0f) },
                )
                .heightIn(min = 44.dp)
                .floating(CircleShape, fill = c.ink)
                .drawWithContent {
                    drawContent()
                    if (action != null && shownMs != null) {
                        val inset = 18.dp.toPx()
                        drawRect(c.canvas.copy(alpha = 0.35f), Offset(inset, size.height - 4.dp.toPx()), Size((size.width - 2 * inset) * left.value, 2.dp.toPx()))
                    }
                }
                .semantics { liveRegion = LiveRegionMode.Polite }
                // fade-late: nothing shows inside until 45% of 420ms, then it eases in
                .graphicsLayer { alpha = CssEase.transform(((grow.value * 440f / 420f - 0.45f) / 0.55f).coerceIn(0f, 1f)) }
                .padding(start = Space.s3, top = Space.s1, end = Space.s1, bottom = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp, 32.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = -40f * (1f - pop.value) },
                contentAlignment = Alignment.Center,
            ) { Icon((data.visuals as? IconMessage)?.icon ?: messageIcon(message), null, filled = true, size = Space.iconSm, tint = c.canvas) }
            Text(
                message, Modifier.weight(1f, fill = false).padding(start = Space.s1, end = Space.s2),
                style = AlbumTheme.type.label, color = c.canvas, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            action?.let {
                Box(
                    Modifier.height(36.dp).clip(CircleShape).background(c.flash).clickable(role = Role.Button) { data.performAction() }
                        .padding(horizontal = Space.s4),
                    contentAlignment = Alignment.Center,
                ) { Text(it, style = AlbumTheme.type.label, color = c.onFlash) }
            }
        }
    }
}

/**
 * The Snackbar message, the design system's snackbar: a full-width surface-high pill with an optional round thumb, the
 * message, and the action in flash-ink (Undo with its icon). A flash-ink line under it counts down the Undo; swipe it
 * sideways to dismiss it. Shown by [AlbumSnackbarHost].
 */
@Composable
private fun SnackbarMessage(data: SnackbarData, shownMs: Long?) {
    val c = AlbumTheme.colors
    val action = data.visuals.actionLabel
    val gone = with(LocalDensity.current) { 60.dp.toPx() }
    val off = remember(data) { Animatable(0f) }
    val left = remember(data) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(data) {
        if (action != null && shownMs != null) left.animateTo(0f, tween(shownMs.toInt(), easing = LinearEasing))
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = Space.s3)
            .graphicsLayer {
                translationX = off.value
                alpha = (1f - abs(off.value) / (gone * 3.3f)).coerceIn(0.2f, 1f)
            }
            .draggable(
                rememberDraggableState { d -> scope.launch { off.snapTo(off.value + d) } },
                Orientation.Horizontal,
                onDragStopped = { if (abs(off.value) > gone) data.dismiss() else off.animateTo(0f) },
            )
            .drawWithContent {
                drawContent()
                if (action != null && shownMs != null) {
                    val inset = 18.dp.toPx()
                    drawRect(c.flashInk.copy(alpha = 0.8f), Offset(inset, size.height - 5.dp.toPx()), Size((size.width - 2 * inset) * left.value, 2.dp.toPx()))
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.target).floating(CircleShape, fill = c.surfaceHigh)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = Space.s2, top = Space.s1, end = Space.s1, bottom = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (data.visuals as? IconMessage)?.thumb?.let { Thumbnail(it, Modifier.size(36.dp).clip(CircleShape)) }
            Text(
                data.visuals.message, Modifier.weight(1f).padding(start = Space.s1),
                style = AlbumTheme.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            action?.let {
                Row(
                    Modifier.height(40.dp).clip(CircleShape).clickable(role = Role.Button) { data.performAction() }.padding(horizontal = Space.s4),
                    horizontalArrangement = Arrangement.spacedBy(Space.s1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (it == "Undo") Icon(Sym.Undo, null, size = Space.iconSm, tint = c.flashInk)
                    Text(it, style = AlbumTheme.type.label, color = c.flashInk)
                }
            }
        }
    }
}

/** cubic-bezier(.2, .9, .3, 1.1): docked chrome and messages rising into place with a slight overshoot. */
internal val RiseEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.1f)

/**
 * How docked chrome comes in: a selection bar, a page's dock button ("Select all suggested"). Up 28dp with a slight
 * overshoot, fading in, in 300ms; it leaves at once ([ExitTransition.None]). Nothing moves with Remove animations.
 */
@Composable
fun dockEnter(): EnterTransition {
    val rise = with(LocalDensity.current) { 28.dp.roundToPx() }
    return if (AlbumTheme.spec.still) EnterTransition.None
    else slideInVertically(tween(300, easing = RiseEase)) { rise } + fadeIn(tween(300, easing = SheetEase))
}

/**
 * A message that names its own icon, for when the wording alone would pick the wrong one ("PIN changed" shows a lock).
 * The Snackbar style shows [thumb] at its start.
 */
class IconMessage(
    override val message: String,
    val icon: Sym,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    val thumb: Media? = null,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
}

/** Shows [message] with [icon] instead of the one its wording picks (see [messageIcon]). */
suspend fun SnackbarHostState.showSnackbar(message: String, icon: Sym, actionLabel: String? = null): SnackbarResult =
    showSnackbar(IconMessage(message, icon, actionLabel))

/**
 * Messages from code with no [SnackbarHostState] at hand (a request over the limit, no app for an intent): shown as the
 * chip by the open Album screen, or as a toast where none shows messages (the external viewer).
 */
object Messages {
    private val flow = MutableSharedFlow<SnackbarVisuals>(extraBufferCapacity = 8)

    /** What to show; collected by the main screen while it's started. */
    val posts = flow.asSharedFlow()

    /** Shows [text], with [icon] or else the one its wording picks. */
    fun post(context: Context, text: String, icon: Sym? = null) {
        val message = IconMessage(text, icon ?: messageIcon(text))
        if (flow.subscriptionCount.value == 0 || !flow.tryEmit(message)) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Every message after an action: a glass chip with the message's icon on a flash disc, the message, and one way back
 * ("Undo" in flash-ink). A line under it counts down the [shownMs] it has left. Swipe it sideways to dismiss it.
 * Messages are past tense with the count: "Moved 12 items to Trash". Shown by [AlbumSnackbarHost].
 */
@Composable
fun AlbumSnackbar(data: SnackbarData, shownMs: Long?, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val message = data.visuals.message
    val action = data.visuals.actionLabel
    val gone = with(LocalDensity.current) { 60.dp.toPx() }
    val off = remember(data) { Animatable(0f) }
    val left = remember(data) { Animatable(1f) }
    val pop = remember(data) { Animatable(if (still) 1f else 0f) }
    val settle = remember(data) { Animatable(if (still) 1f else 0f) } // chip-in: from 24dp lower at 0.9, fading in
    val scope = rememberCoroutineScope()
    LaunchedEffect(data) {
        if (!still) {
            launch { settle.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.25f))) }
            // The icon tick-pops a moment after the chip
            launch {
                delay(100)
                pop.animateTo(1f, tween(440, easing = Springy))
            }
        }
        if (action != null && shownMs != null) left.animateTo(0f, tween(shownMs.toInt(), easing = LinearEasing))
    }
    Box(modifier.fillMaxWidth().padding(horizontal = Space.s3), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .graphicsLayer {
                    val k = settle.value
                    translationX = off.value
                    translationY = (1f - k) * 24.dp.toPx()
                    scaleX = 0.9f + 0.1f * k
                    scaleY = scaleX
                    alpha = (1f - abs(off.value) / (gone * 3.3f)).coerceIn(0.2f, 1f) * k.coerceIn(0f, 1f)
                }
                .draggable(
                    rememberDraggableState { d -> scope.launch { off.snapTo(off.value + d) } },
                    Orientation.Horizontal,
                    onDragStopped = { if (abs(off.value) > gone) data.dismiss() else off.animateTo(0f) },
                )
                .heightIn(min = 44.dp)
                .floating(CircleShape)
                .drawWithContent {
                    drawContent()
                    if (action != null && shownMs != null) {
                        val inset = 18.dp.toPx()
                        drawRect(c.ink.copy(alpha = 0.35f), Offset(inset, size.height - 4.dp.toPx()), Size((size.width - 2 * inset) * left.value, 2.dp.toPx()))
                    }
                }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = -40f * (1f - pop.value) }
                    .background(c.flash, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon((data.visuals as? IconMessage)?.icon ?: messageIcon(message), null, filled = true, size = Space.iconSm, tint = c.onFlash) }
            Text(
                message, Modifier.weight(1f, fill = false).padding(start = Space.s1, end = Space.s2),
                style = AlbumTheme.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            action?.let {
                Box(
                    Modifier.height(36.dp).clip(CircleShape).clickable(role = Role.Button) { data.performAction() }.padding(horizontal = Space.s4),
                    contentAlignment = Alignment.Center,
                ) { Text(it, style = AlbumTheme.type.label, color = c.flashInk) }
            }
        }
    }
}

/** The icon a message carries, read from its wording as the design does. */
internal fun messageIcon(m: String): Sym = when {
    m.startsWith("Couldn") || m.startsWith("Can't") || m.startsWith("Can’t") || m.startsWith("No ") -> Sym.Info
    Regex("^Moved .*Trash").containsMatchIn(m) -> Sym.Delete
    m.startsWith("Hid ") -> Sym.VisibilityOff
    m.startsWith("Unhid ") -> Sym.Visibility
    m.startsWith("PIN and fingerprint") -> Sym.Fingerprint
    m.startsWith("PIN ") -> Sym.Lock
    m.startsWith("Shared") -> Sym.Share
    "Favourites" in m -> Sym.Favorite
    m.startsWith("Added") -> Sym.Add
    m.startsWith("Restored") -> Sym.RestoreFromTrash
    "forever" in m -> Sym.DeleteForever
    m.contains("safe", ignoreCase = true) -> Sym.Lock
    m.startsWith("Date") -> Sym.CalendarMonth
    else -> Sym.CheckCircle
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
 * The main navigation, in the thumb's reach, in the style chosen in Settings ([BarStyle]; Dot by default: icons in one
 * glass capsule, a dot under the current tab, search after a hairline). [mini] is the shrunk bar while the Library
 * scrolls: just the current tab, which [onExpand] opens back up, and search. [onSelect] gets the tapped item's centre in
 * window coordinates (the Reveal tab motion grows from there). The full bar and the mini one swap at once: the mini
 * capsules pop in, and the full bar's current icon pops when it comes back, as it does on [popIn] (the bar returning in
 * place of the selection bar).
 */
@Composable
fun BottomBar(
    items: List<NavItem>,
    active: Int,
    onSelect: (index: Int, center: Offset) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    style: BarStyle = AlbumTheme.spec.appearance.bar,
    mini: Boolean = false,
    onExpand: () -> Unit = {},
    popIn: Boolean = false,
) {
    if (style == BarStyle.Classic) return ClassicBar(items, active, onSelect, onSearch, modifier, mini)
    var wasMini by remember { mutableStateOf(false) }
    Box(modifier) {
        if (mini) {
            SideEffect { wasMini = true }
            MiniBar(items[active], onExpand, onSearch)
        } else {
            val pop = popIn || wasMini // a fresh full bar after the mini one: its current icon pops
            when (style) {
                BarStyle.Pill -> PillBar(items, active, onSelect, onSearch)
                BarStyle.Slider -> SliderBar(items, active, onSelect, onSearch, pop)
                else -> CapsuleBar(items, active, onSelect, onSearch, labels = style == BarStyle.Labels, popIn = pop)
            }
        }
    }
}

/** A tab in the bar: remembers its centre for the Reveal motion, presses down, and is read as a tab. */
@Composable
private fun Modifier.navTab(item: NavItem, on: Boolean, onClick: (Offset) -> Unit): Modifier {
    var center by remember { mutableStateOf(Offset.Zero) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return onGloballyPositioned { center = it.boundsInWindow().center }
        .pressScale(pressed, 0.9f)
        .clip(CircleShape)
        .selectable(on, source, null, role = Role.Tab, onClick = { onClick(center) })
        .semantics { contentDescription = item.label }
}

/** A tab's icon: filled when current, with a small pop as it becomes current (or, with [popIn], as it first shows). */
@Composable
private fun NavIcon(sym: Sym, on: Boolean, tint: Color, popIn: Boolean = false) {
    val still = AlbumTheme.spec.still
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(on && !popIn) }
    LaunchedEffect(on) {
        if (on && !was && !still) {
            pop.snapTo(0.7f)
            pop.animateTo(1.14f, tween(250))
            pop.animateTo(1f, tween(170))
        }
        was = on
    }
    Icon(sym, null, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, filled = on, tint = tint)
}

@Composable
private fun SearchButton(size: Dp, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier.size(size).pressScale(pressed, 0.9f).clip(CircleShape)
            .clickable(source, null, role = Role.Button, onClickLabel = "Search", onClick = onSearch)
            .semantics { contentDescription = "Search" },
        contentAlignment = Alignment.Center,
    ) { Icon(Sym.Search, null, tint = AlbumTheme.colors.ink) }
}

/** Dot and Labels: one capsule of tabs, a mark under the current one, search inside after a hairline. */
@Composable
private fun CapsuleBar(items: List<NavItem>, active: Int, onSelect: (Int, Offset) -> Unit, onSearch: () -> Unit, labels: Boolean, popIn: Boolean) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.height(56.dp).floating().padding(start = if (labels) Space.s3 else Space.s2, end = Space.s2).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (labels) 2.dp else Space.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val on = i == active
                val mark by animateFloatAsState(
                    if (on) 1f else 0f, if (still) snap() else spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "mark",
                )
                Box(
                    Modifier.height(48.dp).widthIn(min = 52.dp).navTab(item, on) { onSelect(i, it) }
                        .then(if (labels) Modifier.padding(horizontal = Space.s3) else Modifier.width(56.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (labels) {
                        Text(item.label, style = AlbumTheme.type.label.copy(fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium), color = c.ink, maxLines = 1)
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(18.dp, 3.dp)
                                .graphicsLayer { scaleX = mark }.background(c.flashInk, RoundedCornerShape(2.dp)),
                        )
                    } else {
                        NavIcon(item.sym, on, c.ink, popIn)
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp).scale(mark).background(c.flashInk, CircleShape))
                    }
                }
            }
            Box(Modifier.padding(horizontal = Space.s1).size(1.dp, 24.dp).background(c.lineStrong.copy(alpha = 0.6f)))
            SearchButton(48.dp, onSearch)
        }
    }
}

/** Slider: a segmented capsule whose flash thumb slides to the current tab, and a search disc beside it. */
@Composable
private fun SliderBar(items: List<NavItem>, active: Int, onSelect: (Int, Offset) -> Unit, onSearch: () -> Unit, popIn: Boolean) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).height(60.dp).floating().padding(4.dp).selectableGroup()) {
            val w = maxWidth / items.size
            val x by animateDpAsState(w * active, if (still) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "thumb")
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().background(c.flash, CircleShape))
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, item ->
                    val on = i == active
                    val tint = if (on) c.onFlash else c.ink
                    Column(
                        Modifier.weight(1f).fillMaxHeight().navTab(item, on) { onSelect(i, it) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    ) {
                        NavIcon(item.sym, on, tint, popIn)
                        Text(item.label, style = AlbumTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = tint, maxLines = 1)
                    }
                }
            }
        }
        SearchButton(60.dp, onSearch, Modifier.floating())
    }
}

/** Classic: a full-width bar on the bottom edge, an indicator pill behind the current tab's icon, labels under. */
@Composable
private fun ClassicBar(items: List<NavItem>, active: Int, onSelect: (Int, Offset) -> Unit, onSearch: () -> Unit, modifier: Modifier, mini: Boolean) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val h by animateDpAsState(if (mini) 54.dp else 78.dp, if (still) snap() else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "classic")
    Row(
        modifier.fillMaxWidth().background(c.surface).drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
            .navigationBarsPadding().height(h).padding(start = Space.s2, end = Space.s2, top = 6.dp, bottom = Space.s4)
            .selectableGroup(),
    ) {
        @Composable
        fun Item(sym: Sym, label: String, on: Boolean, m: Modifier) = Column(
            m.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(60.dp, 32.dp).background(if (on) c.flashSoft else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                NavIcon(sym, on, c.ink)
            }
            if (!mini) Text(label, style = AlbumTheme.type.caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), color = c.ink, maxLines = 1)
        }
        items.forEachIndexed { i, item -> Item(item.sym, item.label, i == active, Modifier.navTab(item, i == active) { onSelect(i, it) }) }
        Item(Sym.Search, "Search", false, Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Search", onClick = onSearch).semantics { contentDescription = "Search" })
    }
}

/**
 * The bar while the Library scrolls: the current tab (tap to show all tabs) and search, still in reach. Each capsule pops
 * in about its own centre from 0.85.
 */
@Composable
private fun MiniBar(item: NavItem, onExpand: () -> Unit, onSearch: () -> Unit) {
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(280, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f))) }
    val popIn = Modifier.graphicsLayer {
        val k = grow.value
        scaleX = 0.85f + 0.15f * k
        scaleY = scaleX
        alpha = k.coerceIn(0f, 1f)
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(
            popIn.pressScale(pressed, 0.9f).height(56.dp).floating().clip(CircleShape)
                .clickable(source, null, role = Role.Button, onClickLabel = "Show all tabs", onClick = onExpand)
                .semantics { contentDescription = "${item.label}, show all tabs" }
                .padding(start = Space.s3, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(item.sym, null, filled = true, tint = c.ink)
            Text(item.label, style = AlbumTheme.type.label, color = c.ink, maxLines = 1)
        }
        SearchButton(56.dp, onSearch, popIn.floating())
    }
}

/** Pill: the active destination is a flash pill with its label; the others show only their icon; search on its own disc. */
@Composable
private fun PillBar(items: List<NavItem>, active: Int, onSelect: (index: Int, center: Offset) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
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
    // Under a confirmation the count row steps aside; the selection itself is untouched
    val aside = ConfirmShown.count > 0
    val headAlpha by animateFloatAsState(if (aside) 0f else 1f, tween(150), label = "selHead")
    val headDrop by animateDpAsState(if (aside) 8.dp else 0.dp, tween(220, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)), label = "selHeadDrop")
    Column(modifier.fillMaxWidth().padding(horizontal = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = headAlpha; translationY = headDrop.toPx() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.floating().padding(end = Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
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

// ---------- Container transform ----------

/**
 * The card a tap just opened something from, in window coordinates, so the screen it opens can grow out of it. Set by
 * [opensScreen]; read once by the screen stack.
 */
internal object OpenedFrom {
    private var rect: Rect? = null
    private var at = 0L

    fun set(bounds: Rect) {
        rect = bounds
        at = SystemClock.uptimeMillis()
    }

    /** The card let go of in the last 300 ms, once: a screen opened any other way slides in as usual. */
    fun take(): Rect? = rect.takeIf { SystemClock.uptimeMillis() - at < 300 }.also { rect = null }
}

/**
 * Marks a card that opens a screen (an album, a group, a person, a place, a memory): the screen grows out of the card's
 * bounds instead of sliding in. Put it on the card itself, next to its click; it only notes where a tap let go.
 */
fun Modifier.opensScreen(): Modifier = this then OpensScreenElement

private data object OpensScreenElement : ModifierNodeElement<OpensScreenNode>() {
    override fun create() = OpensScreenNode()
    override fun update(node: OpensScreenNode) {}
}

private class OpensScreenNode : Modifier.Node(), PointerInputModifierNode, GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        // Before the card's own click sees the release, which is when it opens the screen
        if (pass != PointerEventPass.Initial || pointerEvent.type != PointerEventType.Release) return
        coordinates?.takeIf { it.isAttached }?.let { OpenedFrom.set(it.boundsInWindow()) }
    }

    override fun onCancelPointerInput() {}
}
