package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ForYouTest {
    private val today = LocalDate.of(2026, 10, 25)
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    /** Named photos on epoch days, sorted newest first the way the Library holds them. */
    private fun photos(vararg p: Pair<String, Long>) = p.sortedByDescending { it.second }
    private val dayOf = { p: Pair<String, Long> -> p.second }
    private fun names(m: Memory<Pair<String, Long>>?) = m?.items?.map { it.first }

    @Test fun spreadPicksEvenlyAcrossInOrder() {
        assertEquals(listOf(0, 5, 10, 15), spread((0 until 20).toList(), 4))
        assertEquals(listOf(1, 2), spread(listOf(1, 2), 20)) // fewer than asked: all of them
        assertEquals(emptyList<Int>(), spread(emptyList<Int>(), 20))
    }

    @Test fun onThisDayIsTheLatestEarlierYearOldestFirst() {
        val p = photos(
            "today" to day(2026, 10, 25), "b" to day(2025, 10, 25), "a" to day(2025, 10, 25),
            "older" to day(2023, 10, 25), "dayBefore" to day(2025, 10, 24),
        )
        val m = onThisDay(p, today, dayOf)!!
        assertEquals("On this day · 2025", m.eyebrow)
        assertEquals("1 year ago", m.title)
        assertEquals(listOf("a", "b"), names(m)) // the Library lists b first (newer), so oldest first is a, b
    }

    @Test fun onThisDayGoesBackFurtherWhenLastYearHasNone() {
        val m = onThisDay(photos("x" to day(2023, 10, 25), "y" to day(2025, 10, 26)), today, dayOf)!!
        assertEquals("On this day · 2023", m.eyebrow)
        assertEquals("3 years ago", m.title)
        assertNull(onThisDay(photos("y" to day(2025, 10, 26), "now" to day(2026, 10, 25)), today, dayOf))
    }

    @Test fun leapDayOnlyMatchesLeapYears() {
        val p = photos("leap" to day(2024, 2, 29), "feb28" to day(2027, 2, 28))
        assertEquals(listOf("leap"), names(onThisDay(p, LocalDate.of(2028, 2, 29), dayOf)))
        assertNull(onThisDay(p, LocalDate.of(2027, 3, 1), dayOf))
    }

    @Test fun weekLastYearIsThreeDaysEitherSideLeavingOutTheDayItself() {
        val p = photos(
            "tooEarly" to day(2025, 10, 21), "first" to day(2025, 10, 22), "onThisDay" to day(2025, 10, 25),
            "last" to day(2025, 10, 28), "tooLate" to day(2025, 10, 29), "thisYear" to day(2026, 10, 24),
        )
        val m = weekLastYear(p, today, dayOf)!!
        assertEquals("This week · 2025", m.eyebrow)
        assertEquals("22 to 28 Oct", m.title)
        assertEquals(listOf("first", "last"), names(m))
        assertNull(weekLastYear(photos("onThisDay" to day(2025, 10, 25)), today, dayOf))
    }

    @Test fun recapIsTheLatestEarlierMonthWithPhotos() {
        val p = photos(
            "thisMonth" to day(2026, 10, 3), "end" to day(2026, 8, 31), "start" to day(2026, 8, 1), "july" to day(2026, 7, 31),
        )
        val m = monthRecap(p, today, dayOf)!!
        assertEquals("August", m.title) // September had no photos
        assertEquals("Recap · 2026", m.eyebrow)
        assertEquals(listOf("start", "end"), names(m))
        assertNull(monthRecap(photos("thisMonth" to day(2026, 10, 3)), today, dayOf))
    }

    @Test fun recapCrossesTheYear() {
        val m = monthRecap(photos("nye" to day(2025, 12, 31)), LocalDate.of(2026, 1, 10), dayOf)!!
        assertEquals("December", m.title)
        assertEquals("Recap · 2025", m.eyebrow)
    }

    @Test fun dayRangesReadLikeTheRestOfAlbum() {
        assertEquals("24 Oct", dayRange(day(2025, 10, 24), day(2025, 10, 24)))
        assertEquals("20 to 26 Oct", dayRange(day(2025, 10, 20), day(2025, 10, 26)))
        assertEquals("29 Oct to 2 Nov", dayRange(day(2025, 10, 29), day(2025, 11, 2)))
    }

    @Test fun storageKinds() {
        assertEquals(StorageKind.Photos, storageKind(false, "DCIM/Camera/", "Camera", "IMG_20261025_101010.jpg"))
        assertEquals(StorageKind.Videos, storageKind(true, "DCIM/Camera/", "Camera", "VID_20261025_101010.mp4"))
        val whatsApp = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/"
        assertEquals(StorageKind.WhatsApp, storageKind(true, whatsApp, "WhatsApp Video", "VID-20261025-WA0001.mp4"))
        assertEquals(StorageKind.WhatsApp, storageKind(false, "Pictures/", "WhatsApp Images", "IMG-20261025-WA0001.jpg"))
        assertEquals(StorageKind.Screenshots, storageKind(false, "Pictures/Screenshots/", "Screenshots", "Screenshot_20261025.png"))
        assertEquals(StorageKind.Screenshots, storageKind(false, "DCIM/", "DCIM", "Screenshot_20261025.png"))
    }

    @Test fun storageAddsUpPerKindInBarOrderSkippingEmptyOnes() {
        val files = listOf(StorageKind.Screenshots to 5L, StorageKind.Photos to 100L, StorageKind.Photos to 20L, StorageKind.Screenshots to 1L)
        assertEquals(
            listOf(StorageKind.Photos to 120L, StorageKind.Screenshots to 6L),
            storageOf(files, { it.first }, { it.second }),
        )
        assertEquals(emptyList<Pair<StorageKind, Long>>(), storageOf(emptyList<Pair<StorageKind, Long>>(), { it.first }, { it.second }))
    }

    @Test fun yearRecapIsLastYearInJanuaryAndThisYearFromJuly() {
        val p = photos("nye" to day(2025, 12, 31), "newYear" to day(2026, 1, 1), "june" to day(2026, 6, 30), "lastYear" to day(2025, 3, 1))
        val january = yearRecap(p, LocalDate.of(2026, 1, 10), dayOf)!!
        assertEquals("Year recap", january.eyebrow)
        assertEquals("2025", january.title)
        assertEquals(listOf("lastYear", "nye"), names(january))
        val soFar = yearRecap(p, LocalDate.of(2026, 7, 1), dayOf)!!
        assertEquals("So far this year", soFar.eyebrow)
        assertEquals("2026", soFar.title)
        assertEquals(listOf("newYear", "june"), names(soFar))
        assertNull(yearRecap(p, LocalDate.of(2026, 6, 30), dayOf)) // February to June: none
        assertNull(yearRecap(p, LocalDate.of(2028, 1, 5), dayOf)) // 2027 had no photos
    }

    @Test fun idCardsNeedTheirNumberOrASecondPhrase() {
        // Made-up details in the layout each card prints
        val aadhaar = "Government of India\nRahul Sharma\nDOB: 01/01/1990\nMale\n2345 6789 0123\nVID : 9134 5678 9012 3456"
        val pan = "INCOME TAX DEPARTMENT GOVT. OF INDIA\nRAHUL SHARMA\nPermanent Account Number\nABCDE1234F"
        val passport = "REPUBLIC OF INDIA\nType P Country Code IND Passport No. K1234567"
        val mrz = "P<INDSHARMA<<RAHUL<<<<<<<<<<<<<<<<<<<<<<<<<"
        val voter = "ELECTION COMMISSION OF INDIA\nIDENTITY CARD\nABC1234567"
        val licence = "Indian Union Driving Licence\nIssued by Government of Karnataka\nKA01 20190001234"
        val card = "Platinum\n4111 1111\n1111 1111\nVALID THRU 12/29\nRAHUL SHARMA VISA"
        listOf(aadhaar, pan, passport, mrz, voter, licence, card).forEach { assertEquals(it, true, looksLikeIdCard(it)) }

        val notCards = listOf(
            "Link your Aadhaar with PAN before 31 March, says the Income Tax Department",
            "Your Aadhaar XXXX XXXX 1234 has been linked to your mobile number",
            "Enter your Permanent Account Number (PAN) to continue",
            "Keep your passport and boarding pass ready at the gate",
            "if (p<max) return p",
            "Election Commission announces dates for the Bihar election",
            "Apply for a driving licence online in 3 steps",
            "VISA 4111 1111 1111 1112 VALID THRU 12/29", // fails the card checksum
            "Paid with RuPay card XXXX XXXX XXXX 1234",
            "VID : 9134 5678 9012 3456", // an Aadhaar virtual ID alone, no network or card words
        )
        notCards.forEach { assertEquals(it, false, looksLikeIdCard(it)) }
    }
}
