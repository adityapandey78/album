package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ZipTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(india).toInstant().toEpochMilli()
    private fun name(vararg taken: Long) = zipName(null, taken.toList(), india, Locale.ENGLISH)

    @Test fun anAlbumsZipIsNamedAfterIt() {
        assertEquals("Goa 2024.zip", zipName("Goa 2024", listOf(at(2024, 1, 1)), india))
        assertEquals("Trips_ Goa_Manali.zip", zipName("Trips: Goa/Manali", emptyList(), india))
    }

    @Test fun aSelectionsZipIsNamedByItsDates() {
        assertEquals("Photos 24 Oct 2026.zip", name(at(2026, 10, 24, 9), at(2026, 10, 24, 23)))
        assertEquals("Photos Oct 2026.zip", name(at(2026, 10, 2), at(2026, 10, 24)))
        assertEquals("Photos 2026.zip", name(at(2026, 1, 2), at(2026, 10, 24)))
        assertEquals("Photos 2019 to 2026.zip", name(at(2026, 10, 24), at(2019, 3, 12)))
        assertEquals("Photos.zip", name())
    }

    @Test fun namesAreSafeOnAnyComputer() {
        assertEquals("a_b_c", safeFileName("a/b\\c"))
        assertEquals("hidden", safeFileName("..hidden"))
        assertEquals("Trip", safeFileName(" Trip. "))
        assertEquals("Album", safeFileName("  "))
    }

    @Test fun pathsKeepFoldersApartAndFileNamesUnique() {
        val parts = listOf(
            "Goa" to listOf("IMG_1.jpg", "img_1.jpg"), // shared storage ignores case, and so do ZIP tools on most computers
            "goa" to listOf("IMG_1.jpg"), // two albums called Goa: a folder and a collection
            null to listOf("a.jpg"),
        )
        assertEquals(listOf("Goa/IMG_1.jpg", "Goa/img_1 (1).jpg", "goa (1)/IMG_1.jpg", "a.jpg"), zipPaths(parts) { it }.map { it.first })
    }

    @Test fun theSameNameInTwoFoldersIsFine() {
        val parts = listOf("Goa" to listOf("IMG_1.jpg"), "Manali" to listOf("IMG_1.jpg"))
        assertEquals(listOf("Goa/IMG_1.jpg", "Manali/IMG_1.jpg"), zipPaths(parts) { it }.map { it.first })
    }
}
