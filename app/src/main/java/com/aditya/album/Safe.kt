package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The safe's file format, one file per photo or video: a header, then the content in AES-256-GCM chunks, each sealed on its
 * own, so a 4 GB video streams through 64 KB of memory and a seek in the player decrypts only the chunk it lands in.
 *
 * Header: "ALSF", version 1, the chunk size, a random 32-byte salt and a random 7-byte nonce prefix. Each file gets its own
 * key, HMAC-SHA256 of the salt under the safe's key, so nonces can't repeat across files. Chunk i's nonce is the prefix, i,
 * and a last-chunk flag: chunks can't be swapped, dropped or cut off at the end without decryption failing (the STREAM
 * construction Tink's streaming AEAD uses). Empty content is one empty last chunk, so even that is sealed.
 */
object SafeCipher {
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val HEADER = 4 + 1 + 4 + 32 + 7
    private val MAGIC = "ALSF".toByteArray()
    private const val VERSION: Byte = 1
    private val random = SecureRandom()

    /** Encrypts all of [input] into [output]. Returns the SHA-256 of the plain bytes, for checking copies against. */
    fun encrypt(key: ByteArray, input: InputStream, output: OutputStream, chunk: Int = CHUNK): ByteArray {
        val salt = ByteArray(32).also(random::nextBytes)
        val prefix = ByteArray(7).also(random::nextBytes)
        output.write(ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(chunk).put(salt).put(prefix).array())
        val fileKey = fileKey(key, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val sha = MessageDigest.getInstance("SHA-256")
        var current = ByteArray(chunk)
        var next = ByteArray(chunk)
        val sealed = ByteArray(chunk + TAG)
        var n = input.readNBytes(current, 0, chunk)
        var index = 0L
        while (true) {
            // A full chunk might be the last one: read ahead to find out, since the last chunk is sealed differently
            val ahead = if (n == chunk) input.readNBytes(next, 0, chunk) else 0
            val last = ahead == 0
            sha.update(current, 0, n)
            cipher.init(Cipher.ENCRYPT_MODE, fileKey, GCMParameterSpec(TAG * 8, nonce(prefix, index, last)))
            output.write(sealed, 0, cipher.doFinal(current, 0, n, sealed, 0))
            if (last) return sha.digest()
            current = next.also { next = current }
            n = ahead
            index++
        }
    }

    internal fun fileKey(key: ByteArray, salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update("album safe file".toByteArray()) // this key is for files only, never for anything else
        return SecretKeySpec(mac.doFinal(salt), "AES")
    }

    internal fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray {
        check(index < 1L shl 32) { "file too long" } // 256 TB at 64 KB chunks
        return ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).put(if (last) 1 else 0).array()
    }

    internal fun header(file: RandomAccessFile): ByteArray {
        val header = ByteArray(HEADER)
        file.seek(0)
        file.readFully(header)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC) || header[4] != VERSION) throw IOException("not a safe file")
        return header
    }
}

/**
 * One encrypted file opened for reading: [size] plain bytes, read in any order (a video player seeks). Keeps the chunk it
 * decrypted last, in memory only. Not thread-safe: one reader per user. Damage or the wrong key fails as an IOException.
 */
class SafeReader(file: File, key: ByteArray) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val length = raf.length()
    private val chunk: Int
    private val prefix: ByteArray
    private val fileKey: SecretKeySpec
    private val chunks: Long
    val size: Long

    init {
        try {
            val header = ByteBuffer.wrap(SafeCipher.header(raf))
            header.position(5)
            chunk = header.getInt()
            if (chunk !in 1..(16 shl 20)) throw IOException("bad chunk size")
            val salt = ByteArray(32).also { header.get(it) }
            prefix = ByteArray(7).also { header.get(it) }
            fileKey = SafeCipher.fileKey(key, salt)
            val body = length - SafeCipher.HEADER
            val sealedChunk = chunk + SafeCipher.TAG
            chunks = (body + sealedChunk - 1) / sealedChunk
            // Every file ends in a sealed chunk, at least a bare tag; anything shorter was cut off
            if (chunks == 0L || body - (chunks - 1) * sealedChunk < SafeCipher.TAG) throw IOException("safe file cut short")
            size = body - chunks * SafeCipher.TAG
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val sealed = ByteArray(chunk + SafeCipher.TAG)
    private val plain = ByteArray(chunk)
    private var plainLength = 0
    private var cached = -1L

    private fun load(i: Long) {
        if (i == cached) return
        cached = -1
        val start = SafeCipher.HEADER + i * (chunk + SafeCipher.TAG)
        val n = minOf(chunk + SafeCipher.TAG.toLong(), length - start).toInt()
        raf.seek(start)
        raf.readFully(sealed, 0, n)
        try {
            cipher.init(Cipher.DECRYPT_MODE, fileKey, GCMParameterSpec(SafeCipher.TAG * 8, SafeCipher.nonce(prefix, i, i == chunks - 1)))
            plainLength = cipher.doFinal(sealed, 0, n, plain, 0)
        } catch (e: GeneralSecurityException) {
            throw IOException("safe file damaged or wrong key", e)
        }
        cached = i
    }

    /** Copies up to [count] bytes from [position] into [buffer] at [offset]. How many were copied; -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        if (position >= size) return -1
        if (count == 0) return 0
        val i = position / chunk
        load(i)
        val within = (position - i * chunk).toInt()
        val n = minOf(count, plainLength - within)
        System.arraycopy(plain, within, buffer, offset, n)
        return n
    }

    /** The whole content in memory. For photos and thumbnails; videos stream through [read]. */
    fun readAll(): ByteArray {
        val out = ByteArray(Math.toIntExact(size))
        var at = 0
        while (at < out.size) at += read(at.toLong(), out, at, out.size - at)
        return out
    }

    fun inputStream(): InputStream = object : InputStream() {
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            this@SafeReader.read(position, b, off, len).also { if (it > 0) position += it }
    }

    override fun close() = raf.close()
}

fun sha256(input: InputStream): ByteArray {
    val sha = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return sha.digest()
        sha.update(buffer, 0, n)
    }
}

fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** The folder a photo or video leaving the safe goes back to, or null if MediaStore no longer allows it there (then: Restored where Album saves). */
fun restorePath(path: String, isVideo: Boolean): String? {
    val folder = if (path.isEmpty() || path.endsWith('/')) path else "$path/"
    return folder.takeIf { it.isNotEmpty() && canHold(it, isVideo) }
}

/** What the safe keeps about [m]: enough to show it, and to put it back where it was with its date and favourite. */
fun safeItemOf(m: Media, mime: String, now: Long, bucket: Int = SafeItem.SAFE) = SafeItem(
    name = m.name, mime = mime, isVideo = m.isVideo, taken = m.taken, width = m.width, height = m.height,
    durationMs = m.durationMs, path = m.path, favorite = m.isFavorite, size = 0, sha256 = null,
    original = m.uri.toString(), added = now, bucket = bucket,
)

/** The page a bucket's items are on, as messages name it. */
fun placeOf(bucket: Int) = if (bucket == SafeItem.ID_CARDS) "ID cards" else "Private safe"

/** Snackbar text after moving into [place]; null when nothing was tried or the user said no to Android's pop-up. */
fun movedInMessage(moved: Int, failed: Int, place: String = "Private safe"): String? = when {
    failed == 0 && moved == 0 -> null
    moved == 0 -> "Couldn't move ${itemCount(failed)} to $place. ${if (failed == 1) "It stays" else "They stay"} in the Library."
    failed == 0 -> "Moved ${itemCount(moved)} to $place"
    else -> "Moved ${itemCount(moved)} to $place. $failed couldn't be moved and ${if (failed == 1) "stays" else "stay"} in the Library."
}

/** Why setup and Forgot PIN can't start: both check it's you with the screen lock (or a fingerprint, which needs one). */
const val NEEDS_SCREEN_LOCK = "Set a screen lock in Android settings first. It's how Album checks it's you."

/** What a prompt says once Android has erased the Keystore key it needed (the screen lock was turned off). */
const val KEY_ERASED = "Android erased this key when the screen lock was turned off"

/** Why a move into the safe is refused without ACCESS_MEDIA_LOCATION: Android would hand over photos with GPS blanked. */
const val SAFE_NEEDS_LOCATION = "Album isn't allowed to read photo locations, so moving would lose where these were taken. Nothing was moved."

/** Snackbar text after moving out of [place]. */
fun movedOutMessage(moved: Int, failed: Int, place: String = "Private safe"): String? = when {
    failed == 0 && moved == 0 -> null
    moved == 0 -> "Couldn't move ${itemCount(failed)} out. ${if (failed == 1) "It stays" else "They stay"} in $place."
    failed == 0 -> "Moved ${itemCount(moved)} back to the Library"
    else -> "Moved ${itemCount(moved)} back to the Library. $failed couldn't be moved and ${if (failed == 1) "stays" else "stay"} in $place."
}

/**
 * The private safe: photos and videos encrypted in app storage (filesDir/safe, excluded from backup and device transfer),
 * listed in safe.db, and gone from MediaStore, so the Library, search and other apps never see them.
 *
 * Keys: one random 256-bit safe key encrypts every file (through [SafeCipher]). It is stored only wrapped, twice:
 * - in "key", by an Android Keystore key that needs the fingerprint or screen lock for every single use (BiometricPrompt
 *   with a CryptoObject): the fingerprint unlock and Forgot PIN;
 * - in "pin", by the privacy PIN together with a second Keystore key, so the file is useless off this phone (see
 *   [PrivacyLock]). v1.0 safes get it at their first unlock after the update.
 * Unwrapping a data key, rather than encrypting the files with the Keystore key itself, is deliberate: Keystore runs
 * each operation in secure hardware over IPC, far too slow for gigabytes of video, and a key with a validity window
 * would expire mid-video or mid-move, while one unwrap per unlock binds the fingerprint to exactly that unlock. The safe
 * key stays in memory only while privacy is unlocked and is wiped when it locks; a move holds its own copy until it ends.
 *
 * Android erases Keystore keys that need the screen lock when the screen lock is turned off. The PIN's wrapping doesn't
 * need the screen lock, so the safe still opens with the PIN; the fingerprint and Forgot PIN come back once the screen
 * lock does ([rearm]). Adding a fingerprint does not erase the key (setInvalidatedByBiometricEnrollment false).
 *
 * ID cards ([IdCards]) keep their files here too, in filesDir/safe/id, listed in the same table under their own bucket
 * and encrypted with a key of their own. Nothing here opens them, and the safe's reset never touches them.
 */
object Safe {
    private const val ALIAS = "album_safe" // v1.0's wrapping key; one re-armed later has an alias of its own, named in the key file
    private val KEY_MAGIC = "ALSK".toByteArray()
    private const val KEY_VERSION: Byte = 2
    private const val THUMB = 512
    private const val RESERVE = 100L shl 20 // left free, so a move never fills the phone to the last byte
    private lateinit var app: Context
    private val dao by lazy { Room.databaseBuilder(app, SafeDb::class.java, "safe.db").build().dao() }
    internal val dir by lazy { File(app.filesDir, "safe").apply { mkdirs() } }
    internal val idDir by lazy { File(dir, "id").apply { mkdirs() } } // ID cards' files and key files
    private val keyFile by lazy { AtomicFile(File(dir, "key")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopping = false

    private val openKey = MutableStateFlow<ByteArray?>(null)

    /** The safe key while the safe is open, else null. Never copy it anywhere but memory. */
    val key = openKey.asStateFlow()

    private val lostWrap = MutableStateFlow(false)

    /**
     * True once the safe is found set up but Android has erased the Keystore key of its fingerprint-or-screen-lock wrapping
     * (the screen lock was turned off). With a privacy PIN the safe still opens; without one it can't: see [reset].
     */
    val wrapLost = lostWrap.asStateFlow()

    // Decrypted grid thumbnails, so a tile scrolled back into view doesn't decrypt again. Memory only, emptied on lock.
    private val thumbs = object : LruCache<Long, ImageBitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    /** Everything in the safe, newest first. Moves still waiting for Android to delete the original aren't in it yet. */
    val items by lazy { dao.items(SafeItem.SAFE) }

    /** Everything in ID cards, the same way. */
    val idItems by lazy { dao.items(SafeItem.ID_CARDS) }

    /** Whether nothing has been moved into [bucket] yet (or everything was moved out), so the first move explains the safe. */
    suspend fun isEmpty(bucket: Int = SafeItem.SAFE) = dao.count(bucket) == 0

    /** Call before anything else; safe to call again. Finishes moves the app was closed in the middle of. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        job = scope.launch {
            // Without full photo access every original would look deleted; leave them waiting for a later start
            val full = listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO).all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            if (full) runCatching { settle(app.contentResolver, dao.waiting()) }.onFailure { CrashLog.note("safe settle failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Whether the safe has a key yet. Setting up the privacy PIN makes one. */
    val isSetUp get() = keyFile.baseFile.exists()

    fun open(key: ByteArray) {
        openKey.value = key
    }

    /** Locks the safe: wipes its key and the decrypted thumbnails from memory; the screen drops the rest. */
    @Synchronized
    fun lock() {
        openKey.value?.fill(0)
        openKey.value = null
        thumbs.evictAll()
    }

    // ---------- Unlocking ----------

    /** How Android's prompt checks it's you, and what the safe key is then used for. */
    enum class Proof {
        /** The quick way in: a fingerprint only. "Use PIN" closes the prompt. */
        Fingerprint,

        /** Forgot PIN: the fingerprint, or the screen lock on a phone without one. */
        Recover,

        /** Setting up the privacy PIN: the fingerprint or screen lock, making the safe key (or opening a v1.0 safe's). */
        SetUp,
    }

    /**
     * The Keystore cipher BiometricPrompt unlocks: [fresh] wraps a new safe key (setup), [rearm] wraps the open one again
     * under a new Keystore key ([alias]), else it unwraps the saved one.
     */
    private class Unlock(val cipher: Cipher, val fresh: Boolean, val wrapped: ByteArray?, val rearm: ByteArray? = null, val alias: String? = null)

    /** The key file: the Keystore alias that wraps the safe key, its IV and the wrapped key. */
    private class Saved(val alias: String, val iv: ByteArray, val wrapped: ByteArray)

    /** v1.0 wrote the IV and the wrapped key; a re-armed file starts "ALSK", 2 and names its Keystore alias. */
    private fun saved(): Saved {
        val bytes = keyFile.readFully()
        val b = ByteBuffer.wrap(bytes)
        val alias = if (bytes.size > 6 && bytes.copyOfRange(0, 4).contentEquals(KEY_MAGIC)) {
            b.position(4)
            if (b.get() != KEY_VERSION) throw IOException("unknown key file")
            String(ByteArray(b.get().toInt()).also { b.get(it) })
        } else {
            ALIAS
        }
        val iv = ByteArray(b.get().toInt()).also { b.get(it) }
        return Saved(alias, iv, ByteArray(b.remaining()).also { b.get(it) })
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The saved wrapping's cipher, ready to unwrap; a gone or erased Keystore key throws KeyPermanentlyInvalidatedException. */
    private fun openCipher(): Unlock {
        val saved = saved()
        val wrapping = keyStore().getKey(saved.alias, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, saved.iv))
        return Unlock(cipher, fresh = false, wrapped = saved.wrapped)
    }

    private fun unlockCipher(): Unlock {
        if (keyFile.baseFile.exists()) return openCipher()
        // A Keystore key left by a setup that never finished wraps nothing yet, so it's reused, or replaced if Android
        // erased it. Never deleted otherwise: a second unlock running alongside may be about to wrap with it.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val wrapping = keyStore().getKey(ALIAS, null) as? SecretKey ?: newWrappingKey(ALIAS)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        } catch (e: KeyPermanentlyInvalidatedException) {
            cipher.init(Cipher.ENCRYPT_MODE, newWrappingKey(ALIAS))
        }
        return Unlock(cipher, fresh = true, wrapped = null)
    }

    internal fun newWrappingKey(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 0: every use needs its own fingerprint or screen lock, given through BiometricPrompt's CryptoObject
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(false) // a new fingerprint must never lock you out of your photos
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** Writes the key file in one go: flushed to disk before it replaces the old one, so a crash can't leave half a key. */
    private fun writeKeyFile(alias: String?, iv: ByteArray, wrapped: ByteArray) {
        val body = ByteBuffer.allocate(1 + iv.size + wrapped.size).put(iv.size.toByte()).put(iv).put(wrapped).array()
        val bytes = alias?.toByteArray()?.let { KEY_MAGIC + byteArrayOf(KEY_VERSION, it.size.toByte()) + it + body } ?: body
        val out = keyFile.startWrite()
        try {
            out.write(bytes)
            keyFile.finishWrite(out)
        } catch (e: Exception) {
            keyFile.failWrite(out)
            throw e
        }
    }

    /**
     * After the fingerprint or screen lock: the safe key. A new one is saved (durably) before anything uses it, and never
     * over an existing one, which may already encrypt photos: of two setups at once, the second fails instead. A re-arm
     * saves the new wrapping first, then drops the erased Keystore key it replaces.
     */
    private fun finishUnlock(unlock: Unlock): ByteArray {
        unlock.rearm?.let { key ->
            val old = runCatching { saved().alias }.getOrNull()
            writeKeyFile(unlock.alias, unlock.cipher.iv, unlock.cipher.doFinal(key))
            if (old != null && old != unlock.alias) runCatching { keyStore().deleteEntry(old) }
            lostWrap.value = false
            return key
        }
        if (!unlock.fresh) return unlock.cipher.doFinal(unlock.wrapped)
        check(!keyFile.baseFile.exists()) { "already set up" }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrapped = unlock.cipher.doFinal(key)
        writeKeyFile(null, unlock.cipher.iv, wrapped)
        return key
    }

    /** What Android's prompt may accept for [proof], given what the phone has set up. */
    private fun allowedFor(context: Context, proof: Proof): Int = when (proof) {
        Proof.Fingerprint -> BIOMETRIC_STRONG
        Proof.Recover -> if (hasFingerprint(context)) BIOMETRIC_STRONG else DEVICE_CREDENTIAL
        Proof.SetUp -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }

    /** Whether the phone has a strong biometric (a fingerprint, on a Moto) enrolled. */
    fun hasFingerprint(context: Context) =
        context.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Whether the phone has a screen lock (or a fingerprint, which needs one): Forgot PIN and setup rely on it. */
    fun hasScreenLock(context: Context) =
        context.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Asks for [proof] with [title], then gives the safe key to [onKey] (a setup creates it first). [onFail] gets a message
     * to show, or null when the person just cancelled (or chose the PIN instead). Both are called on the main thread; the
     * Keystore and the key file are used off it. [cancel] closes the prompt, say when the screen asking for it goes away.
     */
    fun prompt(
        context: Context,
        title: String,
        description: String?,
        proof: Proof,
        cancel: CancellationSignal,
        onKey: (ByteArray) -> Unit,
        onFail: (String?) -> Unit,
    ) {
        val allowed = allowedFor(context, proof)
        if (context.getSystemService(BiometricManager::class.java).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onFail(if (proof == Proof.Fingerprint) "Add a fingerprint in Android settings to use it here" else NEEDS_SCREEN_LOCK)
            return
        }
        authenticate(context, title, description, allowed, if (proof == Proof.Fingerprint) "Use PIN" else "Cancel", cancel, onKey, onFail) {
            if (proof == Proof.SetUp) unlockCipher() else openCipher()
        }
    }

    /**
     * Wraps [key] (the open safe key, a copy this call wipes) again under a new Keystore key, after the fingerprint or screen
     * lock: for when Android erased the old one because the screen lock was off for a while. Brings back the fingerprint
     * unlock and Forgot PIN. [onFail] as in [prompt].
     */
    fun rearm(context: Context, key: ByteArray, cancel: CancellationSignal, onDone: () -> Unit, onFail: (String?) -> Unit) {
        val allowed = allowedFor(context, Proof.SetUp)
        if (context.getSystemService(BiometricManager::class.java).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            key.fill(0)
            onFail(NEEDS_SCREEN_LOCK)
            return
        }
        val alias = "${ALIAS}_${System.currentTimeMillis()}"
        val done = { k: ByteArray -> k.fill(0); onDone() }
        authenticate(context, "Turn Forgot PIN back on", null, allowed, "Cancel", cancel, done, { key.fill(0); onFail(it) }) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, newWrappingKey(alias))
            Unlock(cipher, fresh = false, wrapped = null, rearm = key, alias = alias)
        }
    }

    private fun authenticate(
        context: Context,
        title: String,
        description: String?,
        allowed: Int,
        negative: String,
        cancel: CancellationSignal,
        onKey: (ByteArray) -> Unit,
        onFail: (String?) -> Unit,
        cipherFor: () -> Unlock,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            val unlock = try {
                withContext(Dispatchers.IO) { cipherFor() }
            } catch (e: KeyPermanentlyInvalidatedException) {
                lostWrap.value = true // the safe's screen then offers Reset safe, or the PIN's flow re-arms it
                onFail(KEY_ERASED)
                return@launch
            } catch (e: Exception) {
                CrashLog.note("safe unlock failed: ${e.javaClass.simpleName}")
                onFail("Couldn't check it's you. Try again.")
                return@launch
            }
            if (cancel.isCanceled) return@launch
            val prompt = BiometricPrompt.Builder(context)
                .setTitle(title)
                .apply { description?.let(::setDescription) }
                .setAllowedAuthenticators(allowed)
                // Android asks for a way out when the screen lock isn't one of the choices
                .apply { if (allowed and DEVICE_CREDENTIAL == 0) setNegativeButton(negative, context.mainExecutor) { _, _ -> onFail(null) } }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(unlock.cipher), cancel, context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        scope.launch(Dispatchers.Main.immediate) {
                            runCatching { withContext(Dispatchers.IO) { finishUnlock(unlock) } }
                                .onSuccess(onKey)
                                .onFailure {
                                    CrashLog.note("safe key failed: ${it.javaClass.simpleName}")
                                    unlock.rearm?.fill(0)
                                    onFail("Couldn't check it's you. Try again.")
                                }
                        }
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        val cancelled = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        unlock.rearm?.fill(0)
                        onFail(if (cancelled) null else message.toString())
                    }
                },
            )
        }
    }

    /** Whether the safe is set up but Android erased the Keystore key of its key file. Only that, never a doubt, counts. */
    private fun wrapIsLost(): Boolean {
        if (!keyFile.baseFile.exists()) return false
        return try {
            openCipher() // only reads the key file and opens the Keystore key; nothing is created
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Checks [wrapLost] without asking for anything, so a safe that can't open says so before anyone tries. */
    suspend fun checkKey() = withContext(Dispatchers.IO) { lostWrap.value = wrapIsLost() }

    /**
     * Empties a safe nothing can open any more: Android erased the Keystore key of its key file (or the file is gone), and
     * there's no privacy PIN or nobody remembers it. Nobody can decrypt its files, so this frees their space and lets
     * privacy be set up again, with a new PIN. Checks again first: a key file that still opens is never touched. Callers
     * confirm with a hold. Hidden people and albums stay hidden.
     */
    fun reset() = start(safeMessages, null) {
        if (keyFile.baseFile.exists() && !wrapIsLost()) return@start null
        CrashLog.note("safe reset")
        lock()
        dao.clear(SafeItem.SAFE)
        val alias = runCatching { saved().alias }.getOrNull()
        // The key file, the PIN's file and ID cards' escrow with them: all wrap the lost key. ID cards' own folder stays,
        // less the copy of their key sealed to that escrow, which nothing can open any more.
        dir.listFiles()?.forEach { if (it != idDir) it.deleteRecursively() }
        File(idDir, IdCards.PIN_COPY).delete()
        alias?.let { runCatching { keyStore().deleteEntry(it) } }
        thumbs.evictAll()
        lostWrap.value = false
        PrivacyLock.forgetPin()
        null
    }

    // ---------- Reading ----------

    private fun folder(item: SafeItem) = if (item.bucket == SafeItem.ID_CARDS) idDir else dir
    private fun file(item: SafeItem) = File(folder(item), "${item.id}.bin")
    private fun thumbFile(item: SafeItem) = File(folder(item), "${item.id}.thumb")

    fun reader(item: SafeItem, key: ByteArray) = SafeReader(file(item), key)

    /** The grid thumbnail made at move-in, decrypted into memory (and kept there until the safe locks). Null if it couldn't be made or read. */
    fun thumbnail(item: SafeItem, key: ByteArray): ImageBitmap? {
        thumbs[item.id]?.let { return it }
        val image = runCatching {
            val jpeg = SafeReader(thumbFile(item), key).use { it.readAll() }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).asImageBitmap()
        }.getOrNull() ?: return null
        synchronized(this) { if (openKey.value === key) thumbs.put(item.id, image) } // never kept for a safe that locked meanwhile
        return image
    }

    /** The full photo, decrypted into memory and decoded upright, at most [maxSide] pixels on its long side. */
    fun photo(item: SafeItem, key: ByteArray, maxSide: Int = 4096): ImageBitmap {
        val bytes = reader(item, key).use { it.readAll() }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > maxSide) decoder.setTargetSampleSize((long + maxSide - 1) / maxSide)
        }.asImageBitmap()
    }

    // ---------- Moving in and out ----------

    /** [current] is 1-based: "Moving 3 of 40". [inSafe]: started from the safe (moving out), so the safe's screen shows it. */
    class Progress(val current: Int, val total: Int, val inSafe: Boolean)

    /** Android's pop-up deleting the originals, shown by [rememberMoveToSafe]. */
    class Ask(val request: PendingIntent) {
        internal val answer = CompletableDeferred<Boolean>()
        internal var shown = false
    }

    val progress = MutableStateFlow<Progress?>(null)
    val asking = MutableStateFlow<Ask?>(null)

    /** Results for the Library's snackbar (moving in) and the safe's (moving out, deleting), kept until shown. */
    val libraryMessages = Channel<String>(Channel.UNLIMITED)
    val safeMessages = Channel<String>(Channel.UNLIMITED)

    /** Finishes the item in hand, then stops. Each item is moved whole or not at all. */
    fun stop() {
        stopping = true
    }

    fun answer(granted: Boolean) {
        asking.value?.answer?.complete(granted)
    }

    /**
     * Moves [items] into the safe, or ID cards ([bucket]), with that bucket's [key] (a copy this job wipes when done). Each
     * is encrypted, synced to disk, then decrypted again and checked against a fresh read of the original. Only then does
     * Android's delete request remove the verified originals; anything that failed keeps its original and nothing of it
     * stays in the safe.
     */
    fun moveIn(context: Context, items: List<Media>, key: ByteArray, bucket: Int = SafeItem.SAFE) = start(libraryMessages, key) {
        val resolver = context.contentResolver
        if (context.checkSelfPermission(ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) return@start SAFE_NEEDS_LOCATION
        if (items.sumOf { it.size } + RESERVE > dir.usableSpace) return@start "Not enough space to move ${itemCount(items.size)} to ${placeOf(bucket)}"
        val added = ArrayList<SafeItem>()
        var tried = 0
        for (m in items) {
            if (stopping) break
            progress.value = Progress(++tried, items.size, inSafe = false)
            add(context, m, key, bucket)?.let { added += it }
        }
        progress.value = null
        // With Media management on, Android deletes without asking; that's fine, every copy here is verified
        if (added.isNotEmpty()) ask(MediaStore.createDeleteRequest(resolver, added.map { Uri.parse(it.original) }))
        movedInMessage(settle(resolver, added), tried - added.size, placeOf(bucket))
    }

    /** Puts [items] back in the Library with [key] (a copy this job wipes), each checked before its safe copy is deleted. */
    fun moveOut(context: Context, items: List<SafeItem>, key: ByteArray) = start(safeMessages, key) {
        if (items.sumOf { it.size } + RESERVE > dir.usableSpace) return@start "Not enough space to move ${itemCount(items.size)} out"
        var moved = 0
        var tried = 0
        for (item in items) {
            if (stopping) break
            progress.value = Progress(++tried, items.size, inSafe = true)
            if (restore(context, item, key)) moved++
        }
        movedOutMessage(moved, tried - moved, placeOf(items.firstOrNull()?.bucket ?: SafeItem.SAFE))
    }

    /** Deletes [items] and their files for good. Nothing else holds a copy, so callers confirm with a hold first. */
    fun delete(items: List<SafeItem>) = start(safeMessages, null) {
        items.forEach { remove(it) }
        "Deleted ${itemCount(items.size)} forever"
    }

    /**
     * Empties ID cards once nothing can open them ([IdCards.reset]): [stillLost] checks again first, in turn with any move.
     * Their folder goes whole, files and key file; the safe is never touched.
     */
    internal fun resetIdCards(stillLost: suspend () -> Boolean) = start(safeMessages, null) {
        if (!stillLost()) return@start null
        CrashLog.note("id cards reset")
        dao.clear(SafeItem.ID_CARDS)
        idDir.deleteRecursively()
        idDir.mkdirs()
        null
    }

    private fun start(messages: Channel<String>, key: ByteArray?, work: suspend () -> String?): Job {
        val previous = job
        return scope.launch {
            previous?.join() // one at a time, and never before init's settling is done
            stopping = false
            try {
                work()?.let { messages.trySend(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.note("safe job failed: ${e.javaClass.simpleName}")
                messages.trySend("Something went wrong. Nothing was lost.")
            } finally {
                key?.fill(0)
                progress.value = null
                asking.value = null
            }
        }.also { job = it }
    }

    private suspend fun ask(request: PendingIntent): Boolean {
        val ask = Ask(request)
        asking.value = ask
        return ask.answer.await().also { asking.value = null }
    }

    /**
     * Copies [m] into the safe and checks it. The row goes in first, marked with its original, so a copy cut short by a
     * crash is found and cleaned up by [settle]. Returns the verified item, or null (nothing of it kept) if anything failed.
     */
    private suspend fun add(context: Context, m: Media, key: ByteArray, bucket: Int): SafeItem? {
        val resolver = context.contentResolver
        // Without this Android blanks the GPS tags in what it reads out, and the photo would come back without its place.
        // It needs ACCESS_MEDIA_LOCATION, which moveIn checks; without it the read fails and the original stays.
        val source = MediaStore.setRequireOriginal(m.uri)
        var item: SafeItem? = null
        return try {
            val row = safeItemOf(m, checkNotNull(resolver.getType(m.uri)), System.currentTimeMillis(), bucket)
            item = row.copy(id = dao.insert(row))
            val id = item.id
            val sha = write(file(item)) { out -> checkNotNull(resolver.openInputStream(source)).use { SafeCipher.encrypt(key, it, out) } }
            // Best effort: without a thumbnail the tile is plain, the photo itself is fine
            runCatching {
                val thumb = resolver.loadThumbnail(m.uri, Size(THUMB, THUMB), null)
                val jpeg = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                thumb.recycle()
                write(thumbFile(item)) { out -> SafeCipher.encrypt(key, ByteArrayInputStream(jpeg), out) }
            }.onFailure { thumbFile(item).delete() }
            // Decrypt what's on disk and read the original again: both must be the very bytes that were encrypted
            val (size, copy) = SafeReader(file(item), key).use { it.size to sha256(it.inputStream()) }
            val again = checkNotNull(resolver.openInputStream(source)).use(::sha256)
            check(copy.contentEquals(sha) && again.contentEquals(sha)) { "copy differs" }
            dao.verified(id, size, sha.hex())
            item.copy(size = size, sha256 = sha.hex())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.note("safe add failed: ${e.javaClass.simpleName}")
            item?.let { remove(it) } // the original was only read
            null
        }
    }

    /** Encrypts into [file] and makes sure it's on the disk, not just in a write cache, before an original is deleted. */
    private fun write(file: File, encrypt: (OutputStream) -> ByteArray): ByteArray = FileOutputStream(file).use { out ->
        encrypt(out).also { out.fd.sync() }
    }

    /**
     * Finishes moves waiting for their originals to leave the Library: where the original is gone the safe copy is now
     * the photo; where it's still there (Android's pop-up was declined, or the app closed first) the safe copy is dropped.
     * When in doubt it keeps the safe copy. Returns how many moved.
     */
    private suspend fun settle(resolver: ContentResolver, items: List<SafeItem>): Int {
        val moved = ArrayList<Long>()
        for (item in items) {
            val original = item.original?.let(Uri::parse) ?: continue
            when (exists(resolver, original)) {
                true -> remove(item)
                false -> {
                    dao.moved(item.id)
                    moved += ContentUris.parseId(original)
                }
                null -> {} // couldn't tell; ask again next start
            }
        }
        if (moved.isNotEmpty()) forget(moved)
        return moved.size
    }

    /**
     * The moved originals' labels, text and faces leave the smart index and People now, not at their next pass, so an ID
     * card's text doesn't linger on disk; the widget stops showing them. Best effort: the move itself is already done.
     */
    private suspend fun forget(mediaIds: List<Long>) {
        runCatching { SmartIndex.forget(mediaIds) }.onFailure { CrashLog.note("safe: index forget failed: ${it.javaClass.simpleName}") }
        runCatching { People.forget(mediaIds) }.onFailure { CrashLog.note("safe: people forget failed: ${it.javaClass.simpleName}") }
        runCatching { MemoryWidget.refresh(app) }.onFailure { CrashLog.note("safe: widget refresh failed: ${it.javaClass.simpleName}") }
    }

    /** Whether MediaStore still has [uri], trashed or not; null if it can't say. MediaStore never reuses an id. */
    private fun exists(resolver: ContentResolver, uri: Uri): Boolean? = runCatching {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(uri, arrayOf(MediaColumns._ID), args, null)?.use { it.count > 0 }
    }.getOrNull()

    /**
     * Writes [item] back into MediaStore as a new file, hidden (IS_PENDING) until every byte is in and checked against the
     * hash taken when it went in. Only then is it published and the safe copy deleted; if anything fails the new file is
     * removed and the safe copy stays.
     */
    private suspend fun restore(context: Context, item: SafeItem, key: ByteArray): Boolean {
        val resolver = context.contentResolver
        // The safe never noted which storage a photo came from, so going back to its folder means the phone's
        val to = restorePath(item.path, item.isVideo)?.let { SaveLocation(folder = it) } ?: SavePrefs.target(context).inside("Restored")
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, item.name)
            put(MediaColumns.RELATIVE_PATH, to.folder)
            put(MediaColumns.MIME_TYPE, item.mime)
            put(MediaColumns.DATE_TAKEN, item.taken)
            put(MediaColumns.IS_PENDING, 1)
        }
        val collection = if (item.isVideo) MediaStore.Video.Media.getContentUri(to.volume) else MediaStore.Images.Media.getContentUri(to.volume)
        var uri: Uri? = null
        return try {
            uri = checkNotNull(resolver.insert(collection, values))
            val sha = MessageDigest.getInstance("SHA-256")
            reader(item, key).use { r ->
                ParcelFileDescriptor.AutoCloseOutputStream(checkNotNull(resolver.openFileDescriptor(uri, "w"))).use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var at = 0L
                    while (true) {
                        val n = r.read(at, buffer, 0, buffer.size)
                        if (n < 0) break
                        sha.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        at += n
                    }
                    out.fd.sync() // on the disk, not just in a write cache, before the safe copy is deleted
                }
            }
            val written = sha.digest().hex()
            val stored = checkNotNull(resolver.openInputStream(uri)).use { sha256(it).hex() }
            check(written == stored && (item.sha256 == null || item.sha256 == stored)) { "restored copy differs" }
            check(resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            // Publishing rescans the file and may recompute DATE_TAKEN (see AlbumOps.copy); best effort, the file is complete
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaColumns.DATE_TAKEN, item.taken) }, null, null) }
            if (item.favorite) runCatching { resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_FAVORITE, 1) }, null, null) }
            remove(item)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.note("safe restore failed: ${e.javaClass.simpleName}")
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    /**
     * Row first, then files: a crash in between leaves an unlisted file, never a listed item with no file. Unlisted files
     * are not swept up later on purpose: a sweep would take every file with it if the database were ever lost.
     */
    private suspend fun remove(item: SafeItem) {
        thumbs.remove(item.id)
        dao.delete(item.id)
        file(item).delete()
        thumbFile(item).delete()
    }
}
