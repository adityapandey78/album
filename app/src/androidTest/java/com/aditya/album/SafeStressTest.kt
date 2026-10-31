package com.aditya.album

import android.graphics.Bitmap
import android.os.Debug
import android.os.StatFs
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * How many items Private safe holds and how slow it gets: the real engine (SafeCipher, Safe.store's sealed move in,
 * SafeDb, Safe.thumbnail and Safe.photo, the grid) at 1,000 to 20,000 items, in the app's own sandbox. Only with
 * `-e stress 1`, and only in a build with its own package (tools/eval.init.gradle), never next to a real safe:
 *
 *     ./gradlew -I tools/eval.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
 *     adb install -r -g app/build/outputs/apk/debug/app-debug.apk
 *     adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell am instrument -w -e stress 1 -e class com.aditya.album.SafeStressTest com.aditya.album.eval.test/androidx.test.runner.AndroidJUnitRunner
 *     adb shell cat /sdcard/Android/data/com.aditya.album.eval/files/safe-stress.txt
 *
 * Keeps 2 GB of the phone free and stops early when it can't; deletes everything it made at the end.
 */
@RunWith(AndroidJUnit4::class)
class SafeStressTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "safe-stress.txt").apply { writeText("") } }
    private val minFree = (InstrumentationRegistry.getArguments().getString("minFreeMb")?.toLong() ?: 2048L) shl 20
    private val sizes = (InstrumentationRegistry.getArguments().getString("sizes") ?: "1000,5000,10000,20000").split(',').map { it.trim().toInt() }
    private val videos = (InstrumentationRegistry.getArguments().getString("videosMb") ?: "200,500,1024").split(",").mapNotNull { it.trim().toLongOrNull()?.takeIf { mb -> mb > 0 } }
    private val loads = Dispatchers.IO.limitedParallelism(4) // the grid's

    private fun log(line: String) {
        Log.i("SafeStress", line)
        out.appendText(line + "\n")
    }

    private fun free() = StatFs(Safe.dir.path).availableBytes
    private fun pssMb() = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
    private fun heapMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }
    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun f(x: Double) = "%.1f".format(x)
    private fun dirBytes(suffix: String) = Safe.dir.listFiles { f -> f.name.endsWith(suffix) }.orEmpty().sumOf { it.length() }

    /** An image like a camera's, [w] x [h], with a gradient and [noise] (0 to 255) of grain: more grain, bigger JPEG. */
    private fun picture(w: Int, h: Int, noise: Int, seed: Int): Bitmap {
        val r = Random(seed)
        val row = IntArray(w)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val g = (x * 255 / w + if (noise > 0) r.nextInt(noise) else 0).coerceAtMost(255)
                    val b = (y * 255 / h + if (noise > 0) r.nextInt(noise) else 0).coerceAtMost(255)
                    row[x] = (0xff shl 24) or (((g + b) / 2) shl 16) or (g shl 8) or b
                }
                setPixels(row, 0, w, 0, y, w, 1)
            }
        }
    }

    private fun jpeg(b: Bitmap, quality: Int) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** [size] bytes of video-like content, the same every time it's opened (the move reads the original twice). */
    private fun stream(size: Long, block: ByteArray) = object : InputStream() {
        var at = 0L
        override fun read(): Int = if (at < size) block[(at++ % block.size).toInt()].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= size) return -1
            val i = (at % block.size).toInt()
            val n = minOf(len.toLong(), size - at, (block.size - i).toLong()).toInt()
            System.arraycopy(block, i, b, off, n)
            at += n
            return n
        }
    }

    private var next = 0
    private val base = System.currentTimeMillis()

    private fun row(name: String, video: Boolean = false, durationMs: Long = 0, taken: Long = base - next * 3_600_000L) = {
        SafeItem(
            name = name, mime = if (video) "video/mp4" else "image/jpeg", isVideo = video, taken = taken,
            width = 4000, height = 3000, durationMs = durationMs, path = "DCIM/Camera/", favorite = false, size = 0,
            sha256 = null, original = "test://stress/${next++}", added = System.currentTimeMillis(),
        )
    }

    /** Safe.moveIn for one item, without MediaStore: the sealed store, then what settle does once the original is gone. */
    private suspend fun moveIn(row: () -> SafeItem, open: () -> InputStream, thumb: () -> Bitmap): SafeItem {
        val item = checkNotNull(Safe.store(row, null, checkNotNull(IdCards.escrowPublic()), open, thumb)) { "move in failed" }
        Safe.dao.moved(item.id)
        return item
    }

    @Test fun stress() = runBlocking {
        assumeTrue("only with -e stress 1", InstrumentationRegistry.getArguments().getString("stress") != null)
        // Never next to a real safe: its own package and an empty safe, or nothing happens
        assertNotEquals("com.aditya.album", context.packageName)
        Safe.init(context)
        Safe.lock()
        assertEquals("the test safe must start empty", 0, Safe.dao.items(SafeItem.SAFE).first().size + Safe.dao.waiting().size)
        val master = ByteArray(32).also(SecureRandom()::nextBytes)
        AtomicFile(File(Safe.dir, IdCards.ESCROW)).let { f ->
            val o = f.startWrite()
            o.write(Escrow.lock(Escrow.newPair(), master))
            f.finishWrite(o)
        }
        log("device ${android.os.Build.MODEL} sdk ${android.os.Build.VERSION.SDK_INT} cpus ${Runtime.getRuntime().availableProcessors()} maxHeap ${Runtime.getRuntime().maxMemory() shr 20} MB free ${free() shr 20} MB")
        var shown by mutableStateOf<Pair<List<SafeItem>, ByteArray>?>(null)
        compose.setContent { AlbumTheme(Appearance()) { shown?.let { (l, k) -> SafeGrid(l, k, emptySet(), {}, {}, 0.dp) } } }
        try {
            run(master) { shown = it }
        } finally {
            withContext(Dispatchers.Main) { shown = null }
            compose.waitForIdle()
            val all = Safe.dao.items(SafeItem.SAFE).first() + Safe.dao.waiting()
            Safe.delete(all).join()
            Safe.lock()
            File(Safe.dir, IdCards.ESCROW).delete()
            val left = Safe.dir.listFiles().orEmpty().filter { it != Safe.idDir }.map { it.name }
            log("cleanup: deleted ${all.size} items, left in safe dir: $left, free ${free() shr 20} MB")
        }
    }

    private suspend fun run(master: ByteArray, show: suspend (Pair<List<SafeItem>, ByteArray>?) -> Unit) {
        fun unlock(): ByteArray {
            Safe.lock()
            Safe.open(master.copyOf())
            return checkNotNull(Safe.key.value)
        }

        // ---------- What one photo costs: 3 MB JPEG (12 MP) ----------
        val photo = jpeg(picture(4000, 3000, 24, 1), 92).let { j ->
            if (j.size >= 3 shl 20) j else j + Random(2).nextBytes((3 shl 20) - j.size) // past the JPEG's end, as camera trailers are
        }
        val tile = picture(512, 384, (InstrumentationRegistry.getArguments().getString("grain") ?: "16").toInt(), 3)
        val tileJpeg = jpeg(tile, 85).size
        log("photo ${photo.size} bytes, thumbnail jpeg $tileJpeg bytes (${tile.width}x${tile.height})")
        val realCount = 40
        val real = ArrayList<SafeItem>()
        var t = System.nanoTime()
        val per = ArrayList<Double>()
        repeat(realCount) { i ->
            val s = System.nanoTime()
            real += moveIn(row("real_$i.jpg", taken = base + 1000L * (realCount - i)), { photo.inputStream() }, { tile.copy(Bitmap.Config.ARGB_8888, false) })
            per += ms(s)
        }
        log("move in 3 MB photo x$realCount: total ${f(ms(t))} ms, per item avg ${f(per.average())} p50 ${f(per.sorted()[per.size / 2])} max ${f(per.max())} ms")
        val enc = real.sumOf { File(Safe.dir, "${it.id}.bin").length() }
        val thumbs = real.sumOf { File(Safe.dir, "${it.id}.thumb").length() }
        log("storage: original ${photo.size.toLong() * realCount} bytes -> .bin $enc (+${f((enc - photo.size.toLong() * realCount) * 100.0 / (photo.size.toLong() * realCount))}%) + .thumb $thumbs (${thumbs / realCount} per item)")
        // Keep 3 real photos (the newest) for the full-photo decode at every size; the rest go now
        Safe.delete(real.drop(3)).join()

        // ---------- Videos ----------
        val block = Random(4).nextBytes(1 shl 20)
        for (mb in videos) {
            val bytes = mb shl 20
            if (free() - bytes - (64L shl 20) < minFree) {
                log("video $mb MB: skipped, would leave less than ${minFree shr 20} MB free (free ${free() shr 20} MB)")
                continue
            }
            val pss0 = pssMb()
            t = System.nanoTime()
            val v = moveIn(row("video_$mb.mp4", video = true, durationMs = 60_000), { stream(bytes, block) }, { picture(512, 288, 40, 5) })
            val moveMs = ms(t)
            val pss1 = pssMb()
            val key = unlock()
            t = System.nanoTime()
            val buffer = ByteArray(256 shl 10)
            val readMs = Safe.reader(v, key).use { r ->
                var at = 0L
                while (true) {
                    val n = r.read(at, buffer, 0, buffer.size)
                    if (n < 0) break
                    at += n
                }
                check(at == bytes)
                ms(t)
            }
            val seeks = Safe.reader(v, key).use { r ->
                val rnd = Random(6)
                val s = System.nanoTime()
                repeat(50) { r.read(rnd.nextLong(bytes), buffer, 0, 4096) }
                ms(s) / 50
            }
            log(
                "video $mb MB: move in ${f(moveMs)} ms (${f(bytes / 1048576.0 / (moveMs / 1000))} MB/s incl. verify), PSS $pss0 -> $pss1 MB, " +
                    ".bin ${File(Safe.dir, "${v.id}.bin").length()} bytes; read all ${f(readMs)} ms (${f(bytes / 1048576.0 / (readMs / 1000))} MB/s), random seek+read ${f(seeks)} ms",
            )
            Safe.delete(listOf(v)).join()
        }

        // ---------- Scale ----------
        val dbFile = context.getDatabasePath("safe.db")
        var have = Safe.dao.count(SafeItem.SAFE)
        log("size | per item move in (bulk, small file) | list one query / pages | sections | unlock->list | first thumb | screen of 40 thumbs | full photo | PSS list / +thumbs / +grid / +scroll | heap | safe.db+wal | .thumb total | escrowPrivate")
        for (target in sizes) {
            // Bulk: the real sealed move in with a small file, so 20,000 fit; the 3 MB cost is above
            val needed = (target - have).toLong() * (tileJpeg + 8192 + 4096)
            if (free() - needed < minFree) {
                log("size $target: stopped, needs ~${needed shr 20} MB and only ${(free() - minFree) shr 20} MB can be used above the ${minFree shr 20} MB floor")
                break
            }
            val small = Random(7).nextBytes(2048)
            t = System.nanoTime()
            val adding = target - have
            var batch = System.nanoTime()
            while (have < target) {
                moveIn(row("bulk_$have.jpg"), { small.inputStream() }, { tile.copy(Bitmap.Config.ARGB_8888, false) })
                have++
                if (have % 1000 == 0) {
                    Log.i("SafeStress", "bulk $have: last 1000 in ${f(ms(batch))} ms, free ${free() shr 20} MB")
                    batch = System.nanoTime()
                }
            }
            val bulkPer = if (adding > 0) ms(t) / adding else 0.0

            // Unlock: lock wipes keys and thumbnails, then the list, sections, thumbnails, a photo
            show(null)
            System.gc()
            val key = unlock()
            t = System.nanoTime()
            val list = Safe.items.first()
            val unlockList = ms(t)
            assertEquals(target, list.size)
            // The list as one query (indexed) against the pages Safe.items reads, median of 3 each
            val cold = List(3) { t = System.nanoTime(); Safe.dao.items(SafeItem.SAFE).first(); ms(t) }.sorted()[1]
            val warm = List(3) { t = System.nanoTime(); Safe.dao.all(SafeItem.SAFE); ms(t) }.sorted()[1]
            // Control: pure CPU (SHA-256 of 15 MB in memory), so a slow phone is told apart from a slow safe
            t = System.nanoTime(); repeat(5) { sha256(photo.inputStream()) }; val control = ms(t)
            // The same query raw, all columns and just the ids, to see what the rows' size costs
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
                // Side by side, three rounds: with the list's index, without it (NOT INDEXED, as before version 4), only the ids
                val rounds = List(3) {
                    listOf("SELECT * FROM items", "SELECT * FROM items NOT INDEXED", "SELECT id, taken FROM items").map { q ->
                        t = System.nanoTime()
                        d.rawQuery("$q WHERE original IS NULL AND bucket = 0 ORDER BY taken DESC", null).use { c -> while (c.moveToNext()) c.getLong(0) }
                        ms(t)
                    }
                }
                List(3) { i -> f(rounds.map { it[i] }.sorted()[1]) }
            }
            log("  $target: control sha ${f(control)} ms; raw query (median of 3) SELECT * ${raw[0]} ms, without the index ${raw[1]} ms, SELECT id,taken ${raw[2]} ms")
            val zone = ZoneId.systemDefault()
            t = System.nanoTime()
            val sec = sections(list, false) { Instant.ofEpochMilli(it.taken).atZone(zone).toLocalDate().toEpochDay() }
            val sectionsMs = ms(t)
            val pssList = pssMb()
            t = System.nanoTime()
            checkNotNull(Safe.thumbnail(list[0], key))
            val firstThumb = ms(t)
            t = System.nanoTime()
            withContext(Dispatchers.Default) { list.subList(1, 41).map { async(loads) { checkNotNull(Safe.thumbnail(it, key)) } }.awaitAll() }
            val screen = ms(t)
            t = System.nanoTime()
            val full = Safe.photo(list[0], key)
            val photoMs = ms(t)
            val pssThumbs = pssMb()
            t = System.nanoTime()
            repeat(20) { IdCards.escrowPrivate(key) }
            val escrowMs = ms(t) / 20

            // The real grid with every item, then ten flings down it
            t = System.nanoTime()
            withContext(Dispatchers.Main) { show(list to key) }
            compose.waitForIdle()
            val gridMs = ms(t)
            Thread.sleep(2000) // tiles decrypt their thumbnails off the main thread
            val pssGrid = pssMb()
            t = System.nanoTime()
            repeat(10) { compose.onRoot().performTouchInput { swipeUp(durationMillis = 120) }; compose.waitForIdle() }
            val flingMs = ms(t)
            Thread.sleep(2000)
            val pssScroll = pssMb()

            val db = dbFile.length() + File(dbFile.path + "-wal").length()
            log(
                "$target | ${f(bulkPer)} ms | ${f(cold)} / ${f(warm)} ms | ${f(sectionsMs)} ms (${sec.size}) | ${f(unlockList)} ms | ${f(firstThumb)} ms | ${f(screen)} ms | " +
                    "${f(photoMs)} ms (${full.width}x${full.height}) | $pssList / $pssThumbs / $pssGrid / $pssScroll MB | ${heapMb()} MB | ${db shr 10} KB | ${dirBytes(".thumb") shr 20} MB | ${f(escrowMs)} ms",
            )
            log("  grid: first composition ${f(gridMs)} ms, 10 flings ${f(flingMs)} ms; free ${free() shr 20} MB")

            // Does a move in get slower as the safe grows? 10 real 3 MB photos into this safe, then out again.
            // Twice: alone, then with the open grid's list collecting, as when Add is used from the open safe.
            for (watched in listOf(false, true)) {
                val emissions = AtomicInteger()
                val watcher = if (watched) CoroutineScope(Dispatchers.Default).launch { Safe.items.collect { emissions.incrementAndGet() } } else null
                if (watched) Thread.sleep(500)
                val moved = ArrayList<SafeItem>()
                t = System.nanoTime()
                repeat(10) { moved += moveIn(row("probe_$it.jpg"), { photo.inputStream() }, { tile.copy(Bitmap.Config.ARGB_8888, false) }) }
                val probe = ms(t) / 10
                Thread.sleep(if (watched) 3000 else 0) // let the list catch up
                watcher?.cancelAndJoin()
                Safe.delete(moved).join()
                log("  move in 3 MB photo at $target${if (watched) " with the list open" else ""}: ${f(probe)} ms per item${if (watched) ", list re-read ${emissions.get() - 1} times for 10 items" else ""}")
            }
            assertTrue(free() > minFree - (64L shl 20))
        }
    }
}
