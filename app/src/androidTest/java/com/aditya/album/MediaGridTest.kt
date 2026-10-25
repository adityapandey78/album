package com.aditya.album

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableIntStateOf
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
            AlbumTheme(Look.Clean) {
                MediaGrid(media, columns, rememberLazyGridState(), emptySet(), {}, {}, PaddingValues(), Modifier.testTag("grid"))
            }
        }
        val grid = compose.onNodeWithTag("grid")

        // Fingers apart (zoom in): 4 -> 3, even though the gesture is big enough for two steps
        grid.performTouchInput { pinch(center - Offset(150f, 0f), center - Offset(450f, 0f), center + Offset(150f, 0f), center + Offset(450f, 0f)) }
        compose.runOnIdle { assertEquals(3, columns.intValue) }

        // Fingers together (zoom out) three times: 3 -> 4 -> 5 -> 7, and 7 switches to month headers
        repeat(3) {
            grid.performTouchInput { pinch(center - Offset(450f, 0f), center - Offset(150f, 0f), center + Offset(450f, 0f), center + Offset(150f, 0f)) }
        }
        compose.runOnIdle { assertEquals(7, columns.intValue) }
        compose.onNodeWithText(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))).assertExists()
    }

    @Test fun showsTodayHeaderAndOpensTappedPhoto() {
        var opened: Media? = null
        compose.setContent {
            AlbumTheme(Look.Clean) {
                MediaGrid(media, mutableIntStateOf(DEFAULT_COLUMNS), rememberLazyGridState(), emptySet(), { opened = it }, {}, PaddingValues())
            }
        }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
        compose.onAllNodes(hasClickAction())[0].performClick() // first cell = newest photo
        compose.runOnIdle { assertEquals(0L, opened?.id) }
    }
}
