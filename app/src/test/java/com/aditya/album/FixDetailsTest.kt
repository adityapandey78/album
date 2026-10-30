package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class FixDetailsTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private val hour = 3_600_000L

    @Test fun shiftsAddDaysHoursAndMinutes() {
        assertEquals(((24 + 2) * 60 + 30) * 60_000L, shiftOf(1, 2, 30, later = true))
        assertEquals(-90 * 60_000L, shiftOf(0, 1, 30, later = false))
        assertEquals(0L, shiftOf(0, 0, 0, later = true))
    }

    @Test fun settingTheFirstMovesEveryOneAlikeSoTheyKeepTheirOrder() {
        val first = LocalDateTime.of(2026, 10, 30, 10, 0).atZone(india).toInstant().toEpochMilli() // a restore gave them all today
        val taken = listOf(first, first + 90_000, first + 26 * hour)
        val shift = shiftToSet(first, LocalDate.of(2019, 3, 12), LocalTime.of(9, 5), india)
        val moved = taken.map { it + shift }
        assertEquals(LocalDateTime.of(2019, 3, 12, 9, 5), Instant.ofEpochMilli(moved[0]).atZone(india).toLocalDateTime())
        assertEquals(taken.zipWithNext { a, b -> b - a }, moved.zipWithNext { a, b -> b - a })
    }

    @Test fun settingReadsTheTimeOnThePhonesClock() {
        val first = LocalDateTime.of(2024, 3, 15, 18, 30).atZone(india).toInstant().toEpochMilli()
        assertEquals(0L, shiftToSet(first, LocalDate.of(2024, 3, 15), LocalTime.of(18, 30), india))
        // The same moment on a phone in London, still on GMT that day
        assertEquals(0L, shiftToSet(first, LocalDate.of(2024, 3, 15), LocalTime.of(13, 0), london))
        // In summer London is an hour ahead of GMT
        val summer = LocalDateTime.of(2024, 7, 1, 12, 0).atZone(london).toInstant().toEpochMilli()
        assertEquals(hour, shiftToSet(summer, LocalDate.of(2024, 7, 1), LocalTime.of(13, 0), london))
    }

    @Test fun aTimeTheClocksSkippedCountsAsAfterTheChange() {
        // London's clocks jumped from 1:00 to 2:00 on 31 Mar 2024, so 1:30 is 2:30 summer time
        assertEquals(Instant.parse("2024-03-31T01:30:00Z").toEpochMilli(), shiftToSet(0, LocalDate.of(2024, 3, 31), LocalTime.of(1, 30), london))
    }

    @Test fun shiftsAreSaidInWords() {
        assertEquals("5 hours 30 minutes later", shiftText(shiftOf(0, 5, 30, later = true)))
        assertEquals("1 day earlier", shiftText(-24 * hour))
        assertEquals("1 day 1 hour 1 minute later", shiftText(shiftOf(1, 1, 1, later = true)))
        assertEquals("400 days later", shiftText(shiftOf(400, 0, 0, later = true)))
        assertEquals("Same time", shiftText(30_000)) // under a minute
    }

    @Test fun exifDatesReadAndWriteInExifsFormat() {
        val t = LocalDateTime.of(2024, 3, 15, 18, 30, 12)
        assertEquals("2024:03:15 18:30:12", exifDateTime(t))
        assertEquals(t, parseExifDateTime("2024:03:15 18:30:12"))
        assertNull(parseExifDateTime("    :  :     :  :  ")) // what some cameras write when their clock wasn't set
        assertNull(parseExifDateTime(null))
    }

    @Test fun exifDatesMoveByTheShiftOnTheCamerasClock() {
        assertEquals("2025:01:01 00:30:00", shiftedExif("2024:12:31 23:30:00", hour))
        assertEquals("2023:12:31 23:30:00", shiftedExif("2024:12:31 23:30:00", -366 * 24 * hour)) // 2024 was a leap year
        // Exif's clock time has no time zone of its own, so a shift over a clock change moves it by exactly the shift
        assertEquals("2024:03:31 02:30:00", shiftedExif("2024:03:31 00:30:00", 2 * hour))
        assertNull(shiftedExif("not a date", hour))
    }

    @Test fun gpsDateAndTimeMoveTogether() {
        val (date, time) = shiftedGpsStamp("2024:03:15", longArrayOf(23, 1, 30, 1, 5032, 100), hour)!!
        assertEquals("2024:03:16", date)
        assertArrayEquals(longArrayOf(0, 1, 30, 1, 50, 1), time)
        assertNull(shiftedGpsStamp("2024:03:15", longArrayOf(23, 0, 30, 1, 0, 1), hour)) // a zero denominator isn't a time
        assertNull(shiftedGpsStamp(null, longArrayOf(23, 1, 30, 1, 0, 1), hour))
    }

    @Test fun fixedDatesCountOnlyWhileTheFileIsUnchanged() {
        val fix = MediaFix(mediaId = 1, modified = 100, taken = 5)
        assertEquals(5L, fixedTaken(fix, 100))
        assertNull(fixedTaken(fix, 101)) // another app changed the file since: its own date counts again
        assertNull(fixedTaken(fix.copy(taken = null), 100)) // only its place was fixed
        assertNull(fixedTaken(null, 100))
    }

    @Test fun asciiValuesKeepTheirLength() {
        val bytes = asciiBytes("2024:03:15 18:30:12", 20)!!
        assertEquals(20, bytes.size)
        assertEquals(0, bytes[19].toInt())
        assertArrayEquals(byteArrayOf('N'.code.toByte(), 0), asciiBytes("N", 2))
        assertNull(asciiBytes("too long", 3))
    }

    @Test fun gpsIsDegreesMinutesAndSeconds() {
        // 15.4989 N, 73.8278 E: Panaji
        assertArrayEquals(longArrayOf(15, 1, 29, 1, 56040, 1000), gpsRationals(15.4989))
        assertArrayEquals(longArrayOf(73, 1, 49, 1, 40080, 1000), gpsRationals(73.8278))
        assertArrayEquals(gpsRationals(33.8688), gpsRationals(-33.8688)) // the sign goes in the reference
        assertEquals("N", gpsRef(15.4989, latitude = true))
        assertEquals("S", gpsRef(-33.8688, latitude = true))
        assertEquals("E", gpsRef(73.8278, latitude = false))
        assertEquals("W", gpsRef(-73.9857, latitude = false))
    }

    @Test fun rationalsAreWrittenInTheFilesByteOrder() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 72, 0, 0, 0, 1), rationalBytes(longArrayOf(72, 1), ByteOrder.BIG_ENDIAN))
        assertArrayEquals(byteArrayOf(72, 0, 0, 0, 1, 0, 0, 0), rationalBytes(longArrayOf(72, 1), ByteOrder.LITTLE_ENDIAN))
        val values = longArrayOf(15, 1, 29, 1, 56040, 1000)
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) assertArrayEquals(values, rationalsOf(rationalBytes(values, order), order))
        assertEquals("15/1,29/1,56040/1000", rationalText(values))
    }

    @Test fun theByteOrderIsTheOneThatReadsAsExifInterfaceSays() {
        assertEquals(ByteOrder.BIG_ENDIAN, byteOrderOf(rationalBytes(longArrayOf(72, 1), ByteOrder.BIG_ENDIAN), "72/1"))
        assertEquals(ByteOrder.LITTLE_ENDIAN, byteOrderOf(rationalBytes(longArrayOf(72, 1), ByteOrder.LITTLE_ENDIAN), "72/1"))
        assertNull(byteOrderOf(ByteArray(8), "0/0")) // both orders read the same: nothing is written in a guess
        assertNull(byteOrderOf(rationalBytes(longArrayOf(72, 1), ByteOrder.BIG_ENDIAN), "300/1"))
        assertNull(byteOrderOf(ByteArray(8), null))
    }

    @Test fun aPlacesSpotIsARealPhotosNearTheMiddle() {
        val beaches = listOf(doubleArrayOf(15.0, 73.0), doubleArrayOf(15.0, 75.0), doubleArrayOf(15.0, 74.1))
        assertArrayEquals(doubleArrayOf(15.0, 74.1), middleOf(beaches), 0.0) // not 74.0, out at sea between them
        assertNull(middleOf(emptyList()))
    }

    @Test fun theResultSaysWhatAlbumKeepsForFilesThatCantHoldIt() {
        assertNull(albumOnly(0, 24))
        assertEquals("3 of their files can't hold it, so Album keeps it", albumOnly(3, 24))
        assertEquals("Their files can't hold it, so Album keeps it", albumOnly(24, 24))
        assertEquals("Its file can't hold it, so Album keeps it", albumOnly(1, 1))
    }
}
