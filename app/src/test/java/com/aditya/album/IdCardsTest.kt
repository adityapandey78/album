package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class IdCardsTest {
    private val idKey = Random(1).nextBytes(32)
    private val safeKey = Random(2).nextBytes(32)

    // screenLock, pin, setUp, wrapped, pinCopy
    @Test fun theScreenLockOpensWheneverItCan() {
        assertEquals(IdGate.ScreenLock, idGate(screenLock = true, pin = false, setUp = false, wrapped = false, pinCopy = false)) // makes the key
        assertEquals(IdGate.ScreenLock, idGate(screenLock = true, pin = false, setUp = true, wrapped = true, pinCopy = false))
        // A PIN and its copy never take over while the screen lock's key is there
        assertEquals(IdGate.ScreenLock, idGate(screenLock = true, pin = true, setUp = true, wrapped = true, pinCopy = true))
        assertEquals(IdGate.ScreenLock, idGate(screenLock = true, pin = true, setUp = false, wrapped = false, pinCopy = false))
    }

    @Test fun thePinOpensOnlyWithoutAScreenLockOrAlongsideIt() {
        assertEquals(IdGate.Pin, idGate(screenLock = false, pin = true, setUp = true, wrapped = false, pinCopy = true))
        assertEquals(IdGate.Pin, idGate(screenLock = false, pin = true, setUp = false, wrapped = false, pinCopy = false)) // makes the key
        // The screen lock is back after Android erased its key: the PIN, then the screen lock again
        assertEquals(IdGate.PinThenScreenLock, idGate(screenLock = true, pin = true, setUp = true, wrapped = false, pinCopy = true))
    }

    @Test fun lostOnlyWhenNothingIsLeftThatOpensThem() {
        assertEquals(IdGate.Lost, idGate(screenLock = true, pin = false, setUp = true, wrapped = false, pinCopy = false))
        assertEquals(IdGate.Lost, idGate(screenLock = true, pin = true, setUp = true, wrapped = false, pinCopy = false))
        assertEquals(IdGate.Lost, idGate(screenLock = false, pin = false, setUp = true, wrapped = false, pinCopy = false))
        // Not sure the key is gone (the screen lock is off but Keystore didn't say it erased it): never offer to reset
        assertEquals(IdGate.NoLock, idGate(screenLock = false, pin = false, setUp = true, wrapped = true, pinCopy = false))
        assertEquals(IdGate.NoLock, idGate(screenLock = false, pin = false, setUp = false, wrapped = false, pinCopy = false))
    }

    @Test fun theEscrowSealsWithItsPublicHalfAndOpensWithItsPrivateHalf() {
        val pair = Escrow.newPair()
        val record = Escrow.seal(idKey, pair.public)
        assertArrayEquals(idKey, Escrow.open(record, pair.private))
        assertFalse(record.contentEquals(Escrow.seal(idKey, pair.public))) // a fresh key every time
        assertThrows(Exception::class.java) { Escrow.open(record, Escrow.newPair().private) }
    }

    @Test fun onlyTheSafeKeyOpensTheEscrowsPrivateHalf() {
        val pair = Escrow.newPair()
        val file = Escrow.lock(pair, safeKey)
        val copy = Escrow.seal(idKey, Escrow.publicOf(file)) // what ID cards do, with no safe key at hand
        assertArrayEquals(idKey, Escrow.open(copy, Escrow.privateOf(file, safeKey)))
        assertThrows(Exception::class.java) { Escrow.privateOf(file, Random(3).nextBytes(32)) }
    }

    @Test fun anyChangedByteFailsInsteadOfOpening() {
        val pair = Escrow.newPair()
        val file = Escrow.lock(pair, safeKey)
        val copy = Escrow.seal(idKey, pair.public)
        for (i in 2 until copy.size) { // past the length, whose changes fail as a short read
            val bad = copy.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows("copy byte $i", Exception::class.java) { Escrow.open(bad, pair.private) }
        }
        for (i in 2 until file.size) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows("escrow byte $i", Exception::class.java) { Escrow.privateOf(bad, safeKey) }
        }
    }

    @Test fun keyFilesRoundTrip() {
        val saved = WrappedKey.decode(WrappedKey("album_id_123", Random(4).nextBytes(12), Random(5).nextBytes(48)).encode())
        assertEquals("album_id_123", saved.alias)
        assertEquals(12, saved.iv.size)
        assertEquals(48, saved.wrapped.size)
    }

    @Test fun messagesNameTheBucket() {
        assertEquals("ID cards", placeOf(SafeItem.ID_CARDS))
        assertEquals("Private safe", placeOf(SafeItem.SAFE))
        assertEquals("Moved 3 items to ID cards", movedInMessage(3, 0, placeOf(SafeItem.ID_CARDS)))
        assertEquals("Couldn't move 2 items out. They stay in ID cards.", movedOutMessage(0, 2, "ID cards"))
        assertEquals("They leave the Library and search, and open with your screen lock.", idMoveNote(screenLock = true))
        assertEquals("They leave the Library and search, and open with your privacy PIN.", idMoveNote(screenLock = false))
    }
}
