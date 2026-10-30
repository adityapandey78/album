package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory

class DisguiseTest {
    private fun typed(keys: String) = keys.fold("", Calc::press)
    private fun sum(keys: String) = Calc.evaluate(typed(keys))?.let(Calc::format)

    // ---------- The calculator ----------

    @Test fun precedenceAndDecimalsAreRight() {
        assertEquals("14", sum("2+3×4"))
        assertEquals("0.3", sum("0.1+0.2")) // exact, not 0.30000000000000004
        assertEquals("2.5", sum("10÷4"))
        assertEquals("−6", sum("−2×3"))
        assertEquals("1", sum("10−3×3"))
        assertEquals("0.333333333333", sum("1÷3"))
    }

    @Test fun percentIsAHundredth() {
        assertEquals("0.5", sum("50%"))
        assertEquals("200.1", sum("200+10%"))
    }

    @Test fun incompleteSumsAndZeroGiveNothing() {
        assertNull(sum(""))
        assertNull(sum("5+"))
        assertNull(sum("5÷0"))
        assertNull(sum("5÷0.0"))
    }

    @Test fun keysThatMakeNoSenseAreIgnored() {
        assertEquals("5×", typed("5+×")) // a second operator replaces the first
        assertEquals("−", typed("−+")) // a sum may start negative, but only that
        assertEquals("", typed("×"))
        assertEquals("1.5", typed("1.5.")) // one point a number
        assertEquals("0.5", typed(".5"))
        assertEquals("5%", typed("5%%"))
        assertEquals("5%", typed("5%3"))
        assertEquals("", typed("%"))
        assertEquals(Calc.MAX, typed("1".repeat(60)).length)
    }

    @Test fun resultsAreShownAsACalculatorWould() {
        assertEquals("1E15", sum("1000000×1000000000"))
        assertEquals("1.5E−7", sum("0.0000003÷2")) // the minus sign throughout, never a hyphen
        assertEquals("0", sum("5−5"))
        assertEquals("123456789012", sum("123456789012"))
    }

    @Test fun theDisplayGroupsWholeNumbersOnly() {
        val group = { digits: String -> digits.reversed().chunked(3).joinToString(",").reversed() }
        assertEquals("1,234,567.0012+42", Calc.display("1234567.0012+0042", group))
        assertEquals("0", Calc.display("0000", group))
        assertEquals("0.5", Calc.display("0.5", group))
    }

    // ---------- Unlocking ----------

    @Test fun onlyABareNumberOfThePinsLengthIsTried() {
        assertEquals("0042", pinAttempt("0042", 4)) // as typed: the calculator shows 42, but the PIN is 0042
        assertEquals("12345678", pinAttempt("12345678", 8))
        assertNull(pinAttempt("42", 4))
        assertNull(pinAttempt("12+4", 4))
        assertNull(pinAttempt("12.4", 4))
        assertNull(pinAttempt("−123", 4))
        assertNull(pinAttempt("1234", null)) // no PIN: nothing to try
        assertNull(pinAttempt("123", 3)) // shorter than any PIN
    }

    // ---------- The other fronts ----------

    @Test fun signInCodesAreRealTimeBasedCodes() {
        // RFC 6238's SHA-1 test vectors, six digits
        val secret = "12345678901234567890".toByteArray()
        mapOf(59L to "287082", 1111111109L to "081804", 1111111111L to "050471", 1234567890L to "005924", 2000000000L to "279037")
            .forEach { (seconds, code) -> assertEquals("$seconds", code, signInCode(secret, seconds)) }
        assertEquals(signInCode(secret, 60), signInCode(secret, 89)) // one code for each 30 seconds
    }

    @Test fun theCompassTurnsTheShortWayAndFollowsTheScreen() {
        assertEquals(354f, easeTurn(350f, 10f), 0.001f) // toward 370, not back through 180
        assertEquals(6f, easeTurn(10f, 350f), 0.001f)
        assertEquals(90f, facing(0f, 1), 0.001f) // turned sideways, the screen's top faces east when the phone's faces north
        assertEquals(350f, facing(-10f, 0), 0.001f)
        assertEquals(listOf("N", "NE", "E", "S", "W", "NW", "N"), listOf(0f, 40f, 100f, 180f, 260f, 330f, 359f).map(::compassPoint))
    }

    @Test fun theStopwatchReadsLikeOne() {
        assertEquals("00:00.00", stopwatchText(0))
        assertEquals("01:05.42", stopwatchText(65_429))
        assertEquals("1:02:05.42", stopwatchText(3_725_420))
    }

    // ---------- The launcher entries ----------

    @Test fun aDisguiseWinsOverTheAccent() {
        assertEquals("IconCoral", LauncherIcon.entryFor(Accent.Coral, null))
        assertEquals("DisguiseCalculator", LauncherIcon.entryFor(Accent.Coral, Disguise.Calculator))
        for (a in Accent.entries) for (d in listOf(null) + Disguise.entries) assertTrue(LauncherIcon.entryFor(a, d) in LauncherIcon.entries)
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.toSet().size)
    }

    @Test fun theManifestHasEveryEntryWithItsNameAndIcon() {
        val aliases = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).getElementsByTagName("activity-alias")
        val android = "http://schemas.android.com/apk/res/android"
        val byName = (0 until aliases.length).map { aliases.item(it) as Element }.associateBy { it.getAttributeNS(android, "name").removePrefix(".") }
        assertEquals(LauncherIcon.entries.toSet(), byName.keys)
        // Exactly one entry on at install, Album's own lime one, so a fresh install never shows two icons or a disguise
        assertEquals(listOf("IconLime"), byName.filterValues { it.getAttributeNS(android, "enabled") == "true" }.keys.toList())
        for (d in Disguise.entries) {
            val alias = byName.getValue("Disguise${d.name}")
            assertEquals(d.label, alias.getAttributeNS(android, "label"))
            assertEquals("@mipmap/ic_disguise_${d.name.lowercase()}", alias.getAttributeNS(android, "icon"))
        }
    }

    @Test fun formatStartsASumThatEvaluatesBack() {
        // After =, the answer is the start of the next sum, so it must read back as itself
        for (keys in listOf("2+3×4", "−2×3", "1÷3", "0.1+0.2", "0.0000003÷2", "1000000×1000000000")) {
            val shown = sum(keys)!!
            assertEquals(Calc.evaluate(shown)?.let(Calc::format), shown)
        }
        assertEquals(BigDecimal("-6"), Calc.evaluate("−6"))
        assertEquals("2.00000015", Calc.evaluate("1.5E−7+2")?.let(Calc::format))
    }
}
