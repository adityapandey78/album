package com.aditya.album

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest

class ModelDownloadTest {
    @get:Rule val folder = TemporaryFolder()

    // SHA-256 of the ASCII bytes "hello world"
    private val hello = ModelFile("hello.bin", 11, "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9")

    @Test fun acceptsOnlyTheExactFile() {
        val file = folder.newFile()
        file.writeText("hello world")
        assertTrue(verified(file, hello))
        file.writeText("hello worle") // same size, one byte off
        assertFalse(verified(file, hello))
        file.writeText("hello world!")
        assertFalse(verified(file, hello))
        assertFalse(verified(folder.root.resolve("missing"), hello))
    }

    @Test fun hashesLikeSha256sum() {
        val file = folder.newFile()
        file.writeBytes(ByteArray(200_000) { (it % 251).toByte() }) // more than one read buffer
        val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, sha256(file))
    }

    @Test fun resumesOnlyFromAUsefulPartialFile() {
        assertEquals(0L, resumeFrom(0, 100))
        assertEquals(40L, resumeFrom(40, 100))
        assertEquals(0L, resumeFrom(100, 100)) // complete but not installed: it failed its check, so start again
        assertEquals(0L, resumeFrom(150, 100))
    }

    @Test fun readsWhereAPartialResponseStarts() {
        assertEquals(1000L, rangeStart("bytes 1000-1999/2000"))
        assertEquals(0L, rangeStart(" bytes 0-0/1 "))
        assertEquals(5L, rangeStart("bytes 5-9/*"))
        assertNull(rangeStart(null))
        assertNull(rangeStart("bytes */2000")) // the form a 416 uses
        assertNull(rangeStart("items 1-2/3"))
    }

    // --- The download itself, against a local server standing in for GitHub

    private val body = ByteArray(300_000) { (it * 7 % 251).toByte() }
    private val model = ModelFile("m.bin", body.size.toLong(), MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })

    /** Serves [bytes] at a local URL, honouring Range if [ranges], hanging up after [cut] bytes. Returns the Range headers seen. */
    private fun serve(ranges: Boolean, bytes: ByteArray = body, cut: Int = Int.MAX_VALUE, test: (String) -> Unit): List<String?> {
        val seen = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/m.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range").also { seen += it }
            val from = range?.takeIf { ranges }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (from > 0) exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
            exchange.sendResponseHeaders(if (from > 0) 206 else 200, (bytes.size - from).toLong())
            runCatching { exchange.responseBody.use { it.write(bytes, from, minOf(bytes.size, cut) - from) } } // short: the server hangs up
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}/m.bin")
        } finally {
            server.stop(0)
        }
        return seen
    }

    @Test fun downloadsAWholeFile() {
        val part = folder.root.resolve("m.part")
        serve(ranges = true) { url -> ModelDownload.fetch(url, model, part) {} }
        assertTrue(verified(part, model))
    }

    @Test fun keepsWhatArrivedWhenTheConnectionDrops() {
        val part = folder.root.resolve("m.part")
        serve(ranges = true, cut = 120_000) { url -> assertThrows(DownloadError::class.java) { ModelDownload.fetch(url, model, part) {} } }
        assertTrue(part.length() in 1..120_000L) // kept, to continue from
        assertTrue(part.readBytes().contentEquals(body.copyOf(part.length().toInt())))
    }

    @Test fun continuesFromAPartialFile() {
        val part = folder.root.resolve("m.part")
        part.writeBytes(body.copyOf(100_000))
        val seen = serve(ranges = true) { url -> ModelDownload.fetch(url, model, part) {} }
        assertEquals(listOf("bytes=100000-"), seen)
        assertTrue(verified(part, model))
    }

    @Test fun startsAgainWhenTheServerSendsTheWholeFile() {
        val part = folder.root.resolve("m.part")
        part.writeBytes(ByteArray(100_000)) // not even the right bytes: replaced, not appended to
        serve(ranges = false) { url -> ModelDownload.fetch(url, model, part) {} }
        assertTrue(verified(part, model))
    }

    @Test fun refusesAFileOfAnotherSizeBeforeDownloadingIt() {
        val part = folder.root.resolve("m.part")
        part.writeBytes(body.copyOf(1000))
        serve(ranges = true, bytes = body + 1) { url -> assertThrows(DownloadError::class.java) { ModelDownload.fetch(url, model, part) {} } }
        assertFalse(part.exists()) // what was kept belongs to another file
    }

    @Test fun modelFilesAreAllThatIsDownloaded() {
        assertEquals(ModelDownload.IMAGE.size + ModelDownload.TEXT.size, ModelDownload.TOTAL)
        for (f in listOf(ModelDownload.IMAGE, ModelDownload.TEXT)) assertTrue(f.sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
