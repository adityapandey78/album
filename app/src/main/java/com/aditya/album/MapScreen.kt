package com.aditya.album

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo to show on the map, from its details: the map opens on it, its pin ringed. */
data class MapFocus(val mediaId: Long, val lat: Double, val lng: Double)

/**
 * Every Library photo and video with its own GPS, newest first, at world points [xs], [ys], gathered into pins at each
 * whole zoom level. Built off the main thread.
 */
class PhotoMap(val photos: List<Media>, val xs: DoubleArray, val ys: DoubleArray) {
    /** A pin: its newest photo (where it sits, and its cover) and all of its photos by index into [photos]. */
    inner class Pin(level: Int, val points: IntArray) {
        val cover get() = photos[points[0]]
        val x get() = xs[points[0]]
        val y get() = ys[points[0]]
        val id = mapAreaIdOf(level, cover.id)
        val items get() = points.map { photos[it] }
    }

    val box = boxOf(xs, ys)
    private val levels = (0..MAX_ZOOM.toInt()).map { level -> clusters(xs, ys, pinSpacing(level)).map { Pin(level, it) } }

    fun pins(level: Int) = levels[level.coerceIn(levels.indices)]

    /** The pin behind album id [id] (see mapAreaIdOf), or null once it's gone. */
    fun pin(id: Long): Pin? = levels.getOrNull(mapAreaLevel(id))?.find { it.id == id }
}

/** The Library's photos with GPS, in [photos]' order (newest first). Photos without GPS, estimated places included, stay off. */
fun photoMapOf(gps: List<ItemGps>, photos: List<Media>): PhotoMap {
    val at = HashMap<Long, ItemGps>(gps.size * 2)
    gps.forEach { if (it.lat != 0.0 || it.lng != 0.0) at[it.id] = it } // 0, 0 is a camera's blank GPS, not the Gulf of Guinea
    val located = photos.filter { it.id in at }
    val xs = DoubleArray(located.size) { mercatorX(at.getValue(located[it].id).lng) }
    val ys = DoubleArray(located.size) { mercatorY(at.getValue(located[it].id).lat) }
    return PhotoMap(located, xs, ys)
}

// The index changes every few seconds while it runs; the map re-reads it at most this often, as Explore does
private const val GPS_REFRESH_MS = 3_000L

/** The map's photos, shared by the map and the pin albums it opens. Null until first loaded; works while collected. */
object MapPhotos {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val map: StateFlow<PhotoMap?> = combine(SmartIndex.gps().conflate().transform { emit(it); delay(GPS_REFRESH_MS) }, Explore.photos, ::photoMapOf)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null) // the pin album keeps it while the map is covered
}

/**
 * The world map's levels of detail as paths, read from assets/map off the main thread the first time the map opens and
 * kept after: the coarse level comes first, so land shows at once.
 */
private object WorldMap {
    class Chunk(val left: Double, val top: Double, val right: Double, val bottom: Double, val path: Path)

    /** A level's layers as paths; its points aren't kept. */
    class Level(level: MapLevel) {
        val land = paths(level, LAND, close = true)
        val countries = paths(level, COUNTRIES, close = false)
        val states = paths(level, STATES, close = false)
    }

    private fun paths(level: MapLevel, kind: Int, close: Boolean) = level.layers[kind].orEmpty().map { chunk ->
        val path = Path()
        chunk.parts.forEach { p ->
            path.moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
            if (close) path.close()
        }
        Chunk(chunk.left, chunk.top, chunk.right, chunk.bottom, path)
    }

    // ponytail: kept for the life of the process once read (about 9 MB of native paths, 960,000 points). Drop it on
    // onTrimMemory, or read the closest level only when first zoomed to, if memory matters.
    private val loaded = MutableStateFlow<List<Level>>(emptyList())
    val levels = loaded.asStateFlow()
    private val started = AtomicBoolean(false)

    suspend fun load(context: Context) {
        if (!started.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            for (i in 0..2) {
                val bytes = context.assets.open("map/lod$i.bin").use { it.readBytes() }
                loaded.value = loaded.value + Level(readMapLevel(bytes))
            }
        }
    }
}

/** The camera the map opens on before it knows where your photos are: your time zone's side of the world. */
private fun homeCamera() = MapCamera(mercatorX(TimeZone.getDefault().rawOffset / 3_600_000.0 * 15), 0.5, 0.0)

private val CameraSaver = listSaver<MapCamera?, Double>(
    save = { c -> c?.let { listOf(it.x, it.y, it.zoom) }.orEmpty() },
    restore = { if (it.size == 3) MapCamera(it[0], it[1], it[2]) else null },
)

// A city: fitting one photo, or a street's worth, stops here rather than filling the screen with land
private const val FIT_MAX_ZOOM = 12.0
private const val FOCUS_ZOOM = 13.0
private const val PICK_PIN_DP = 40

/**
 * Where your photos were taken, on a simple offline map (land, country and state borders; no streets). Pinch to zoom,
 * drag to pan, double-tap to zoom in. Photos gather into pins with their newest photo and a count; a pin opens its
 * photos as an album through [onOpen]. Opens fitted to all your photos, or on [focus]. With [onPick] it drops a pin for a
 * new place instead: no photo pins, a pin held in the middle that the map moves under, and Use this spot.
 */
@Composable
fun MapScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, focus: MapFocus? = null, onPick: ((lat: Double, lng: Double) -> Unit)? = null) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val still = AlbumTheme.spec.still
    val map = MapPhotos.map.collectAsState().value
    val levels by WorldMap.levels.collectAsState()
    val placeOf = rememberPlaces()
    LaunchedEffect(Unit) { WorldMap.load(context) }
    BackHandler(onBack = onBack)

    val density = LocalDensity.current
    val tile = with(density) { WORLD_DP.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var camera by rememberSaveable(stateSaver = CameraSaver) { mutableStateOf<MapCamera?>(null) }
    // Placed on your photos (or the focus) once; after that only you move the map, however the photos change
    var placed by rememberSaveable { mutableStateOf(false) }
    // Moved away from the fitted view: then "Show all" brings it back
    var moved by rememberSaveable { mutableStateOf(focus != null) }

    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf<Job?>(null) }
    val decay = rememberSplineBasedDecay<Offset>()
    val pad = with(density) { (PIN_DP.dp / 2 + Space.s4).toPx() } // a pin on the edge still shows whole
    // Gestures call these long after this composition, so they read the size when called
    fun clamp(cam: MapCamera) = cam.clamped(size.width.toFloat(), size.height.toFloat(), tile)
    fun transform(cam: MapCamera, pan: Offset, zoom: Float, about: Offset) =
        cam.transformed(pan, zoom, about, size.width.toFloat(), size.height.toFloat(), tile)
    fun fitted() = map?.box?.let { fit(it, size.width.toFloat(), size.height.toFloat(), tile, pad, FIT_MAX_ZOOM) }
    fun moveTo(target: MapCamera) {
        motion?.cancel()
        val from = camera
        if (from == null || still) {
            camera = clamp(target)
            return
        }
        val dx = wrapDx(target.x - from.x)
        motion = scope.launch {
            animate(0f, 1f, animationSpec = tween(450, easing = FastOutSlowInEasing)) { t, _ ->
                camera = clamp(MapCamera(from.x + dx * t, from.y + (target.y - from.y) * t, from.zoom + (target.zoom - from.zoom) * t))
            }
        }
    }

    LaunchedEffect(size, map) {
        if (size == IntSize.Zero) return@LaunchedEffect
        val now = camera
        when {
            now == null && focus != null -> {
                camera = clamp(MapCamera(mercatorX(focus.lng), mercatorY(focus.lat), FOCUS_ZOOM))
                placed = true
            }
            now == null -> {
                camera = clamp(fitted() ?: homeCamera())
                placed = map != null
            }
            !placed && map != null -> {
                fitted()?.let(::moveTo) // the photos arrived after the map opened: fly to them
                placed = true
            }
            else -> camera = clamp(now) // a new screen size (rotation)
        }
    }

    // The pins on screen, at the camera's whole zoom level. Recomputed as the map moves, but the screen only changes when
    // a pin comes or goes: pins are placed in layout, never recomposed to move.
    val margin = with(density) { PIN_DP.dp.toPx() }
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val visible by remember(map, size) {
        derivedStateOf(structuralEqualityPolicy()) {
            val cam = camera ?: return@derivedStateOf emptyList()
            val worldPx = cam.worldPx(tile)
            map?.pins(floor(cam.zoom).toInt())?.filter { pin ->
                val sx = w / 2 + wrapDx(pin.x - cam.x) * worldPx
                val sy = h / 2 + (pin.y - cam.y) * worldPx
                sx > -margin && sx < w + margin && sy > -margin && sy < h + margin
            }.orEmpty()
        }
    }
    val focusIndex = remember(map, focus) { focus?.let { f -> map?.photos?.indexOfFirst { it.id == f.mediaId } } ?: -1 }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        Box(Modifier.background(c.canvas).statusBarsPadding()) {
            if (onPick != null) CollapsedTopBar("Drop a pin", subtitle = "Move the map to put the pin on the place", onBack = onBack)
            else CollapsedTopBar("Map", subtitle = map?.let { countOf(it.photos.size, "item") }, onBack = onBack)
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        camera?.let { cam ->
                            moved = true
                            placed = true
                            moveTo(transform(cam, Offset.Zero, 2f, at))
                        }
                    })
                }
                .pointerInput(Unit) {
                    mapGestures(
                        onStart = { motion?.cancel() },
                        onMove = { pan, zoom, centroid ->
                            camera?.let { cam ->
                                moved = true
                                placed = true
                                camera = transform(cam, pan, zoom, centroid)
                            }
                        },
                        onFling = { velocity ->
                            motion = scope.launch {
                                var last = Offset.Zero
                                Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(velocity, decay) {
                                    val step = value - last
                                    last = value
                                    camera = camera?.let { transform(it, step, 1f, Offset.Zero) }
                                }
                            }
                        },
                    )
                }
                .semantics { contentDescription = if (onPick != null) "Map, with a pin in the middle" else "Map of your photos" },
        ) {
            val sea = if (c.dark) c.canvas else c.surfaceHigh // water is the darker of the two, in every theme
            val land = if (c.dark) c.surfaceHigh else c.surface
            val border = c.lineStrong
            val state = c.lineStrong.copy(alpha = 0.45f)
            val borderWidth = with(density) { 1.dp.toPx() }
            val stateWidth = with(density) { 0.75.dp.toPx() }
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(sea)
                    val cam = camera ?: return@drawBehind
                    val level = levels.getOrNull(levelOfDetail(cam.zoom)) ?: levels.lastOrNull() ?: return@drawBehind
                    val worldPx = cam.worldPx(tile)
                    drawLayer(level.land, cam, worldPx, land, null)
                    // States fade in from zoom 4, where a country fills the screen
                    val stateAlpha = (cam.zoom - 4).toFloat().coerceIn(0f, 1f)
                    if (stateAlpha > 0f) drawLayer(level.states, cam, worldPx, state.copy(alpha = state.alpha * stateAlpha), stateWidth)
                    drawLayer(level.countries, cam, worldPx, border, borderWidth)
                },
            )
            // The focus photo isn't in any pin (its folder is hidden from the Library, or it's still being indexed): a dot
            // marks where it was taken instead
            if (focus != null && map != null && focusIndex < 0 && onPick == null) {
                Layout({ Box(Modifier.size(14.dp).background(c.surface, CircleShape).padding(2.dp).background(c.flash, CircleShape)) }) { m, _ ->
                    val p = m.first().measure(Constraints())
                    layout(size.width, size.height) {
                        camera?.let { cam ->
                            val worldPx = cam.worldPx(tile)
                            val sx = w / 2 + wrapDx(mercatorX(focus.lng) - cam.x) * worldPx
                            val sy = h / 2 + (mercatorY(focus.lat) - cam.y) * worldPx
                            p.place(sx.roundToInt() - p.width / 2, sy.roundToInt() - p.height / 2)
                        }
                    }
                }
            }
            val shown = if (onPick != null) emptyList() else visible // read once, so each pin is placed by its own entry
            Layout({
                shown.forEach { pin ->
                    key(pin.id) {
                        MapPinView(
                            pin,
                            focused = focusIndex >= 0 && focusIndex in pin.points,
                            label = {
                                val where = pin.items.mapNotNull { placeOf[it.id] }
                                "${countOf(pin.points.size, "item")}, ${areaTitle(where, coordinates(latOf(pin.y), lngOf(pin.x)))}"
                            },
                            onClick = {
                                CrashLog.note("open map pin ${pin.points.size}") // no place: crash logs never say where you've been
                                onOpen(pin.id)
                            },
                        )
                    }
                }
            }) { measurables, constraints ->
                val placeables = measurables.map { it.measure(Constraints()) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val cam = camera ?: return@layout
                    val worldPx = cam.worldPx(tile)
                    placeables.forEachIndexed { i, p ->
                        val pin = shown[i]
                        val sx = w / 2 + wrapDx(pin.x - cam.x) * worldPx
                        val sy = h / 2 + (pin.y - cam.y) * worldPx
                        p.place(sx.roundToInt() - p.width / 2, sy.roundToInt() - p.height / 2)
                    }
                }
            }
            if (map != null && map.photos.isEmpty() && onPick == null) {
                val indexing = SmartIndex.progress.collectAsState().value.let { it.done < it.total }
                EmptyState(
                    Sym.LocationOff,
                    "Nothing on the map yet",
                    if (indexing) "Album is still reading where your photos were taken." else "Photos taken with location on show up here.",
                    Modifier.align(Alignment.Center).padding(Space.s4).background(c.surface, RoundedCornerShape(Space.radiusLg)),
                )
            }
            if (onPick != null) {
                // The pin's point is the middle of the map: the glyph's tip sits on it
                Icon(Sym.LocationOn, null, Modifier.align(Alignment.Center).offset(y = -PICK_PIN_DP.dp / 2), filled = true, size = PICK_PIN_DP.dp, tint = c.flashInk)
                AlbumButton(
                    "Use this spot",
                    { camera?.let { onPick(latOf(it.y), lngOf(it.x)) } },
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Space.s4).fillMaxWidth().heightIn(min = 56.dp),
                    ButtonVariant.Primary,
                    Sym.Check,
                )
            } else {
                ShowAllButton(moved && map?.box != null, Modifier.align(Alignment.BottomEnd)) {
                    fitted()?.let(::moveTo)
                    moved = false
                }
            }
        }
    }
}

/** Brings back the view of all your photos, once you've moved away from it. In the thumb's reach. */
@Composable
private fun ShowAllButton(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val still = AlbumTheme.spec.still
    AnimatedVisibility(
        visible,
        modifier.navigationBarsPadding().padding(end = Space.edgeSafe, bottom = Space.s4),
        enter = fadeIn(tween(if (still) 0 else 180)),
        exit = fadeOut(tween(if (still) 0 else 150)),
    ) { AlbumIconButton(Sym.Fullscreen, "Show all photos", onClick, variant = IconButtonVariant.Glass) }
}

/** Draws the chunks of a layer that are on screen, in each copy of the world that shows (the map wraps sideways). */
private fun DrawScope.drawLayer(chunks: List<WorldMap.Chunk>, cam: MapCamera, worldPx: Double, color: Color, strokePx: Float?) {
    // Paths are in world units from their chunk's corner, so the scale is the world's width in pixels; a line's width is
    // divided by it to come out the same at every zoom
    val scale = worldPx.toFloat()
    val style = strokePx?.let { Stroke(it / scale, join = StrokeJoin.Round) }
    for (chunk in chunks) {
        val top = size.height / 2 + (chunk.top - cam.y) * worldPx
        val bottom = size.height / 2 + (chunk.bottom - cam.y) * worldPx
        if (bottom < 0 || top > size.height) continue
        for (copy in -1..1) {
            val left = size.width / 2 + (chunk.left + copy - cam.x) * worldPx
            val right = size.width / 2 + (chunk.right + copy - cam.x) * worldPx
            if (right < 0 || left > size.width) continue
            withTransform({
                translate(left.toFloat(), top.toFloat())
                scale(scale, scale, Offset.Zero)
            }) {
                if (style == null) drawPath(chunk.path, color) else drawPath(chunk.path, color, style = style)
            }
        }
    }
}

/**
 * Pan, pinch and fling, in one gesture: [onMove] gets each step's pan and zoom about the fingers' centre once they've
 * moved past touch slop (so a tap still reaches a pin), and [onFling] the speed of the last finger to lift.
 */
private suspend fun PointerInputScope.mapGestures(
    onStart: () -> Unit,
    onMove: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onFling: (velocity: Offset) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    onStart()
    val tracker = VelocityTracker()
    var moving = false
    var travelled = Offset.Zero
    var zoomed = 1f
    var fingers = 1
    do {
        val event = awaitPointerEvent()
        val down = event.changes.count { it.pressed }
        // Speed is the one finger's: a second finger landing or lifting would jump the centre
        if (down > 0 && down != fingers) {
            tracker.resetTracking()
            fingers = down
        }
        val pan = event.calculatePan()
        val zoom = event.calculateZoom()
        if (!moving && down > 0) {
            travelled += pan
            zoomed *= zoom
            moving = travelled.getDistance() > viewConfiguration.touchSlop ||
                abs(1 - zoomed) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
        }
        val centroid = event.calculateCentroid(useCurrent = false)
        if (moving && down > 0 && centroid.isSpecified) {
            if (pan != Offset.Zero || zoom != 1f) onMove(pan, zoom, centroid)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        // The finger still down, or at the end the one lifting
        if (fingers == 1) (event.changes.firstOrNull { it.pressed } ?: event.changes.firstOrNull())?.let(tracker::addPointerInputChange)
    } while (event.changes.any { it.pressed })
    if (moving && fingers == 1) tracker.calculateVelocity().let { onFling(Offset(it.x, it.y)) }
}

/** A pin: its newest photo in a surface frame, with how many photos it holds. */
@Composable
private fun MapPinView(pin: PhotoMap.Pin, focused: Boolean, label: () -> String, onClick: () -> Unit) {
    val c = AlbumTheme.colors
    val frame = RoundedCornerShape(14.dp)
    Box(Modifier.size(PIN_DP.dp)) {
        Box(
            Modifier.fillMaxSize()
                .background(c.surface, frame)
                // The ring says which pin holds the photo the map was opened on; a thin line-strong edge otherwise
                .border(if (focused) 2.dp else 1.dp, if (focused) c.flashInk else c.lineStrong, frame)
                .clip(frame)
                .clickable(role = Role.Button, onClickLabel = "Show photos", onClick = onClick)
                .semantics { contentDescription = label() }
                .padding(3.dp),
        ) { Thumbnail(pin.cover, Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp))) }
        if (pin.points.size > 1) {
            // A big count may be wider than the pin, so it's allowed past its edge. TalkBack hears it in the pin's label.
            Badge(
                "%,d".format(pin.points.size),
                Modifier.align(Alignment.BottomEnd).wrapContentWidth(Alignment.End, unbounded = true).padding(end = 5.dp, bottom = 5.dp)
                    .clearAndSetSemantics {},
                tone = BadgeTone.Photo,
            )
        }
    }
}

private const val EMPTY_AREA = "No photos here any more."

/**
 * The photos of a map pin opened as an album (see mapAreaIdOf), named by their places. Null while the map loads, or once
 * the pin is gone (its newest photo deleted), so the album closes.
 */
@Composable
fun rememberMapArea(id: Long): SmartAlbum? {
    val map = MapPhotos.map.collectAsState().value
    val places = Explore.places.collectAsState().value
    return remember(id, map, places) {
        val pin = map?.pin(id) ?: return@remember null
        val items = pin.items
        // Untitled for the moment the places take to load, rather than coordinates that turn into a name
        val title = places?.let { groups ->
            val ids = items.mapTo(HashSet()) { it.id }
            val where = groups.flatMap { g -> g.items.filter { it.id in ids }.map { g.key } }
            areaTitle(where, coordinates(latOf(pin.y), lngOf(pin.x)))
        }.orEmpty()
        SmartAlbum(title, items, EMPTY_AREA)
    }
}
