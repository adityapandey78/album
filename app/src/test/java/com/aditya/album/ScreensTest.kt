package com.aditya.album

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** The screen stack's bookkeeping: which screens are drawn, which are going, and how each came in. Animations never run here. */
class ScreensTest {
    // A scope whose coroutines never start: only what push, pop and match do at once is checked
    private val idle = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    })
    private val card = Rect(10f, 20f, 110f, 120f)

    @Test fun pushedScreensSlideGrowOrFade() {
        val s = Screens(idle, emptyList())
        s.push(Route.Settings, null, still = false)
        s.push(Route.Open(1), card, still = false)
        s.push(Route.Search, card, still = false) // search always fades, even from a card
        assertEquals(listOf(Entry.Slide, Entry.Grow, Entry.Fade), s.layers.map { it.entry })
        assertTrue(s.tabs.slides) // under a screen that slid in, the tabs slip left
        assertTrue(!s.layers[0].cover.slides) // under one that grew, Settings only dims
        assertEquals(Entry.Fade, Screens(idle, emptyList()).apply { push(Route.Settings, null, still = true) }.layers[0].entry)
    }

    @Test fun popMovesTheTopScreenOutUntilItHasGone() {
        val s = Screens(idle, listOf(Route.Settings))
        s.push(Route.About, null, still = false)
        s.pop(still = false)
        assertEquals(listOf<Route>(Route.Settings), s.layers.map { it.route })
        assertEquals(listOf<Route>(Route.About), s.leaving.map { it.route })
    }

    @Test fun forwardAgainWhileGoingBringsTheSameScreenBack() {
        val s = Screens(idle, listOf(Route.Settings))
        s.push(Route.About, null, still = false)
        val about = s.layers.last()
        s.pop(still = false)
        s.push(Route.About, null, still = false)
        assertSame(about, s.layers.last()) // same layer, so the same composition and its state
        assertTrue(s.leaving.isEmpty())
    }

    @Test fun aRouteIsNeverDrawnTwice() {
        // Album 1, album 2, album 1 again: popping twice fast would draw album 1 going and album 1 coming back
        val s = Screens(idle, listOf(Route.Open(1), Route.Open(2), Route.Open(1)))
        s.pop(still = false)
        assertTrue(s.leaving.none { it.route == Route.Open(1) })
    }

    @Test fun otherChangesSwapAtOnceKeepingScreensInPlace() {
        val s = Screens(idle, listOf(Route.Settings, Route.Open(1)))
        val settings = s.layers[0]
        s.match(listOf(Route.Settings, Route.Open(2))) // a merged person replaces the one open
        assertSame(settings, s.layers[0])
        assertEquals(listOf(Route.Settings, Route.Open(2)), s.layers.map { it.route })
        s.match(emptyList())
        assertTrue(s.layers.isEmpty())
    }
}
