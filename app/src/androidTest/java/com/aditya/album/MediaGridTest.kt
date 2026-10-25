package com.aditya.album

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MediaGridTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.now().toEpochDay()
    private val media = List(200) { i ->
        Media(
            i.toLong(), Uri.parse("content://test/$i"), false, taken = 0, day = today - i / 6, width = 0, height = 0,
            durationMs = 0, bucketId = 0, bucketName = "", isFavorite = false, size = 0, name = "", path = "",
        )
    }

    @Test fun pinchStepsOneColumnLevelPerGesture() {
        val columns = mutableIntStateOf(DEFAULT_COLUMNS)
        compose.setContent {
            AlbumTheme(Appearance()) {
                MediaGrid(media, columns, rememberLazyGridState(), emptySet(), {}, {}, PaddingValues(), Modifier.testTag("grid"))
            }
        }
        val grid = compose.onNodeWithTag("grid")

        // Fingers apart (zoom in): 4 -> 3, even though the gesture is big enough for two steps
        grid.performTouchInput { pinch(center - Offset(150f, 0f), center - Offset(450f, 0f), center + Offset(150f, 0f), center + Offset(450f, 0f)) }
        compose.runOnIdle { assertEquals(3, columns.intValue) }

        // Fingers together (zoom out) four times: 3 -> 4 -> 5 -> 7 -> month view, which has month headers
        repeat(4) {
            grid.performTouchInput { pinch(center - Offset(450f, 0f), center - Offset(150f, 0f), center + Offset(450f, 0f), center + Offset(150f, 0f)) }
        }
        compose.runOnIdle { assertEquals(MONTH_COLUMNS, columns.intValue) }
        compose.onNodeWithText(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))).assertExists()
    }

    @Test fun showsTodayHeaderAndOpensTappedPhoto() {
        var opened: Media? = null
        compose.setContent {
            AlbumTheme(Appearance()) {
                MediaGrid(media, mutableIntStateOf(DEFAULT_COLUMNS), rememberLazyGridState(), emptySet(), { opened = it }, {}, PaddingValues())
            }
        }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
        compose.onAllNodes(hasClickAction())[0].performClick() // first cell = newest photo
        compose.runOnIdle { assertEquals(0L, opened?.id) }
    }

    @Test fun longPressAndDragSelectsARangeAndLiftingDoesNotTapIt() {
        var selected by mutableStateOf(emptySet<Long>())
        compose.setContent {
            AlbumTheme(Appearance()) {
                MediaGrid(media, mutableIntStateOf(DEFAULT_COLUMNS), rememberLazyGridState(), selected, {}, { selected = it }, PaddingValues(), Modifier.testTag("grid"))
            }
        }
        val origin = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot.topLeft
        fun centreOfCell(i: Int) = compose.onAllNodes(hasClickAction())[i].fetchSemanticsNode().boundsInRoot.center - origin
        // Measured before selecting, while the headers aren't clickable yet. Today holds ids 0..5, four per row.
        val first = centreOfCell(0)
        val third = centreOfCell(2)
        val sixth = centreOfCell(5)

        compose.onNodeWithTag("grid").performTouchInput {
            down(first)
            repeat(10) { moveBy(Offset.Zero, delayMillis = 60) } // hold past the long-press timeout
            moveTo(sixth)
            up()
        }
        compose.runOnIdle { assertEquals((0L..5L).toSet(), selected) }

        // Dragging back from a selected photo clears the range again
        compose.onNodeWithTag("grid").performTouchInput {
            down(sixth)
            repeat(10) { moveBy(Offset.Zero, delayMillis = 60) }
            moveTo(third)
            up()
        }
        compose.runOnIdle { assertEquals(setOf(0L, 1L), selected) }
    }

    @Test fun justifiedRowsOpenTheTappedPhotoAndKnowWhereItIs() {
        val state = LazyGridState()
        var opened: Media? = null
        compose.setContent {
            AlbumTheme(Appearance(gallery = GalleryStyle.Justified)) {
                MediaGrid(media, mutableIntStateOf(DEFAULT_COLUMNS), state, emptySet(), { opened = it }, {}, PaddingValues(), header = { Text("Library") })
            }
        }
        compose.onNodeWithText("Library").assertExists()
        compose.onAllNodes(hasClickAction())[1].performClick() // the second photo, inside the first row
        compose.runOnIdle {
            assertEquals(1L, opened?.id)
            val first = cellBounds(state, 0L)!!
            val second = cellBounds(state, 1L)!!
            assertEquals(first.top, second.top) // same row
            assertTrue(second.left > first.right) // to its right, past the gutter
            assertEquals(null, cellBounds(state, 199L)) // far down, not on screen
        }
    }

    @Test fun calendarJumpScrollsToTheDayAndReportsIt() {
        val state = LazyGridState()
        var jumped = false
        compose.setContent {
            AlbumTheme(Appearance()) {
                MediaGrid(media, mutableIntStateOf(DEFAULT_COLUMNS), state, emptySet(), {}, {}, PaddingValues(), jumpTo = today - 20, onJumped = { jumped = true })
            }
        }
        compose.runOnIdle {
            assertTrue(jumped)
            // 6 photos a day: day 20 starts at photo 120, after 20 headers
            assertEquals(140, state.firstVisibleItemIndex)
        }
    }
}
