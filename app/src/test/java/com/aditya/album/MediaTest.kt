package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MediaTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun millis(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun seconds(text: String) = millis(text) / 1000
    private val restored = seconds("2026-10-25T09:00:00") // a backup restore gave every file this time

    @Test fun theCameraDateWins() {
        assertEquals(millis("2024-03-15T10:00:00"), takenOf(millis("2024-03-15T10:00:00"), restored, "IMG-20240101-WA0001.jpg", zone))
    }

    @Test fun whatsAppPhotosKeepTheTimeTheyArrived() {
        assertEquals(millis("2024-03-15T18:30:00"), takenOf(0, seconds("2024-03-15T18:30:00"), "IMG-20240315-WA0012.jpg", zone))
    }

    @Test fun restoredWhatsAppPhotosGetTheDayInTheirName() {
        assertEquals(millis("2024-03-15T12:00:00"), takenOf(0, restored, "IMG-20240315-WA0012.jpg", zone))
        assertEquals(millis("2023-12-31T12:00:00"), takenOf(0, restored, "VID-20231231-WA0003.mp4", zone))
    }

    @Test fun whatsAppDesktopNamesHaveTheTimeToo() {
        assertEquals(millis("2024-03-15T18:30:12"), takenOf(0, restored, "WhatsApp Image 2024-03-15 at 18.30.12.jpeg", zone))
    }

    @Test fun twelveHourDesktopNamesKnowMorningFromEvening() {
        assertEquals(millis("2024-03-15T18:30:12"), takenOf(0, restored, "WhatsApp Image 2024-03-15 at 6.30.12 PM.jpeg", zone))
        assertEquals(millis("2024-03-15T18:30:12"), takenOf(0, restored, "WhatsApp Image 2024-03-15 at 6.30.12\u202FPM.jpeg", zone)) // macOS
        assertEquals(millis("2024-03-15T06:30:12"), takenOf(0, restored, "WhatsApp Video 2024-03-15 at 6.30.12 am.mp4", zone))
        assertEquals(millis("2024-03-15T12:05:00"), takenOf(0, restored, "WhatsApp Image 2024-03-15 at 12.05.00 PM.jpeg", zone))
        assertEquals(millis("2024-03-15T00:05:00"), takenOf(0, restored, "WhatsApp Image 2024-03-15 at 12.05.00 AM.jpeg", zone))
    }

    @Test fun otherFilesKeepTheirFileTime() {
        listOf("IMG-20241345-WA0001.jpg", "IMG_20240315_103000.jpg", "Screenshot_20240315-103000.png", "photo.jpg").forEach { name ->
            assertEquals(name, restored * 1000, takenOf(0, restored, name, zone))
        }
    }

    @Test fun whatsAppNames() {
        assertEquals(true, isWhatsAppName("IMG-20240315-WA0012.jpg"))
        assertEquals(true, isWhatsAppName("WhatsApp Video 2024-03-15 at 6.30.12 PM.mp4"))
        assertEquals(false, isWhatsAppName("IMG_20240315_103000.jpg"))
    }
}
