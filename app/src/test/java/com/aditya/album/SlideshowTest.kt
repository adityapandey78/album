package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowTest {
    private fun next(videos: String, from: Int) = nextPhoto(videos.length, from) { videos[it] == 'v' } // p = photo, v = video

    @Test fun skipsVideos() {
        assertEquals(0, next("pvvp", 0))
        assertEquals(3, next("pvvp", 1))
    }

    @Test fun loopsBackToTheStart() {
        assertEquals(0, next("pvv", 1))
        assertEquals(0, next("pvv", 3)) // one past the end, after the last photo
    }

    @Test fun nothingToShowWithoutPhotos() {
        assertNull(next("vv", 0))
        assertNull(next("", 0))
    }

    private fun story(videos: String, start: Int) = storySlides(videos.indices.toList(), start) { videos[it] == 'v' }

    @Test fun storyRunsFromTheStartPhotoToTheEndWithoutVideos() {
        assertEquals(listOf(1, 3, 4), story("ppvpp", 1))
        assertEquals(listOf(3, 4), story("ppvpp", 2)) // started on a video: from the next photo
    }

    @Test fun storyFromAVideoAfterTheLastPhotoStartsOver() {
        assertEquals(listOf(0, 2), story("pvpv", 3))
    }

    @Test fun noStoryWithoutPhotos() {
        assertEquals(emptyList<Int>(), story("vv", 0))
    }
}
