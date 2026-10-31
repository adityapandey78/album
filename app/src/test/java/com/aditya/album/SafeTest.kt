package com.aditya.album

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

class SafeTest {
    private val key = Random(1).nextBytes(32) // a software key, as the Keystore-unwrapped one is
    private val small = 16 // tiny chunks, so short content crosses many chunk boundaries

    private fun sealed(plain: ByteArray, chunk: Int = small, k: ByteArray = key): File {
        val file = File.createTempFile("safe", ".bin").apply { deleteOnExit() }
        file.outputStream().use { SafeCipher.encrypt(k, ByteArrayInputStream(plain), it, chunk) }
        return file
    }

    private fun open(file: File, k: ByteArray = key) = SafeReader(file, k).use { it.readAll() }

    @Test fun roundTripsEverySizeAroundChunkEdges() {
        for (n in listOf(0, 1, 15, 16, 17, 31, 32, 33, 100)) {
            val plain = Random(n).nextBytes(n)
            val file = sealed(plain)
            assertArrayEquals("size $n", plain, open(file))
            val chunks = maxOf(1, (n + small - 1) / small) // empty content is still one sealed chunk
            assertEquals("size $n", SafeCipher.HEADER + n + chunks * SafeCipher.TAG.toLong(), file.length())
        }
    }

    @Test fun roundTripsWithRealChunkSize() {
        val plain = Random(2).nextBytes(3 * SafeCipher.CHUNK + 123)
        val file = File.createTempFile("safe", ".bin").apply { deleteOnExit() }
        val sha = file.outputStream().use { SafeCipher.encrypt(key, ByteArrayInputStream(plain), it) }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(plain), sha)
        SafeReader(file, key).use {
            assertEquals(plain.size.toLong(), it.size)
            assertArrayEquals(sha, sha256(it.inputStream()))
        }
    }

    @Test fun readsAnyRangeInAnyOrder() {
        val plain = Random(3).nextBytes(100)
        SafeReader(sealed(plain), key).use { r ->
            for (start in listOf(90, 0, 15, 16, 47, 99, 31)) {
                val buffer = ByteArray(40)
                val n = r.read(start.toLong(), buffer, 0, buffer.size)
                // Never past a chunk's end, so a read is at most what's left of the chunk it starts in
                assertEquals(minOf(40, small - start % small, 100 - start), n)
                assertArrayEquals(plain.copyOfRange(start, start + n), buffer.copyOf(n))
            }
            assertEquals(-1, r.read(100, ByteArray(4), 0, 4))
        }
    }

    @Test fun sameContentSealsDifferentlyEachTime() {
        val plain = Random(4).nextBytes(50)
        assertFalse(sealed(plain).readBytes().contentEquals(sealed(plain).readBytes()))
    }

    @Test fun wrongKeyFails() {
        val file = sealed(Random(5).nextBytes(50))
        assertThrows(IOException::class.java) { open(file, Random(6).nextBytes(32)) }
    }

    @Test fun anyChangedByteFails() {
        val plain = Random(7).nextBytes(50)
        val good = sealed(plain).readBytes()
        for (i in good.indices) {
            if (i < 4) continue // the magic: changes there are "not a safe file", also an IOException, checked below
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val file = File.createTempFile("safe", ".bin").apply { deleteOnExit(); writeBytes(bad) }
            assertThrows("byte $i", IOException::class.java) { open(file) }
        }
        val notSafe = File.createTempFile("safe", ".bin").apply { deleteOnExit(); writeBytes(good.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertThrows(IOException::class.java) { open(notSafe) }
    }

    @Test fun cutDroppedOrAddedChunksFail() {
        val plain = Random(8).nextBytes(64) // 4 chunks of 16
        val good = sealed(plain).readBytes()
        val sealedChunk = small + SafeCipher.TAG
        val h = SafeCipher.HEADER
        val variants = mapOf(
            "last chunk cut off" to good.copyOf(good.size - sealedChunk),
            "end cut short" to good.copyOf(good.size - 5),
            "only the header" to good.copyOf(h),
            "middle chunk dropped" to good.copyOfRange(0, h + sealedChunk) + good.copyOfRange(h + 2 * sealedChunk, good.size),
            "chunks swapped" to good.copyOfRange(0, h) + good.copyOfRange(h + sealedChunk, h + 2 * sealedChunk) +
                good.copyOfRange(h, h + sealedChunk) + good.copyOfRange(h + 2 * sealedChunk, good.size),
            "chunk appended" to good + good.copyOfRange(h, h + sealedChunk),
        )
        for ((what, bytes) in variants) {
            val file = File.createTempFile("safe", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
            assertThrows(what, IOException::class.java) { open(file) }
        }
    }

    @Test fun eachChunkHasItsOwnNonce() {
        val prefix = ByteArray(7) { it.toByte() }
        val nonces = (0L until 4).flatMap { listOf(SafeCipher.nonce(prefix, it, false), SafeCipher.nonce(prefix, it, true)) }
        assertEquals(nonces.size, nonces.map { it.toList() }.toSet().size)
        assertEquals(12, nonces[0].size)
    }

    @Test fun headerIsChecked() {
        val file = sealed(ByteArray(3))
        RandomAccessFile(file, "r").use { assertEquals(SafeCipher.HEADER, SafeCipher.header(it).size) }
    }

    @Test fun restoresToItsFolderWhenMediaStoreAllowsIt() {
        assertEquals("DCIM/Camera/", restorePath("DCIM/Camera/", isVideo = false))
        assertEquals("Pictures/WhatsApp/", restorePath("Pictures/WhatsApp", isVideo = false)) // trailing slash added
        assertEquals("Movies/Clips/", restorePath("Movies/Clips/", isVideo = true))
    }

    @Test fun restoresElsewhereToRestoredWhereAlbumSaves() {
        assertNull(restorePath("Movies/Clips/", isVideo = false)) // photos can't live in Movies
        assertNull(restorePath("Download/", isVideo = false))
        assertNull(restorePath("Android/media/com.whatsapp/WhatsApp/Media/", isVideo = true))
        assertNull(restorePath("", isVideo = true))
        assertEquals("Pictures/Restored/", SaveLocation().inside("Restored").folder) // the default: same as before 1.2
        assertEquals("DCIM/Album/Restored/", SaveLocation(folder = "DCIM/Album/").inside("Restored").folder)
    }

    @Test fun movedInMessages() {
        assertNull(movedInMessage(0, 0)) // nothing tried, or Android's pop-up declined
        assertEquals("Moved 12 items to Private safe", movedInMessage(12, 0))
        assertEquals("Moved 1 item to Private safe", movedInMessage(1, 0))
        assertEquals("Couldn't move 1 item to Private safe. It stays in the Library.", movedInMessage(0, 1))
        assertEquals("Moved 10 items to Private safe. 2 couldn't be moved and stay in the Library.", movedInMessage(10, 2))
    }

    @Test fun movedOutMessages() {
        assertNull(movedOutMessage(0, 0))
        assertEquals("Moved 3 items back to the Library", movedOutMessage(3, 0))
        assertEquals("Couldn't move 2 items out. They stay in Private safe.", movedOutMessage(0, 2))
        assertEquals("Moved 3 items back to the Library. 1 couldn't be moved and stays in Private safe.", movedOutMessage(3, 1))
    }

    /**
     * A move in while locked, as Safe.add does it: a key of the item's own encrypts the file and is sealed to the public half
     * of the safe's key pair, with no safe key at hand. Once unlocked, the safe key opens the private half, that the item's
     * key, and that the file. Neither the safe key nor another safe's opens the item directly.
     */
    @Test fun aSealedMoveInOpensOnlyThroughTheSafeKey() {
        val pair = Escrow.lock(Escrow.newPair(), key) // made at an unlock: the public half in the clear
        val plain = Random(9).nextBytes(100)
        val own = Random(10).nextBytes(32)
        val file = sealed(plain, k = own)
        val record = Escrow.seal(own, Escrow.publicOf(pair)) // locked: only the public half
        val opened = Escrow.open(record, Escrow.privateOf(pair, key))
        assertArrayEquals(own, opened)
        assertArrayEquals(plain, open(file, opened))
        assertThrows(IOException::class.java) { open(file, key) } // the safe key isn't the item's key
        assertThrows(Exception::class.java) { Escrow.privateOf(pair, Random(11).nextBytes(32)) }
        assertFalse(record.contentEquals(Escrow.seal(own, Escrow.publicOf(pair)))) // a fresh seal every time
    }

    @Test fun movesInWithoutUnlockingOnlyOnceThereIsAKeyPairForAPinsSafe() {
        assertTrue(canSealLocked(pin = true, setUp = true, keyPair = true))
        assertFalse(canSealLocked(pin = true, setUp = true, keyPair = false)) // not opened since the update: the PIN, once
        assertFalse(canSealLocked(pin = false, setUp = true, keyPair = true)) // a v1.0 safe with no PIN yet
        assertFalse(canSealLocked(pin = true, setUp = false, keyPair = true)) // no safe key: a pair left over is stale
        assertFalse(canSealLocked(pin = false, setUp = false, keyPair = false)) // nothing set up: the PIN's setup first
    }

    @Test fun hexIsLowerCaseAndPadded() {
        assertEquals("000fff", byteArrayOf(0, 15, -1).hex())
    }
}
