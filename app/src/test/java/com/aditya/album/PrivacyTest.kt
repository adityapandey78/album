package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class PrivacyTest {
    private val key = Random(1).nextBytes(32) // the safe key
    private val fast = 1_000 // PBKDF2 rounds: the real count only makes the tests slow

    // A software HMAC standing in for the phone's Keystore key; another secret is another phone
    private fun device(secret: Int): (ByteArray) -> ByteArray = { input ->
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Random(secret).nextBytes(32), "HmacSHA256")) }.doFinal(input)
    }

    private val phone = device(7)

    private fun seal(pin: String, d: (ByteArray) -> ByteArray = phone) = PinSeal.seal(key, pin, d, fast)

    @Test fun theRightPinOpensTheSafeKey() {
        for (pin in listOf("0000", "1234", "90210", "12345678")) assertArrayEquals(pin, key, PinSeal.open(seal(pin), pin, phone))
    }

    @Test fun aWrongPinOpensNothing() {
        val record = seal("2468")
        for (pin in listOf("2469", "8642", "0000", "24680", "246", "")) assertNull(pin, PinSeal.open(record, pin, phone))
    }

    @Test fun theFileIsUselessOffThePhone() {
        // The same PIN with another phone's Keystore key (or none of this one's) opens nothing, so guessing has to happen here
        assertNull(PinSeal.open(seal("1234"), "1234", device(8)))
    }

    @Test fun changingThePinKeepsTheKeyAndRetiresTheOldPin() {
        val old = seal("1111")
        val changed = PinSeal.seal(PinSeal.open(old, "1111", phone)!!, "987654", phone, fast)
        assertArrayEquals(key, PinSeal.open(changed, "987654", phone))
        assertNull(PinSeal.open(changed, "1111", phone))
        assertEquals(6, PinSeal.length(changed))
    }

    @Test fun eachSealIsDifferent() {
        assertFalse(seal("1234").contentEquals(seal("1234"))) // fresh salt and IV every time
    }

    @Test fun anyChangedByteFailsInsteadOfOpening() {
        val good = seal("4321")
        for (i in good.indices) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val opened = runCatching { PinSeal.open(bad, "4321", phone) }.getOrNull()
            assertNull("byte $i", opened) // the header is authenticated with the key: no field can be changed unnoticed
        }
    }

    @Test fun storesTheIterationsItWasSealedWith() {
        val strong = PinSeal.seal(key, "5555", phone, 20_000, SecureRandom())
        assertArrayEquals(key, PinSeal.open(strong, "5555", phone))
    }

    @Test fun pinsAreFourToEightDigits() {
        listOf("1234", "00000000", "567890").forEach { assertTrue(it, isPin(it)) }
        listOf("", "123", "123456789", "12a4", "12 4", "١٢٣٤").forEach { assertFalse(it, isPin(it)) }
    }

    @Test fun waitsGrowAfterTheFreeTries() {
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), (0 until FREE_TRIES).map(::waitAfter))
        val waits = (FREE_TRIES..FREE_TRIES + 6).map(::waitAfter)
        assertEquals(30_000L, waits.first())
        assertTrue(waits.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(60 * 60_000L, waitAfter(100)) // never more than an hour
    }

    @Test fun waitsSurviveARestartAndAChangedClock() {
        val struck = Strikes(FREE_TRIES, elapsed = 50_000, wall = 1_000_000, boot = 3)
        assertEquals(30_000L, waitLeft(struck, elapsed = 50_000, wall = 1_000_000, boot = 3))
        assertEquals(20_000L, waitLeft(struck, elapsed = 60_000, wall = 1_010_000, boot = 3))
        // Same boot: setting the clock forward skips nothing
        assertEquals(20_000L, waitLeft(struck, elapsed = 60_000, wall = 9_000_000, boot = 3))
        // After a restart the boot clock starts again, so the wall clock counts
        assertEquals(25_000L, waitLeft(struck, elapsed = 1_000, wall = 1_005_000, boot = 4))
        // A wall clock set back counts as no time passed
        assertEquals(30_000L, waitLeft(struck, elapsed = 1_000, wall = 500_000, boot = 4))
        assertEquals(0L, waitLeft(struck, elapsed = 90_000, wall = 1_040_000, boot = 3))
        assertEquals(0L, waitLeft(Strikes(2, 50_000, 1_000_000, 3), 50_000, 1_000_000, 3))
    }

    @Test fun wrongPinNotesCountDownToTheWait() {
        assertEquals("Wrong PIN. Try again.", wrongPinNote(1))
        assertEquals("Wrong PIN. Try again.", wrongPinNote(2))
        assertEquals("Wrong PIN. 2 tries left before a short wait.", wrongPinNote(FREE_TRIES - 2))
        assertEquals("Wrong PIN. 1 try left before a short wait.", wrongPinNote(FREE_TRIES - 1))
        assertEquals("Wrong PIN.", wrongPinNote(FREE_TRIES))
        assertEquals("Wrong PIN.", wrongPinNote(FREE_TRIES + 3)) // the wait itself says the rest
    }

    @Test fun pinAndHidingMessagesCarryTheirIcons() {
        assertEquals(Sym.Lock, messageIcon("PIN set"))
        assertEquals(Sym.Lock, messageIcon("PIN changed"))
        assertEquals(Sym.Fingerprint, messageIcon("PIN and fingerprint set"))
        assertEquals(Sym.VisibilityOff, messageIcon("Hid 2 items"))
        assertEquals(Sym.Visibility, messageIcon("Unhid 2 items"))
        assertEquals(Sym.Info, messageIcon("Couldn't move everyone to Hidden items. Try again."))
    }

    @Test fun waitsReadAsMinutesAndSeconds() {
        assertEquals("0:30", waitText(30_000))
        assertEquals("0:01", waitText(1)) // never 0:00 while still waiting
        assertEquals("14:05", waitText(845_000))
        assertEquals("60:00", waitText(60 * 60_000L))
    }
}
