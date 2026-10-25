package com.aditya.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How far the background jobs that go through the whole library have got: places and things, people, and later scans
 * such as clean-up. Each job reports itself, from any thread; [IndexingCard] shows them as one while any runs.
 */
object IndexStatus {
    private val jobs = MutableStateFlow<Map<String, IndexProgress>>(emptyMap())

    /** Running jobs by name. Empty when nothing is running. */
    val running: StateFlow<Map<String, IndexProgress>> = jobs.asStateFlow()

    /**
     * [job] has gone through [done] of its [total] items. [job] says what it organises, in sentence case ("People",
     * "Places and things"). Reaching [total], or nothing to do, takes it off the card: report (0, 0) when stopping early.
     */
    fun report(job: String, done: Int, total: Int) = jobs.update { if (done >= total) it - job else it + (job to IndexProgress(done, total)) }
}

/** The card's meta line, e.g. "1,234 of 3,490 · People, places and things": all jobs' items together, names A to Z. */
fun indexingMeta(jobs: Map<String, IndexProgress>): String {
    val names = jobs.keys.sorted().mapIndexed { i, name -> if (i == 0) name else name.replaceFirstChar { it.lowercase() } }
    return "%,d of %,d · %s".format(jobs.values.sumOf { it.done }, jobs.values.sumOf { it.total }, names.joinToString(", "))
}

/**
 * "Organising your photos" with what's being organised and a progress line, while any [IndexStatus] job runs. It slides
 * open and closed, or just appears and goes with Remove animations on; the line itself always moves, as it's information.
 */
@Composable
fun IndexingCard(modifier: Modifier = Modifier) {
    val jobs by IndexStatus.running.collectAsState()
    // The last numbers stay while the card leaves, so it doesn't empty out as it closes
    var last by remember { mutableStateOf(jobs) }
    SideEffect { if (jobs.isNotEmpty()) last = jobs }
    val now = jobs.ifEmpty { last }
    val still = AlbumTheme.spec.still
    val c = AlbumTheme.colors
    AnimatedVisibility(
        jobs.isNotEmpty(),
        modifier,
        enter = if (still) fadeIn(tween(0)) else fadeIn(tween(180)) + expandVertically(tween(220)),
        exit = if (still) fadeOut(tween(0)) else fadeOut(tween(150)) + shrinkVertically(tween(220)),
    ) {
        val total = now.values.sumOf { it.total }
        Column(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Space.radiusMd)).semantics(mergeDescendants = true) {}.padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Text("Organising your photos", style = AlbumTheme.type.label, color = c.ink)
            Text(indexingMeta(now), style = AlbumTheme.type.meta, color = c.inkMuted)
            ProgressLine(if (total == 0) 0f else now.values.sumOf { it.done } / total.toFloat(), Modifier.padding(top = Space.s1))
        }
    }
}
