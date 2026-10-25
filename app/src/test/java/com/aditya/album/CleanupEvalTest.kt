package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Clean up's evaluation: a small library built from the photos in resources/cleanup (see SOURCES.md) and copies, edits,
 * bursts and hard negatives made from them in code, run through the same pure steps as the phone ([lookAt], [CopyIndex],
 * [cleanupOf]). Prints pair precision and recall per tier and per edit, what would be wrongly pre-selected, and blur and
 * brightness precision, to build/reports/cleanup-eval.txt. Asserts the floors the knobs in Cleanup.kt were tuned to, and
 * PDQ distances between the fixtures themselves, so a change in measuring shows up here first.
 */
class CleanupEvalTest {
    /** A picture as ARGB pixels, [w] by [h]. */
    class Img(val w: Int, val h: Int, val px: IntArray)

    private fun load(name: String): Img = ImageIO.read(javaClass.getResourceAsStream("/cleanup/$name")).toImg()

    private fun BufferedImage.toImg() = Img(width, height, getRGB(0, 0, width, height, null, 0, width))

    private fun Img.toBuffered() = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, px, 0, w) }

    // ---------- Edits, in plain Kotlin on pixels (only JPEG and text come from the JDK) ----------

    /** Box-averaged down to [nw] by [nh], the way a decoder shrinks a big photo; or enlarged, each pixel repeated. */
    private fun Img.resize(nw: Int, nh: Int): Img {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val x0 = x * w / nw
            val x1 = maxOf(x0 + 1, (x + 1) * w / nw)
            val y0 = y * h / nh
            val y1 = maxOf(y0 + 1, (y + 1) * h / nh)
            var r = 0
            var g = 0
            var b = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val p = px[yy * w + xx]
                r += p shr 16 and 0xff
                g += p shr 8 and 0xff
                b += p and 0xff
            }
            val n = (x1 - x0) * (y1 - y0)
            out[y * nw + x] = rgb(r / n, g / n, b / n)
        }
        return Img(nw, nh, out)
    }

    private fun Img.longSide(side: Int): Img {
        val s = side.toFloat() / maxOf(w, h)
        return if (s >= 1f) this else resize((w * s).roundToInt(), (h * s).roundToInt())
    }

    /** What the phone measures: decoded at most LOOK_PX on the long side. */
    private fun Img.decoded() = longSide(LOOK_PX)

    private fun Img.jpegBytes(quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            writer.write(null, IIOImage(toBuffered(), null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun Img.jpeg(quality: Int) = ImageIO.read(jpegBytes(quality).inputStream()).toImg()

    private fun Img.turned() = Img(h, w, IntArray(w * h) { i -> px[(h - 1 - i % h) * w + i / h] }) // a quarter turn clockwise

    private fun Img.mirrored() = Img(w, h, IntArray(w * h) { i -> px[(i / w) * w + w - 1 - i % w] })

    /** [share] of the width and height cut away, half from each side. */
    private fun Img.cropped(share: Float): Img {
        val dx = (w * share / 2).roundToInt()
        val dy = (h * share / 2).roundToInt()
        val nw = w - 2 * dx
        return Img(nw, h - 2 * dy, IntArray(nw * (h - 2 * dy)) { i -> px[(dy + i / nw) * w + dx + i % nw] })
    }

    private fun Img.map(f: (Int, Int, Int) -> Int) = Img(w, h, IntArray(px.size) { f(px[it] shr 16 and 0xff, px[it] shr 8 and 0xff, px[it] and 0xff) })

    private fun Img.brighter(k: Float) = map { r, g, b -> rgb((r * k).toInt(), (g * k).toInt(), (b * k).toInt()) }

    // A warm, punchy filter: more contrast, red up, blue down
    private fun Img.filtered() = map { r, g, b ->
        fun c(v: Float) = ((v - 128) * 1.15f + 128).toInt()
        rgb(c(r * 1.08f + 6), c(g.toFloat()), c(b * 0.88f))
    }

    // A sticker and a caption bar over about 8% of the picture
    private fun Img.overlaid(): Img {
        val out = px.copyOf()
        val sx = w * 3 / 4
        val sy = h / 10
        val r = minOf(w, h) / 9
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - sx) * (x - sx) + (y - sy) * (y - sy) < r * r) out[y * w + x] = rgb(250, 200, 40)
            if (y > h * 0.9f && x > w * 0.1f && x < w * 0.6f) out[y * w + x] = rgb(255, 255, 255)
        }
        return Img(w, h, out)
    }

    /** A 1080 x 2400 phone screenshot of the photo shown in a viewer: status bar, black letterbox, navigation bar. */
    private fun Img.screenshot(): Img {
        val sw = 1080
        val sh = 2400
        val shown = resize(sw, (h * sw.toFloat() / w).roundToInt())
        val out = IntArray(sw * sh)
        val top = (sh - shown.h) / 2
        for (y in 0 until shown.h) shown.px.copyInto(out, (top + y) * sw, y * sw, (y + 1) * sw)
        for (y in 0 until 70) for (x in 0 until sw) out[y * sw + x] = if (x in 40..160 && y in 20..50) rgb(255, 255, 255) else rgb(20, 20, 20)
        for (y in sh - 130 until sh) for (x in 0 until sw) out[y * sw + x] = if (x in 400..680 && y in sh - 70..sh - 60) rgb(200, 200, 200) else 0
        return Img(sw, sh, out)
    }

    /** Separable Gaussian blur, sigma in pixels: a photo slightly out of focus. */
    private fun Img.gaussian(sigma: Float): Img {
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }.let { k -> FloatArray(k.size) { k[it] / k.sum() } }
        return convolve(k, horizontal = true).convolve(k, horizontal = false)
    }

    /** Camera shake: a horizontal smear [length] pixels long. */
    private fun Img.motion(length: Int) = convolve(FloatArray(length) { 1f / length }, horizontal = true)

    private fun Img.convolve(k: FloatArray, horizontal: Boolean): Img {
        val r = k.size / 2
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0f
            var gg = 0f
            var bb = 0f
            for (i in k.indices) {
                val p = if (horizontal) px[y * w + (x + i - r).coerceIn(0, w - 1)] else px[(y + i - r).coerceIn(0, h - 1) * w + x]
                rr += k[i] * (p shr 16 and 0xff)
                gg += k[i] * (p shr 8 and 0xff)
                bb += k[i] * (p and 0xff)
            }
            out[y * w + x] = rgb(rr.roundToInt(), gg.roundToInt(), bb.roundToInt())
        }
        return Img(w, h, out)
    }

    /** Lens defocus: every point spread over a disc of [radius] pixels. */
    private fun Img.defocus(radius: Int): Img {
        val offsets = (-radius..radius).flatMap { dy -> (-radius..radius).filter { dx -> dx * dx + dy * dy <= radius * radius }.map { dx -> dx to dy } }
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0
            var gg = 0
            var bb = 0
            for ((dx, dy) in offsets) {
                val p = px[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                rr += p shr 16 and 0xff
                gg += p shr 8 and 0xff
                bb += p and 0xff
            }
            out[y * w + x] = rgb(rr / offsets.size, gg / offsets.size, bb / offsets.size)
        }
        return Img(w, h, out)
    }

    /** Only the middle stays sharp (a portrait's subject); the rest goes soft like a wide-open lens. */
    private fun Img.bokeh(): Img {
        val soft = gaussian(4f)
        return Img(w, h, IntArray(px.size) { i ->
            val x = i % w
            val y = i / w
            if (abs(x - w / 2) < w / 6 && abs(y - h / 2) < h / 4) px[i] else soft.px[i]
        })
    }

    /** A printed page or receipt: the same layout every time, different text by [seed]. */
    private fun document(seed: Long): Img {
        val rnd = Random(seed)
        val img = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(245, 243, 238)
        g.fillRect(0, 0, 1200, 1600)
        g.color = Color(30, 30, 30)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 56)
        g.drawString("INVOICE ${1000 + rnd.nextInt(9000)}", 120, 200)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 34)
        val words = listOf("tea", "paneer", "rice", "dal", "roti", "chai", "lassi", "naan", "curd", "samosa", "dosa", "idli")
        for (line in 0 until 24) {
            val item = (0 until 1 + rnd.nextInt(3)).joinToString(" ") { words[rnd.nextInt(words.size)] }
            g.drawString(item, 120, 320 + line * 48)
            g.drawString("Rs ${10 + rnd.nextInt(990)}.00", 880, 320 + line * 48)
        }
        g.dispose()
        return img.toImg()
    }

    /** A pocket shot: nearly black, with sensor noise. */
    private fun dark(seed: Long): Img {
        val rnd = Random(seed)
        return Img(1280, 960, IntArray(1280 * 960) { i -> (6 + (i % 1280) / 160 + rnd.nextInt(8)).let { rgb(it, it, it + 2) } })
    }

    /** Pointed at the sun: nearly white. */
    private fun washedOut(seed: Long): Img {
        val rnd = Random(seed)
        return Img(1280, 960, IntArray(1280 * 960) { i -> (240 - (i / 1280) / 120 + rnd.nextInt(10)).coerceAtMost(255).let { rgb(it, it, it - 4) } })
    }

    /** A clear sky: a smooth gradient and a little noise, nothing to judge. */
    private fun sky(seed: Long): Img {
        val rnd = Random(seed)
        return Img(1280, 960, IntArray(1280 * 960) { i -> (120 + (i / 1280) / 12 + rnd.nextInt(4)).let { rgb(it - 60, it - 10, it + 60) } })
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    // ---------- The library ----------

    /**
     * One item of the test library. [origin] names the moment it shows (copies and edits share their original's),
     * [scene] the place and time similar shots share, [edit] what was done to it. [camera] is its EXIF capture time when
     * it still has camera details. [face] is where a face is, as shares of the picture, for the blur exemption.
     */
    class Item(
        val id: Long, val origin: String, val edit: String, val taken: Long, val path: String, val name: String,
        val camera: String?, val scene: String?, val night: Boolean, val face: FloatArray?,
    )

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_760_000_000_000L

    // The originals, with the capture times and exposures their EXIF had (SOURCES.md)
    private class Original(val file: String, val taken: Long, val camera: String?, val scene: String?, val night: Boolean, val face: FloatArray? = null)

    private val originals = listOf(
        Original("bridge-1-original.jpg", t0, "2025:10:09 10:00:00.00", "bridge burst", false),
        Original("iss040e108044.jpg", t0 + 5 * day + 22_060, "2014:08:21 19:06:22.06", "time-lapse", true),
        Original("iss040e108050.jpg", t0 + 5 * day + 64_000, "2014:08:21 19:07:04.00", "time-lapse", true),
        Original("KSC-20190913-PH_KLS01_0030.jpg", t0 + 10 * day + 5_900, "2019:09:13 10:43:05.9", null, false),
        Original("KSC-20190913-PH_KLS01_0035.jpg", t0 + 10 * day + 12_550, "2019:09:13 10:43:12.55", null, false),
        Original("NHQ202403030019.jpg", t0 + 20 * day + 46_460, "2024:03:03 21:22:46.46", null, true, floatArrayOf(0.34f, 0.18f, 0.56f, 0.64f)),
        Original("NHQ202403030020.jpg", t0 + 20 * day + 66_390, "2024:03:03 21:23:06.39", null, true, floatArrayOf(0.445f, 0.14f, 0.62f, 0.55f)),
        Original("iss061e110433.jpg", t0 + 30 * day, "2019:12:29 00:30:34.03", null, true),
        Original("iss061e110462.jpg", t0 + 30 * day + 358_810, "2019:12:29 00:36:32.84", null, true),
    )

    /**
     * Copy makers: name, where the result is saved, and the edit. Copies that come back from apps and chats have lost
     * their camera details; the last two are edits saved as copies on the phone, which keep them (and the capture time).
     */
    private class Edit(val name: String, val path: String, val keepsCamera: Boolean = false, val make: (Img) -> Img)

    private val edits = listOf(
        Edit("jpeg q90", "Pictures/") { it.jpeg(90) },
        Edit("jpeg q70", "Pictures/") { it.jpeg(70) },
        Edit("jpeg q50", "Pictures/") { it.jpeg(50) },
        Edit("WhatsApp", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/") { it.longSide(1600).jpeg(75) },
        Edit("resize 1080", "Pictures/") { it.longSide(1080).jpeg(85) },
        Edit("resize 512", "Download/") { it.longSide(512).jpeg(85) },
        Edit("enlarged 2x", "Pictures/") { it.resize(it.w * 2, it.h * 2).jpeg(92) }, // an "enhanced" export: more pixels, no camera details
        Edit("turned 90", "Pictures/") { it.turned().jpeg(92) },
        Edit("mirrored", "Pictures/") { it.mirrored().jpeg(92) },
        Edit("crop 2%", "Pictures/") { it.cropped(0.02f).jpeg(92) },
        Edit("crop 5%", "Pictures/") { it.cropped(0.05f).jpeg(92) },
        Edit("crop 10%", "Pictures/") { it.cropped(0.10f).jpeg(92) },
        Edit("brighter", "Pictures/") { it.brighter(1.15f).jpeg(92) },
        Edit("filter", "Pictures/") { it.filtered().jpeg(92) },
        Edit("sticker", "Pictures/") { it.overlaid().jpeg(92) },
        Edit("screenshot", "Pictures/Screenshots/") { it.screenshot().jpeg(95) },
        Edit("filter, own edit", "DCIM/Camera/", keepsCamera = true) { it.filtered().jpeg(95) },
        Edit("crop 5%, own edit", "DCIM/Camera/", keepsCamera = true) { it.cropped(0.05f).jpeg(95) },
    )

    private class Measured(val item: Item, val shot: Shot, val row: CleanupRow, val turns: LongArray)

    private var lookNanos = 0L // time in lookAt, the phone's measuring after the decode

    /** The library, measured as the phone measures it as each item is made, so only one picture is in memory at a time. */
    private fun library(): List<Measured> {
        val hasher = PdqHasher()
        val out = ArrayList<Measured>()
        fun add(
            img: Img, bytes: ByteArray, origin: String, edit: String, taken: Long, path: String = "DCIM/Camera/", name: String? = null,
            camera: String? = null, scene: String? = null, night: Boolean = false, face: FloatArray? = null,
        ) {
            val id = out.size + 1L
            val item = Item(id, origin, edit, taken, path, name ?: "IMG_$id.jpg", camera, scene, night, face)
            val d = img.decoded()
            val started = System.nanoTime()
            val look = lookAt(d.px, d.w, d.h, isScreenshot(path, item.name), hasher)
            lookNanos += System.nanoTime() - started
            val shot = Shot(id, false, taken, img.w, img.h, bytes.size.toLong(), path.trimEnd('/').substringAfterLast('/'), path, item.name)
            val row = CleanupRow(
                id, 1, shot.size, 1, pdq = pdqHex(look.pdq.hashes), quality = look.pdq.quality, blur = look.blur,
                textured = look.textured, low = look.low, high = look.high, clipped = look.clipped, capture = camera, night = night,
                portrait = false, faces = if (face != null) 1 else 0, faceSharpness = face?.let { faceSharpness(d, it) }, hash = imageHash(bytes),
            )
            out += Measured(item, shot, row, look.pdq.hashes)
        }
        for (o in originals) {
            val bytes = javaClass.getResourceAsStream("/cleanup/${o.file}")!!.readBytes()
            val img = ImageIO.read(bytes.inputStream()).toImg()
            add(img, bytes, o.file, "original", o.taken, camera = o.camera, scene = o.scene, night = o.night, face = o.face)
            // Exact copies: the same file saved twice, and the same pixels with metadata added
            add(img, bytes, o.file, "same file", o.taken + day, path = "Download/", camera = o.camera, night = o.night)
            add(img, withComment(bytes), o.file, "EXIF edit", o.taken + 2 * day, path = "Pictures/", camera = o.camera, night = o.night)
            for (e in edits) {
                val copy = e.make(img)
                val name = if (e.name == "screenshot") "Screenshot_${out.size + 1}.jpg" else null
                // Shared copies are dated when saved; an edit keeps the EXIF capture time, which Android reads as the date taken
                val taken = if (e.keepsCamera) o.taken else o.taken + 3 * day
                add(copy, copy.jpegBytes(92), o.file, e.name, taken, e.path, name, if (e.keepsCamera) o.camera else null, o.scene, o.night)
            }
        }
        // A burst: the bridge a fraction of a second apart, the camera drifting a little. Each frame is its own moment.
        val bridge = load("bridge-1-original.jpg")
        for (k in 1..3) {
            val frame = bridge.cropped(0.01f * k).resize(bridge.w, bridge.h).brighter(1f + 0.01f * k).jpeg(92)
            add(frame, frame.jpegBytes(92), "bridge burst $k", "burst", t0 + 400L * k, camera = "2025:10:09 10:00:0$k.40", scene = "bridge burst")
        }
        // Hard negatives: receipts with one layout minutes apart, pocket shots, washed-out shots, plain skies
        for (k in 1..4L) document(k).let { add(it, it.jpegBytes(90), "document $k", "document", t0 + 40 * day + k * 60_000, camera = "2025:11:01 10:0$k:00.00") }
        for (k in 1..3L) dark(k).let { add(it, it.jpegBytes(90), "dark $k", "dark", t0 + 50 * day + k * 20_000, camera = "2025:11:02 22:00:${10 * k}.00", night = true) }
        for (k in 1..2L) washedOut(k).let { add(it, it.jpegBytes(90), "bright $k", "bright", t0 + 60 * day + k * 20_000, camera = "2025:11:03 12:00:${10 * k}.00") }
        for (k in 1..3L) sky(k).let { add(it, it.jpegBytes(90), "sky $k", "sky", t0 + 70 * day + k * 20_000, camera = "2025:11:04 12:00:${10 * k}.00") }
        return out
    }

    // Sharpness of the face at [f] (shares of the picture), as the closer look measures a face ML Kit found
    private fun faceSharpness(d: Img, f: FloatArray): Float? {
        val luma = FloatArray(d.px.size) { pdqLuma(d.px[it]) }
        return crispness(luma, logOf(luma, d.w, d.h), d.w, (f[0] * d.w).toInt(), (f[1] * d.h).toInt(), (f[2] * d.w).toInt(), (f[3] * d.h).toInt())
    }

    // A JPEG with a comment segment added after its start: different bytes, same pixels (a metadata-only edit)
    private fun withComment(jpeg: ByteArray): ByteArray {
        val comment = "Edited date 2026:01:01".toByteArray()
        val segment = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, (comment.size + 2).toByte()) + comment
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /** The copies the worker would find: each photo against those measured before it, in all eight turns. */
    private fun pairsOf(measured: List<Measured>): List<CopyPair> {
        val index = CopyIndex()
        val pairs = ArrayList<CopyPair>()
        for (m in measured) {
            if ((m.row.quality ?: 0) < MIN_QUALITY) continue
            pairs += index.copiesOf(m.item.id, m.turns)
            index.add(m.item.id, m.turns)
        }
        return pairs
    }

    private val report = StringBuilder()

    private fun say(line: String) {
        println(line)
        report.appendLine(line)
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "n/a" else "%.1f%% (%d/%d)".format(100.0 * n / of, n, of)

    @Test fun evaluate() {
        val started = System.nanoTime()
        val measured = library()
        val byId = measured.associateBy { it.item.id }
        val pairs = pairsOf(measured)
        val result = cleanupOf(measured.map { it.shot }, measured.associate { it.item.id to it.row }, pairs, t0 + 100 * day)
        say("Clean up evaluation: ${measured.size} items from ${originals.size} photos, ${"%.1f".format((System.nanoTime() - started) / 1e9)} s; " +
            "measuring ${"%.0f".format(lookNanos / 1e6 / measured.size)} ms a photo after decoding (this machine)")

        // Exact copies and copies: pairs are (kept, pre-selected). A pre-selected item is right when it shows the same moment
        // as the one kept; it's a lost moment when it isn't a copy of anything (an original, a burst frame, a receipt)
        fun groupPairs(groups: List<ShotGroup>) = groups.flatMap { g -> g.ids.drop(1).map { g.keep to it } }
        val exactPairs = groupPairs(result.exact)
        val copyPairs = groupPairs(result.copies)
        val moments = setOf("original", "burst", "document", "dark", "bright", "sky")
        fun item(id: Long) = byId.getValue(id).item
        val wrongExact = exactPairs.count { (a, b) -> item(a).origin != item(b).origin || item(b).edit !in setOf("original", "same file", "EXIF edit") }
        val crossOrigin = copyPairs.count { (a, b) -> item(a).origin != item(b).origin }
        val lost = (exactPairs + copyPairs).count { (_, b) -> item(b).edit in moments && item(b).edit != "original" } +
            copyPairs.count { (_, b) -> item(b).edit == "original" }
        say("")
        say("Exact copies: ${exactPairs.size} pre-selected, wrong ${pct(wrongExact, exactPairs.size)}")
        for (edit in listOf("same file", "EXIF edit")) {
            val found = originals.count { o -> exactPairs.any { (a, b) -> item(a).origin == o.file && item(b).edit == edit } }
            say("  $edit: recall ${pct(found, originals.size)}")
        }
        say("Copies: ${copyPairs.size} pre-selected, kept with another photo's copies ${pct(crossOrigin, copyPairs.size)}, " +
            "originals or other moments pre-selected $lost")
        val clear = originals.filter { o -> measured.first { it.item.origin == o.file && it.item.edit == "original" }.row.quality!! >= MIN_QUALITY }
        say("  (originals clear enough to match, PDQ quality >= $MIN_QUALITY: ${clear.size} of ${originals.size}; the rest only match exactly)")
        val selected = (exactPairs + copyPairs).associate { (keep, copy) -> copy to keep }
        val recall = LinkedHashMap<String, Int>()
        for (e in edits) {
            val found = clear.count { o ->
                val copy = measured.first { it.item.origin == o.file && it.item.edit == e.name }.item.id
                selected[copy]?.let { item(it).origin } == o.file
            }
            recall[e.name] = found
            val d = clear.map { o ->
                val a = measured.first { it.item.origin == o.file && it.item.edit == "original" }
                val b = measured.first { it.item.origin == o.file && it.item.edit == e.name }
                (0 until 8).minOf { t -> pdqDistance(a.turns, 0, b.turns, t * PDQ_LONGS) }
            }
            say("  %-18s pre-selected %s, PDQ distance %s".format(e.name, pct(found, clear.size), d.joinToString(" ")))
        }

        // Similar shots: pairs inside groups. Right when both show one scene (a burst, a time-lapse) or one moment
        // (copies too far apart for Copies, such as bigger crops, may group here: nothing is pre-selected)
        val similarPairs = result.similar.flatMap { g -> g.ids.flatMap { a -> g.ids.filter { it > a }.map { a to it } } }
        fun alike(a: Long, b: Long) = item(a).origin == item(b).origin || item(a).scene != null && item(a).scene == item(b).scene
        val trueSimilar = similarPairs.count { (a, b) -> alike(a, b) }
        val scenes = measured.filter { it.item.scene != null && it.item.edit in setOf("original", "burst") }.groupBy { it.item.scene!! }
        val sceneTruth = scenes.values.sumOf { it.size * (it.size - 1) / 2 }
        val shots = scenes.values.flatten().mapTo(HashSet()) { it.item.id }
        val sceneFound = similarPairs.count { (a, b) -> a in shots && b in shots && item(a).scene == item(b).scene }
        say("")
        say("Similar shots: ${result.similar.size} groups ${result.similar.map { g -> g.ids.map { item(it).let { i -> "${i.origin.take(14)}/${i.edit}" } } }}")
        say("  pair precision ${pct(trueSimilar, similarPairs.size)}; bursts and time-lapses grouped ${pct(sceneFound, sceneTruth)}")
        for ((scene, shots) in scenes) {
            val d = shots.flatMap { a -> shots.filter { it.item.id > a.item.id }.map { b -> pdqDistance(a.turns, b.turns) } }
            say("  $scene: PDQ distances between its shots ${d.joinToString(" ")}")
        }
        fun twin(prefix: String) = measured.filter { it.item.origin.startsWith(prefix) && it.item.edit == "original" }
        val reframed = twin("KSC")
        val room = twin("NHQ")
        val night = twin("iss061")
        val docs = measured.filter { it.item.edit == "document" }
        say("  hard negatives, PDQ distance (quality): reframed 7 s apart ${pdqDistance(reframed[0].turns, reframed[1].turns)}, " +
            "same room 20 s apart ${pdqDistance(room[0].turns, room[1].turns)} (${room.map { it.row.quality }}), " +
            "night shots ${pdqDistance(night[0].turns, night[1].turns)} (${night.map { it.row.quality }}), " +
            "receipts ${docs.flatMap { a -> docs.filter { it.item.id > a.item.id }.map { pdqDistance(a.turns, it.turns) } }} (${docs.map { it.row.quality }})")

        // Blur: originals and copies are sharp; Gaussian, defocus and shake versions are blurred; night, bokeh, receipts and skies are hard negatives
        val hasher = PdqHasher()
        fun blurRow(img: Img, night: Boolean = false, face: FloatArray? = null): CleanupRow {
            val d = img.decoded()
            val look = lookAt(d.px, d.w, d.h, false, hasher)
            return CleanupRow(0, 0, 0, 0, blur = look.blur, textured = look.textured, night = night, portrait = false,
                faces = if (face != null) 1 else 0, faceSharpness = face?.let { faceSharpness(d, it) })
        }
        val blurCases = ArrayList<Triple<String, Boolean, CleanupRow>>() // what, blurred for real, measured
        for (o in originals) {
            val img = load(o.file).decoded()
            blurCases += Triple("${o.file.take(18)} sharp", false, blurRow(img, o.night, o.face))
            blurCases += Triple("${o.file.take(18)} jpeg q50", false, blurRow(img.jpeg(50), o.night, o.face))
            blurCases += Triple("${o.file.take(18)} 512 px", false, blurRow(img.longSide(512), o.night, o.face))
            blurCases += Triple("${o.file.take(18)} bokeh", false, blurRow(img.bokeh(), o.night, o.face))
            for ((what, blurred) in listOf(
                "gauss 1.5" to img.gaussian(1.5f), "gauss 3" to img.gaussian(3f), "defocus 3" to img.defocus(3),
                "defocus 6" to img.defocus(6), "shake 9" to img.motion(9), "shake 15" to img.motion(15),
            )) blurCases += Triple("${o.file.take(18)} $what", true, blurRow(blurred, o.night, o.face))
        }
        for (k in 1..2L) blurCases += Triple("receipt $k", false, blurRow(document(k)))
        for (k in 1..2L) blurCases += Triple("sky $k", false, blurRow(sky(k)))
        val flagged = blurCases.filter { isBlurry(it.third) }
        val hits = flagged.count { it.second }
        say("")
        say("Blurry: precision ${pct(hits, flagged.size)}, recall ${pct(hits, blurCases.count { it.second })}")
        for (kind in listOf("sharp", "jpeg q50", "512 px", "bokeh", "gauss 1.5", "gauss 3", "defocus 3", "defocus 6", "shake 9", "shake 15")) {
            val cases = blurCases.filter { it.first.endsWith(kind) }
            say("  %-10s flagged %d of %d, scores %s".format(kind, cases.count { isBlurry(it.third) }, cases.size,
                cases.joinToString(" ") { c -> c.third.blur?.let { "%.3f".format(it) } ?: "-" }))
        }
        val faces = blurCases.filter { it.third.faceSharpness != null }
        say("  face sharpness on NHQ shots (sharp, q50, 512, bokeh): ${faces.joinToString(" ") { "%.3f".format(it.third.faceSharpness) }}")
        val negatives = blurCases.filter { it.first.startsWith("receipt") || it.first.startsWith("sky") }
        say("  receipts and skies flagged ${negatives.count { isBlurry(it.third) }} of ${negatives.size} (textured ${negatives.joinToString(" ") { "%.2f".format(it.third.textured) }})")

        // Too dark or bright
        val exposure = measured.filter { it.item.edit in setOf("original", "dark", "bright", "document", "sky") }
        val darkFound = exposure.filter { isDark(it.row) }.map { it.item.edit }
        val brightFound = exposure.filter { isBright(it.row) }.map { it.item.edit }
        say("")
        say("Too dark: flagged ${darkFound.count { it == "dark" }} of 3 pocket shots, ${darkFound.count { it != "dark" }} others; brightest 1%: " +
            exposure.joinToString(" ") { "${it.item.edit.take(4)}=${it.row.high}" })
        say("Too bright: flagged ${brightFound.count { it == "bright" }} of 2 washed-out shots, ${brightFound.count { it != "bright" }} others; darkest 1%: " +
            exposure.joinToString(" ") { "${it.item.edit.take(4)}=${it.row.low}" })

        File("build/reports").mkdirs()
        File("build/reports/cleanup-eval.txt").writeText(report.toString())

        // Floors: never a wrong pre-selection on this set, and the copies Meta's threshold is meant to catch
        assertEquals(0, wrongExact)
        assertEquals(0, crossOrigin)
        assertEquals(0, lost)
        assertEquals(originals.size, originals.count { o -> exactPairs.any { (a, b) -> item(a).origin == o.file && item(b).edit == "EXIF edit" } })
        for (e in listOf("jpeg q90", "jpeg q70", "jpeg q50", "WhatsApp", "resize 1080", "resize 512", "enlarged 2x", "turned 90", "mirrored", "brighter", "screenshot")) {
            assertEquals("copies after $e", clear.size, recall[e])
        }
        // Edits saved as copies keep camera details: never pre-selected, shown as similar shots instead
        assertEquals(0, recall["filter, own edit"])
        val edited = measured.filter { it.item.edit == "filter, own edit" && it.item.origin in clear.map { o -> o.file } }.map { it.item.id }
        assertTrue(edited.all { id -> result.similar.any { id in it.ids } })
        assertEquals(similarPairs.size, trueSimilar)
        assertEquals(sceneTruth, sceneFound)
        assertTrue(flagged.all { it.second })
    }

    /** PDQ distances between the fixtures as they are: a change in decoding or hashing shows up here first. */
    @Test fun pdqDistancesBetweenFixturesStay() {
        val hasher = PdqHasher()
        fun hash(file: String) = load(file).decoded().let { lookAt(it.px, it.w, it.h, false, hasher).pdq }
        val pairs = listOf(
            "iss040e108044.jpg" to "iss040e108050.jpg", // a time-lapse, 42 s apart
            "KSC-20190913-PH_KLS01_0030.jpg" to "KSC-20190913-PH_KLS01_0035.jpg", // reframed, 7 s apart
            "NHQ202403030019.jpg" to "NHQ202403030020.jpg", // same room, different people
            "iss061e110433.jpg" to "iss061e110462.jpg", // night, six minutes apart
            "bridge-1-original.jpg" to "KSC-20190913-PH_KLS01_0030.jpg", // unrelated
        )
        val distances = pairs.map { (a, b) -> pdqDistance(hash(a).original, hash(b).original) }
        println("fixture distances: $distances")
        assertEquals(EXPECTED_DISTANCES, distances)
    }

    private companion object {
        // Filled in from the first run; they only change when measuring does (bump CLEANUP_VERSION then)
        val EXPECTED_DISTANCES = listOf(52, 114, 126, 106, 122)
    }
}
