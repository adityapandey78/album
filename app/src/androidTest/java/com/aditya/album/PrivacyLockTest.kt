package com.aditya.album

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.random.Random

/**
 * The privacy PIN against this phone's real Keystore: the PIN's file opens only through the Keystore key made with it,
 * wrong PINs count and then wait, and a PIN change keeps the safe key. Run in the eval build, whose data is its own.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyLockTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = Random(3).nextBytes(32)

    private fun clean() {
        File(File(context.filesDir, "safe"), "pin").delete()
        context.getSharedPreferences("privacy", 0).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("album_pin")
    }

    @Before fun start() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { PrivacyLock.init(context) }
        clean()
    }

    @After fun end() = clean()

    @Test fun thePinOpensTheSafeKey() = runBlocking {
        PrivacyLock.savePin(key, "2580")
        assertTrue(PrivacyLock.setUp.value)
        assertEquals(4, PrivacyLock.pinLength())
        assertArrayEquals(key, (PrivacyLock.check("2580") as PinCheck.Right).key)
    }

    @Test fun wrongPinsCountThenWaitAndARightOneClearsThem() = runBlocking {
        PrivacyLock.savePin(key, "13579")
        for (n in 1 until FREE_TRIES) assertEquals(n, (PrivacyLock.check("97531") as PinCheck.Wrong).strikes)
        assertArrayEquals(key, (PrivacyLock.check("13579") as PinCheck.Right).key) // the count starts again
        repeat(FREE_TRIES) { PrivacyLock.check("00000") }
        // Even the right PIN waits now
        val wait = PrivacyLock.check("13579") as PinCheck.Wait
        assertTrue(wait.ms in 1..30_000)
    }

    @Test fun changingThePinKeepsTheKey() = runBlocking {
        PrivacyLock.savePin(key, "1111")
        val opened = (PrivacyLock.check("1111") as PinCheck.Right).key
        PrivacyLock.savePin(opened, "24682468")
        assertEquals(8, PrivacyLock.pinLength())
        assertTrue(PrivacyLock.check("1111") is PinCheck.Wrong)
        assertArrayEquals(key, (PrivacyLock.check("24682468") as PinCheck.Right).key)
    }

    @Test fun withoutThisPhonesKeystoreKeyThePinCantBeChecked() = runBlocking {
        PrivacyLock.savePin(key, "4321")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("album_pin")
        // Not a wrong PIN, which would count towards a wait: only Forgot PIN gets back in
        assertEquals(PinCheck.Broken, PrivacyLock.check("4321"))
    }
}
