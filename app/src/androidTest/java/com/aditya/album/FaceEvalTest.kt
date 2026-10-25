package com.aditya.album

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Measures People on labelled photos with the real pipeline: FaceAnalyser (decode, YuNet, cut from the full-size photo,
 * check, quality gates, EdgeFace-S) and then cluster() in the same batches People.run uses. Who is in each photo comes
 * from its name (see [truthOf]). Reports pairwise and B-cubed precision and recall, people split over several groups,
 * groups mixing people, strangers in people's groups and the "Same person?" suggestions, to logcat (tag FaceEval) and
 * files/face-eval.txt. files/faces.tsv keeps every face (landmarks and embedding included) for tuning and for
 * tools/face_reference.py, which checks this pipeline against the reference one. Skipped when the folder is missing.
 * tools/face_eval.py fetches the photos (public domain, never committed) and says how to run it: as its own package
 * next to the installed release build, which has another signature. `-e faces Pictures/Other/` measures another folder.
 */
@RunWith(AndroidJUnit4::class)
class FaceEvalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Photo(val id: Long, val name: String)

    private class Labelled(val face: FaceVector, val truth: String, val file: String, val found: FoundFace)

    @Test fun peopleTest() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, READ_MEDIA_IMAGES)
        val folder = InstrumentationRegistry.getArguments().getString("faces") ?: "Pictures/PeopleEval/"
        val photos = photosIn(folder)
        assumeTrue("no photos in $folder", photos.isNotEmpty())

        val faces = ArrayList<Labelled>()
        var unreadable = 0
        val started = System.currentTimeMillis()
        val dropped = FaceAnalyser(context).use { analyser ->
            for (p in photos) { // newest first, as People.run scans, so face ids come out in the same order
                val found = analyser.analyse(mediaUri(p.id, isVideo = false)) ?: run { unreadable++; emptyList() }
                val truth = truthOf(p.name, found.map { it.box })
                found.forEachIndexed { i, f ->
                    faces += Labelled(FaceVector(faces.size + 1L, p.id, f.box, f.embedding, 0, f.weak), truth[i] ?: "$STRANGER ${p.name} #$i", p.name, f)
                }
            }
            analyser.dropped
        }
        val ms = (System.currentTimeMillis() - started) / photos.size

        // People.run re-clusters every CLUSTER_EVERY batches of BATCH photos: 128 photos at a time
        var placed = emptyMap<Long, Placed>()
        var nextAuto = AUTO_CLUSTERS
        var suggestions = emptyList<Suggestion>()
        val none = Corrections(emptySet(), emptyList(), emptyMap())
        val byPhoto = faces.groupBy { it.face.mediaId }
        val clusterMs = ArrayList<Long>()
        for (chunk in photos.chunked(128).runningReduce { a, b -> a + b }) {
            val vectors = chunk.flatMap { byPhoto[it.id].orEmpty() }.map { l ->
                val p = placed[l.face.id]
                FaceVector(l.face.id, l.face.mediaId, l.face.box, l.face.embedding, p?.cluster ?: 0, l.face.weak, p?.fit ?: 0f)
            }
            val t = System.currentTimeMillis()
            val result = cluster(vectors, none, nextAuto)
            clusterMs += System.currentTimeMillis() - t
            placed = placed + result.changed
            nextAuto = result.nextAuto
            suggestions = result.suggestions
        }
        val clusterOf = faces.associate { it.face.id to (placed[it.face.id]?.cluster ?: 0L) }
        // Faces left out (unsure) each stand alone, as People shows them
        val evaluated = faces.mapIndexed { i, l -> clusterOf.getValue(l.face.id).takeIf { it > 0 } ?: -(i + 1L) }

        val report = buildString {
            appendLine("FaceEval $folder: ${photos.size} photos, $ms ms each, ${faces.size} faces kept (${faces.count { it.face.weak }} weak), dropped $dropped, $unreadable unreadable, clustering ${clusterMs.joinToString("+")} ms")
            appendLine(evaluate(faces.map { it.truth }, evaluated, faces.map { it.face.embedding }))
            val members = faces.indices.groupBy { evaluated[it] }
            for ((cluster, ids) in members.entries.filter { it.key > 0 }.sortedByDescending { it.value.size }) {
                val shown = if (ids.size >= MIN_FACES) "shown" else "hidden"
                appendLine("  group ${cluster - AUTO_CLUSTERS} ($shown): " + ids.groupingBy { faces[it].truth }.eachCount().entries.joinToString { "${it.key} x${it.value}" })
            }
            val unsure = faces.filter { clusterOf.getValue(it.face.id) <= 0 }
            appendLine("  unsure: ${unsure.size} faces, ${unsure.count { !it.truth.startsWith(STRANGER) }} of them known: " +
                unsure.filter { !it.truth.startsWith(STRANGER) }.groupingBy { it.truth }.eachCount().entries.joinToString { "${it.key} x${it.value}" })
            fun who(group: Long) = faces.filter { clusterOf.getValue(it.face.id) == group }.groupingBy { it.truth.substringBefore(" #") }.eachCount().entries.joinToString { "${it.key} x${it.value}" }
            for (s in suggestions) appendLine("  same person? %.3f: %s | %s".format(s.score, who(s.a), who(s.b)))
            val missing = photos.filter { p -> !p.name.startsWith("x_") && faces.none { it.file == p.name && !it.truth.startsWith(STRANGER) } }
            if (missing.isNotEmpty()) appendLine("  no face kept: " + missing.joinToString { it.name })
        }
        report.lines().forEach { Log.i("FaceEval", it) }
        val out = context.getExternalFilesDir(null)!!
        File(out, "face-eval.txt").writeText(report)
        File(out, "faces.tsv").printWriter().use { w ->
            for (f in faces) w.println(
                listOf(
                    f.file, f.truth, f.found.quality, f.face.weak, clusterOf.getValue(f.face.id), f.face.box.run { "$left,$top,$right,$bottom" },
                    f.found.landmarks.joinToString(","), f.face.embedding.joinToString(","),
                ).joinToString("\t"),
            )
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

/** A marked face whose box misses the marked point is the nearest one, but only this close (a fraction of the width). */
private const val NEAR = 0.15f

/**
 * Who each of a photo's faces is, from the photo's [name], or null for a stranger. "Person_Name_N": the biggest face is
 * Person Name. "Person_Name_N@62+Other_Name@25": the biggest face across the point 62% of the way across is Person Name,
 * the one across 25% Other Name (or the nearest within [NEAR], since the marks are rough; further away, the marked face
 * was dropped and the label goes to nobody). "x_N": nobody known (a crowd).
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
        fun distance(i: Int) = abs((boxes[i].left + boxes[i].right) / 2 - x)
        (free.filter { x in boxes[it].left..boxes[it].right }.maxByOrNull { boxes[it].right - boxes[it].left }
            ?: free.filter { distance(it) <= NEAR }.minByOrNull(::distance))?.let { truth[it] = person }
    }
    return truth.toList()
}

/**
 * Pairwise precision (faces grouped together that are one person) and recall (faces of one person that were grouped
 * together), B-cubed precision and recall, people whose faces sit in more than one shown group, shown groups holding
 * more than one person, and how far apart the embeddings are: same-person cosine (low end) against different-people
 * cosine (high end). [clusters] must give every face left out its own id.
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
    val lost = named.count { it !in shown } // in a group too small to show, or left out
    fun pct(sorted: List<Float>, p: Double) = if (sorted.isEmpty()) Float.NaN else sorted[((sorted.size - 1) * p).toInt()]
    same.sort()
    different.sort()
    // B-cubed over the known faces, as People shows them: a face outside a shown group stands alone
    val shownGroup = truth.indices.map { if (it in shown) clusters[it] else Long.MIN_VALUE + it }
    val members = truth.indices.groupBy { shownGroup[it] }
    val total = named.groupingBy { truth[it] }.eachCount()
    val bPrecision = named.map { i -> members.getValue(shownGroup[i]).count { truth[it] == truth[i] }.toDouble() / members.getValue(shownGroup[i]).size }.average()
    val bRecall = named.map { i -> members.getValue(shownGroup[i]).count { truth[it] == truth[i] }.toDouble() / total.getValue(truth[i]) }.average()
    return "precision %.3f recall %.3f | B-cubed precision %.3f recall %.3f | people %d, split %d, groups shown %d, mixed %d, strangers in people %d, known faces not in a shown group %d of %d".format(
        tp / (tp + fp).coerceAtLeast(1).toDouble(), tp / (tp + fn).coerceAtLeast(1).toDouble(), bPrecision, bRecall,
        named.map { truth[it] }.distinct().size, split, groups.size, mixed, strangersIn, lost, named.size,
    ) + "\ncosine same person: min %.3f p10 %.3f median %.3f | different: median %.3f p90 %.3f p99 %.3f max %.3f".format(
        pct(same, 0.0), pct(same, 0.1), pct(same, 0.5), pct(different, 0.5), pct(different, 0.9), pct(different, 0.99), pct(different, 1.0),
    )
}
