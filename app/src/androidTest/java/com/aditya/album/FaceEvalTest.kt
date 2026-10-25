package com.aditya.album

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Measures People on labelled photos with the real pipeline: FaceAnalyser (decode, ML Kit, alignment, quality gates,
 * SFace) and then cluster() in the same batches People.run uses. Who is in each photo comes from its name (see
 * [truthOf]). Reports pairwise precision and recall, people split over several groups and groups mixing people, to
 * logcat (tag FaceEval) and files/face-eval.txt; files/faces.tsv keeps the embeddings for tuning. Skipped when the
 * folder is missing. tools/face_eval.py fetches the photos (public domain, never committed) and says how to run it:
 * as its own package next to the installed release build, which has another signature. `-e faces Pictures/Other/`
 * measures another folder.
 */
@RunWith(AndroidJUnit4::class)
class FaceEvalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Photo(val id: Long, val name: String)

    private class Labelled(val face: FaceVector, val truth: String, val file: String, val quality: Float)

    @Test fun peopleTest() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, READ_MEDIA_IMAGES)
        val folder = InstrumentationRegistry.getArguments().getString("faces") ?: "Pictures/PeopleEval/"
        val photos = photosIn(folder)
        assumeTrue("no photos in $folder", photos.isNotEmpty())

        val thread = Executors.newSingleThreadExecutor()
        val faces = ArrayList<Labelled>()
        var unreadable = 0
        val started = System.currentTimeMillis()
        val dropped = FaceAnalyser(context, thread).use { analyser ->
            for (p in photos) { // newest first, as People.run scans, so face ids come out in the same order
                val found = analyser.analyse(mediaUri(p.id, isVideo = false)) ?: run { unreadable++; emptyList() }
                val truth = truthOf(p.name, found.map { it.box })
                found.forEachIndexed { i, f ->
                    faces += Labelled(FaceVector(faces.size + 1L, p.id, f.box, f.embedding, 0), truth[i] ?: "$STRANGER ${p.name} #$i", p.name, f.quality)
                }
            }
            analyser.dropped
        }
        thread.shutdown()
        val ms = (System.currentTimeMillis() - started) / photos.size

        // People.run re-clusters every CLUSTER_EVERY batches of BATCH photos: 128 photos at a time
        var clusters = emptyMap<Long, Long>()
        var nextAuto = AUTO_CLUSTERS
        val none = Corrections(emptyMap(), emptyList(), emptyMap())
        val byPhoto = faces.groupBy { it.face.mediaId }
        for (chunk in photos.chunked(128).runningReduce { a, b -> a + b }) {
            val vectors = chunk.flatMap { byPhoto[it.id].orEmpty() }.map { FaceVector(it.face.id, it.face.mediaId, it.face.box, it.face.embedding, clusters[it.face.id] ?: 0) }
            val result = cluster(vectors, none, nextAuto = nextAuto)
            clusters = vectors.associate { it.id to (result.changed[it.id] ?: it.cluster) }
            nextAuto = result.nextAuto
        }

        val report = buildString {
            appendLine("FaceEval $folder: ${photos.size} photos, $ms ms each, ${faces.size} faces kept, dropped $dropped, $unreadable unreadable")
            appendLine(evaluate(faces.map { it.truth }, faces.map { clusters.getValue(it.face.id) }, faces.map { it.face.embedding }))
            for ((cluster, members) in faces.groupBy { clusters.getValue(it.face.id) }.entries.sortedByDescending { it.value.size }) {
                val shown = if (members.size >= MIN_FACES) "shown" else "hidden"
                appendLine("  group ${cluster - AUTO_CLUSTERS} ($shown): " + members.groupingBy { it.truth }.eachCount().entries.joinToString { "${it.key} x${it.value}" })
            }
            val missing = photos.filter { p -> !p.name.startsWith("x_") && faces.none { it.file == p.name && !it.truth.startsWith(STRANGER) } }
            if (missing.isNotEmpty()) appendLine("  no face kept: " + missing.joinToString { it.name })
        }
        report.lines().forEach { Log.i("FaceEval", it) }
        val out = context.getExternalFilesDir(null)!!
        File(out, "face-eval.txt").writeText(report)
        File(out, "faces.tsv").printWriter().use { w ->
            for (f in faces) w.println("${f.file}\t${f.truth}\t${f.quality}\t${f.face.box.run { "$left,$top,$right,$bottom" }}\t${f.face.embedding.joinToString(",")}")
        }
    }

    private fun photosIn(folder: String): List<Photo> {
        val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf(folder), "${MediaStore.Images.Media._ID} DESC")
            ?.use { c -> List(c.count) { c.moveToNext(); Photo(c.getLong(0), c.getString(1).substringBeforeLast('.')) } }
            .orEmpty()
    }
}

private const val STRANGER = "stranger"

/**
 * Who each of a photo's faces is, from the photo's [name], or null for a stranger. "Person_Name_N": the biggest face is
 * Person Name. "Person_Name_N@62+Other_Name@25": the biggest face across the point 62% of the way across is Person Name,
 * the one across 25% Other Name. "x_N": nobody known (a crowd).
 */
fun truthOf(name: String, boxes: List<FaceBox>): List<String?> {
    val truth = arrayOfNulls<String>(boxes.size)
    if (name.startsWith("x_") || boxes.isEmpty()) return truth.toList()
    val parts = name.split('+')
    if (parts.size == 1 && '@' !in name) {
        truth[boxes.indices.maxBy { (boxes[it].right - boxes[it].left) * (boxes[it].bottom - boxes[it].top) }] = name.substringBeforeLast('_')
        return truth.toList()
    }
    parts.forEachIndexed { i, part ->
        val person = part.substringBefore('@').let { if (i == 0) it.substringBeforeLast('_') else it }
        val x = part.substringAfter('@').toFloat() / 100
        // The biggest face across that point (not a small one in the background), else the nearest
        val free = boxes.indices.filter { truth[it] == null }
        (free.filter { x in boxes[it].left..boxes[it].right }.maxByOrNull { boxes[it].right - boxes[it].left }
            ?: free.minByOrNull { abs((boxes[it].left + boxes[it].right) / 2 - x) })?.let { truth[it] = person }
    }
    return truth.toList()
}

/**
 * Pairwise precision (faces grouped together that are one person) and recall (faces of one person that were grouped
 * together), people whose faces sit in more than one shown group, shown groups holding more than one person, and how
 * far apart the embeddings are: same-person cosine (low end) against different-people cosine (high end).
 */
fun evaluate(truth: List<String>, clusters: List<Long>, embeddings: List<FloatArray>): String {
    var tp = 0
    var fp = 0
    var fn = 0
    val same = ArrayList<Float>()
    val different = ArrayList<Float>()
    for (i in truth.indices) for (j in i + 1 until truth.size) {
        val isSame = truth[i] == truth[j]
        val together = clusters[i] == clusters[j]
        val strangers = truth[i].startsWith(STRANGER) && truth[j].startsWith(STRANGER) // may be one person seen twice: not counted
        when {
            isSame && together -> tp++
            together && !strangers -> fp++
            isSame -> fn++
        }
        val s = dot(embeddings[i], embeddings[j])
        if (isSame) same += s else if (!truth[i].startsWith(STRANGER) && !truth[j].startsWith(STRANGER)) different += s
    }
    val size = clusters.groupingBy { it }.eachCount()
    val shown = truth.indices.filter { size.getValue(clusters[it]) >= MIN_FACES }
    val named = truth.indices.filter { !truth[it].startsWith(STRANGER) }
    val split = named.groupBy { truth[it] }.count { (_, faces) -> faces.filter { it in shown }.map { clusters[it] }.distinct().size > 1 }
    val groups = shown.groupBy { clusters[it] }.values
    val mixed = groups.count { faces -> faces.filter { it in named }.map { truth[it] }.distinct().size > 1 }
    val strangersIn = groups.filter { faces -> faces.any { it in named } }.sumOf { faces -> faces.count { it !in named } }
    val lost = named.count { it !in shown } // in a group too small to show
    fun pct(sorted: List<Float>, p: Double) = if (sorted.isEmpty()) Float.NaN else sorted[((sorted.size - 1) * p).toInt()]
    same.sort()
    different.sort()
    return "precision %.3f recall %.3f | people %d, split %d, groups shown %d, mixed %d, strangers in people %d, known faces in hidden groups %d of %d".format(
        tp / (tp + fp).coerceAtLeast(1).toDouble(), tp / (tp + fn).coerceAtLeast(1).toDouble(),
        named.map { truth[it] }.distinct().size, split, groups.size, mixed, strangersIn, lost, named.size,
    ) + "\ncosine same person: min %.3f p10 %.3f median %.3f | different: median %.3f p90 %.3f p99 %.3f max %.3f".format(
        pct(same, 0.0), pct(same, 0.1), pct(same, 0.5), pct(different, 0.5), pct(different, 0.9), pct(different, 0.99), pct(different, 1.0),
    )
}
