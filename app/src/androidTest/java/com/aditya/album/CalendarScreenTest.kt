package com.aditya.album

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.now().toEpochDay()
    private val media = List(3) { i -> // two photos today, one five days ago
        Media(
            i.toLong(), Uri.parse("content://test/$i"), false, taken = 0, day = if (i < 2) today else today - 5, width = 0, height = 0,
            durationMs = 0, bucketId = 0, bucketName = "", isFavorite = false, size = 0, name = "", path = "",
        )
    }

    @Test fun announcesTodayWithItsCountAndShowsTheChosenDay() {
        var shown: Long? = null
        compose.setContent { AlbumTheme(Appearance()) { CalendarSheet(media, onDismiss = {}, onDay = { shown = it }, onOpen = {}) } }
        compose.onNodeWithContentDescription("Today, ${dayDescription(today, 2)}").performClick()
        compose.onNodeWithText("Show ", substring = true).performClick()
        compose.runOnIdle { assertEquals(today, shown) }
    }

    @Test fun aDayWithoutPhotosShowsTheNearestOne() {
        var shown: Long? = null
        compose.setContent { AlbumTheme(Appearance()) { CalendarSheet(media, onDismiss = {}, onDay = { shown = it }, onOpen = {}) } }
        val empty = today - 4 // nothing that day; five days ago is nearer than today
        if (LocalDate.ofEpochDay(empty).month != LocalDate.ofEpochDay(today).month) return // that day is on the previous page
        compose.onNodeWithContentDescription(dayDescription(empty, 0)).performClick()
        compose.onNodeWithText("Show ", substring = true).performClick()
        compose.runOnIdle { assertEquals(today - 5, shown) }
    }
}
