package com.aditya.album

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as MlText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// Text from a photo, like Google Lens: ML Kit's bundled model reads the photo on the phone (offline; nothing leaves it),
// every line it found is outlined on the photo, and the person selects words to copy, as in a text field.

/** A word ML Kit read. [box] is in fractions (0 to 1) of the photo's width and height; [line] counts lines from 0. */
class LensWord(val text: String, val box: Rect, val line: Int)

/** All the text read from one photo, words in reading order. [lines] are each line's box, in the same fractions. */
class LensText(val words: List<LensWord>, val lines: List<Rect>, val width: Int, val height: Int)

/** The lens over one photo: what was read ([text] is null while reading) and which words are selected. */
@Stable
class TextLens {
    var text by mutableStateOf<LensText?>(null)
        internal set

    /** Indexes into the words, first to last; null when nothing is selected. */
    var selection by mutableStateOf<IntRange?>(null)
}

/** Reads the text in [m] once (again after an edit), off the main thread. */
@Composable
fun rememberTextLens(m: Media): TextLens {
    val context = LocalContext.current
    val lens = remember(m.uri, editVersion(m.uri)) { TextLens() }
    LaunchedEffect(lens) {
        val started = SystemClock.elapsedRealtime()
        val read = readText(context, m.uri)
        CrashLog.note("text lens: ${read.words.size} words in ${SystemClock.elapsedRealtime() - started} ms")
        lens.text = read
    }
    return lens
}

/**
 * Longest side the photo is read at. More than the smart index's 2048: here the person is looking for the small print
 * too. ponytail: one decode of up to 36 MB; tile the photo if fine print on 200 MP photos goes unread.
 */
private const val LENS_PX = 3072

/** Everything ML Kit can read in [uri]; no words when it has none or can't be read. */
private suspend fun readText(context: Context, uri: Uri): LensText {
    val bitmap = withContext(Dispatchers.IO) { decodeUpright(context, uri, LENS_PX) } ?: return LensText(emptyList(), emptyList(), 0, 0)
    val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val text = suspendCancellableCoroutine<MlText?> { c ->
            reader.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { c.resume(if (it.isSuccessful) it.result else null) }
        }
        return withContext(Dispatchers.Default) { lensText(text, bitmap.width, bitmap.height) }
    } finally {
        reader.close()
    }
}

/**
 * The photo as it's shown (ImageDecoder applies the Exif orientation; RAW photos are turned by [uprightRaw]), at most
 * [maxPx] on its longest side, in the software sRGB ARGB_8888 that ML Kit reads (as SmartIndex decodes). Also what codes,
 * straightening and the RAW viewer read. Null when it can't be decoded. Blocking: call off the main thread.
 */
internal fun decodeUpright(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
    val resolver = context.contentResolver
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val scale = maxPx.toFloat() / max(info.size.width, info.size.height)
        if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
    }
    // 10-bit HDR photos decode to other configs
    val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    if (runCatching { resolver.getType(uri) }.getOrNull() == RAW_MIME) uprightRaw(resolver, uri, bitmap, thumbnail = false) else bitmap
} catch (e: Exception) {
    CrashLog.note("decode upright failed ($e)")
    null
}

/** ML Kit's blocks, lines and words as [LensText], in fractions of the [width] x [height] it read. */
private fun lensText(text: MlText?, width: Int, height: Int): LensText {
    val words = ArrayList<LensWord>()
    val lines = ArrayList<Rect>()
    fun fraction(r: android.graphics.Rect) = Rect(r.left / width.toFloat(), r.top / height.toFloat(), r.right / width.toFloat(), r.bottom / height.toFloat())
    for (block in text?.textBlocks.orEmpty()) for (line in block.lines) {
        val box = line.boundingBox ?: continue
        val found = line.elements.mapNotNull { e -> e.boundingBox?.let { LensWord(e.text, fraction(it), lines.size) } }
        if (found.isEmpty()) continue
        words += found
        lines += fraction(box)
    }
    return LensText(words, lines, width, height)
}

// ---------- Selecting ----------

/** Where [this] box, in fractions of the photo, is on screen while the photo is drawn at [photo]. */
fun Rect.onPhoto(photo: Rect) =
    Rect(photo.left + left * photo.width, photo.top + top * photo.height, photo.left + right * photo.width, photo.top + bottom * photo.height)

/** The words in [range] as text: a space between words on a line, a new line between lines. */
fun selectedText(words: List<LensWord>, range: IntRange): String = buildString {
    for (i in range) {
        if (i > range.first) append(if (words[i].line == words[i - 1].line) ' ' else '\n')
        append(words[i].text)
    }
}

/** Every word on the same line as word [i]. */
fun lineOf(words: List<LensWord>, i: Int): IntRange {
    var first = i
    while (first > 0 && words[first - 1].line == words[i].line) first--
    var last = i
    while (last < words.lastIndex && words[last + 1].line == words[i].line) last++
    return first..last
}

/** The word nearest [point] on screen (any word whose box holds it counts as 0 away); null when none is within [reach]. */
fun wordNear(words: List<LensWord>, photo: Rect, point: Offset, reach: Float = Float.MAX_VALUE): Int? {
    var best: Int? = null
    var bestDistance = reach
    words.forEachIndexed { i, w ->
        val r = w.box.onPhoto(photo)
        val d = hypot(max(max(r.left - point.x, point.x - r.right), 0f), max(max(r.top - point.y, point.y - r.bottom), 0f))
        if (d <= bestDistance) {
            best = i
            bestDistance = d
        }
    }
    return best
}

/** Where the photo is drawn: [photo] from Telephoto, which has nothing until the full photo loads; fitted till then. */
private fun LensText.area(photo: Rect, box: Size) = photo.takeUnless { it.isEmpty } ?: fitRect(width, height, box)

/** The tip of a selection handle: under the first word's start or the last word's end, at the bottom of the word. */
private fun handleTip(words: List<LensWord>, i: Int, photo: Rect, start: Boolean): Offset =
    words[i].box.onPhoto(photo).let { if (start) it.bottomLeft else it.bottomRight }

/**
 * Selecting words, as in a text field: tap a word to select its line, drag across words to select from that word to the
 * one under the finger, or drag a selection handle to move that end. It looks at touches before the photo does and
 * takes only those that start on a word or a handle, so pinches and touches elsewhere still zoom and pan the photo.
 * [photo] is where the photo is drawn right now.
 */
fun Modifier.lensSelection(lens: TextLens, photo: () -> Rect, haptics: Haptics): Modifier = pointerInput(lens, haptics) {
    val handleReach = 28.dp.toPx() // the handle is drawn just below its tip
    val wordReach = 8.dp.toPx() // small print on a zoomed-out photo is smaller than a finger
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val text = lens.text?.takeIf { it.words.isNotEmpty() } ?: return@awaitEachGesture
        val words = text.words
        val area = text.area(photo(), size.toSize())
        val selected = lens.selection
        val handleDown = Offset(0f, handleReach / 2) // the handle's middle, below its tip
        // The end that stays put while the other follows the finger
        val fixed = when {
            selected != null && (handleTip(words, selected.first, area, start = true) + handleDown - down.position).getDistance() < handleReach -> selected.last
            selected != null && (handleTip(words, selected.last, area, start = false) + handleDown - down.position).getDistance() < handleReach -> selected.first
            else -> null
        }
        val onHandle = fixed != null
        val anchor = fixed ?: wordNear(words, area, down.position, wordReach) ?: return@awaitEachGesture
        var dragging = false
        var last = -1
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture // a pinch: the photo's
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (change.changedToUp()) {
                if (!dragging) {
                    change.consume() // not a tap on the photo, which would clear the selection
                    if (!onHandle) lens.selection = lineOf(words, anchor)
                }
                return@awaitEachGesture
            }
            if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
            if (dragging) {
                change.consume() // the photo doesn't pan meanwhile
                val at = wordNear(words, text.area(photo(), size.toSize()), change.position) ?: continue
                if (at != last) {
                    last = at
                    lens.selection = minOf(anchor, at)..maxOf(anchor, at)
                    haptics.lightTick()
                }
            }
        }
    }
}

// ---------- Drawing ----------

/** Selected words are drawn at this size, then scaled to their boxes; measured once each, not each frame. */
private val wordStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 32.sp)

/**
 * Over the photo: dims it and outlines each line in flash-soft; selected words in flash with their text in on-flash,
 * and a handle at each end of the selection. [photo] is where the photo is drawn right now (it follows zoom and pan).
 */
@Composable
fun TextLensHighlights(lens: TextLens, photo: () -> Rect) {
    val text = lens.text ?: return
    val c = AlbumTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 0) // [layouts] is the cache
    val layouts = remember(text) { arrayOfNulls<TextLayoutResult>(text.words.size) }
    Canvas(Modifier.fillMaxSize()) {
        val area = text.area(photo(), size)
        drawRect(Color.Black, area.topLeft, area.size, alpha = 0.32f)
        val corner = CornerRadius(4.dp.toPx())
        val pad = 2.dp.toPx()
        text.lines.forEach { line ->
            val r = line.onPhoto(area).inflate(pad)
            drawRoundRect(c.flashSoft, r.topLeft, r.size, corner, alpha = 0.55f)
        }
        val range = lens.selection ?: return@Canvas
        for (i in range) {
            val r = text.words[i].box.onPhoto(area).inflate(pad)
            drawRoundRect(c.flash, r.topLeft, r.size, corner)
            val layout = layouts[i] ?: measurer.measure(text.words[i].text, wordStyle).also { layouts[i] = it }
            val k = minOf(r.width / layout.size.width, r.height / layout.size.height)
            translate(r.left + (r.width - layout.size.width * k) / 2, r.top + (r.height - layout.size.height * k) / 2) {
                scale(k, Offset.Zero) { drawText(layout, c.onFlash) }
            }
        }
        handle(handleTip(text.words, range.first, area, start = true), start = true, c.flash)
        handle(handleTip(text.words, range.last, area, start = false), start = false, c.flash)
    }
}

/** Android's text selection handle: a drop whose square corner points at [tip], hanging below it to the outside. */
private fun DrawScope.handle(tip: Offset, start: Boolean, color: Color) {
    val r = 10.dp.toPx()
    val side = if (start) -r else r
    drawCircle(color, r, Offset(tip.x + side, tip.y + r))
    drawRect(color, Offset(minOf(tip.x, tip.x + side), tip.y), Size(r, r))
}

// ---------- Chrome ----------

/** The lens's top bar over the photo: Close, and what it's for. */
@Composable
fun TextLensBar(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = AlbumTheme.colors
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(c.scrimPhoto, Color.Transparent)))
            .statusBarsPadding()
            .padding(start = Space.s2, end = Space.s2, top = Space.s1, bottom = Space.s8),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumIconButton(Sym.Close, "Close", onClose, variant = IconButtonVariant.Photo)
        Text("Text", style = AlbumTheme.type.label, color = c.onPhoto)
    }
}

/**
 * The lens's panel at the bottom: the selected words (all the text while none are), the links, phone numbers and e-mail
 * addresses in all of it as chips ([onLink] asks before opening one), Select all and Copy. While reading, and when the
 * photo has no text, it says so instead. [onCopied] confirms the copy (the viewer's snackbar).
 */
@Composable
fun TextLensPanel(lens: TextLens, onCopied: () -> Unit, onLink: (CodeContent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val text = lens.text
    val selection = lens.selection
    Column(
        modifier.fillMaxWidth()
            .pointerInput(Unit) {} // touches on the panel stay off the photo under it
            .background(c.surface, RoundedCornerShape(topStart = Space.radiusLg, topEnd = Space.radiusLg))
            .navigationBarsPadding()
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        when {
            text == null -> Text("Reading the text in this photo", style = type.body, color = c.inkMuted)
            text.words.isEmpty() -> Text("No text found in this photo", style = type.body, color = c.inkMuted)
            else -> {
                val all = text.words.indices
                val shown = remember(text, selection) { selectedText(text.words, selection ?: all) }
                Text(if (selection == null) "All text" else "Selected text", style = type.label, color = c.inkMuted)
                Text(shown, Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = type.body, color = c.ink)
                val links = remember(text) { linksIn(selectedText(text.words, all)) }
                if (links.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        links.forEach { link ->
                            Chip(chipLabel(link), {
                                CrashLog.note("text link")
                                onLink(link)
                            }, icon = link.icon(), toggle = false)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    AlbumButton("Select all", {
                        CrashLog.note("text select all")
                        lens.selection = all
                    }, Modifier.weight(1f), icon = Sym.SelectAll, enabled = selection != all)
                    AlbumButton("Copy", {
                        CrashLog.note("text copy ${shown.length} chars")
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Text from photo", shown))
                        onCopied()
                    }, Modifier.weight(1f), ButtonVariant.Primary, Sym.ContentCopy)
                }
            }
        }
    }
}
