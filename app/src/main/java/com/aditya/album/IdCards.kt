package com.aditya.album

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** How ID cards open on this phone right now. See [idGate]. */
enum class IdGate {
    /** Android's prompt: the screen lock or fingerprint. The first time, it also makes their key. */
    ScreenLock,

    /** The phone has no screen lock: the privacy PIN opens the copy of the key it keeps (made the first time). */
    Pin,

    /** The screen lock is back, but Android erased its key while it was off: the PIN, then the screen lock to re-arm it. */
    PinThenScreenLock,

    /** No screen lock and no privacy PIN: nothing can guard them yet. */
    NoLock,

    /** Android erased the screen lock's key and the PIN keeps no copy: nothing can open them any more. */
    Lost,
}

/**
 * Which way into ID cards there is. [screenLock]: the phone has a secure screen lock. [pin]: a privacy PIN is set.
 * [setUp]: ID cards have a key, or items. [wrapped]: the screen lock's wrapping of the key is there and Android isn't
 * known to have erased its Keystore key (only a sure erasure counts). [pinCopy]: the PIN can open a copy of the key.
 * While the screen lock can open them, the PIN never does; it's the way in only on a phone without one.
 */
fun idGate(screenLock: Boolean, pin: Boolean, setUp: Boolean, wrapped: Boolean, pinCopy: Boolean): IdGate = when {
    screenLock && (wrapped || !setUp) -> IdGate.ScreenLock
    pin && (pinCopy || !setUp) -> if (screenLock) IdGate.PinThenScreenLock else IdGate.Pin
    setUp && !wrapped -> IdGate.Lost
    else -> IdGate.NoLock
}

/** What For you's confirmation says about where ID photos go, for a phone with or without a [screenLock]. */
fun idMoveNote(screenLock: Boolean) =
    "They leave the Library and search, and open with your ${if (screenLock) "screen lock" else "privacy PIN"}."

/** A short record: a length-prefixed head (2 bytes), a length-prefixed IV (1 byte), then the sealed bytes. */
private fun pack(head: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray =
    ByteBuffer.allocate(2 + head.size + 1 + iv.size + sealed.size)
        .putShort(head.size.toShort()).put(head).put(iv.size.toByte()).put(iv).put(sealed).array()

private fun unpack(record: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
    val b = ByteBuffer.wrap(record)
    val head = ByteArray(b.short.toInt()).also { b.get(it) }
    val iv = ByteArray(b.get().toInt()).also { b.get(it) }
    return Triple(head, iv, ByteArray(b.remaining()).also { b.get(it) })
}

/**
 * ID cards' key file: the Keystore alias of the key that wraps it (one that needs the screen lock or fingerprint for every
 * use), the IV, and the wrapped key.
 */
class WrappedKey(val alias: String, val iv: ByteArray, val wrapped: ByteArray) {
    fun encode() = pack(alias.toByteArray(), iv, wrapped)

    companion object {
        fun decode(bytes: ByteArray) = unpack(bytes).let { (alias, iv, wrapped) -> WrappedKey(String(alias), iv, wrapped) }
    }
}

/**
 * Seals small secrets to a public key, so a copy can be made while only the public half is at hand: P-256 ECDH with a key
 * made for each seal, HMAC-SHA256 of the shared secret (keyed by that key's public half) as the AES-256-GCM key. ID cards
 * seal their key to the escrow whenever they're open; only the safe key, which the privacy PIN opens, unseals the escrow's
 * private half ([lock], [privateOf]). So the PIN keeps a way in without ever being typed while ID cards are open.
 */
object Escrow {
    fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun seal(secret: ByteArray, to: PublicKey): ByteArray {
        val once = newPair()
        val head = once.public.encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, shared(once.private, to, head))
        cipher.updateAAD(head)
        return pack(head, cipher.iv, cipher.doFinal(secret))
    }

    /** The secret in [record]. A wrong key or any changed byte throws. */
    fun open(record: ByteArray, with: PrivateKey): ByteArray {
        val (head, iv, sealed) = unpack(record)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, shared(with, publicKey(head), head), GCMParameterSpec(128, iv))
        cipher.updateAAD(head)
        return cipher.doFinal(sealed)
    }

    /** The escrow's file: [pair]'s public half in the clear (but authenticated), its private half sealed under [safeKey]. */
    fun lock(pair: KeyPair, safeKey: ByteArray): ByteArray {
        val head = pair.public.encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, under(safeKey))
        cipher.updateAAD(head)
        val private = pair.private.encoded
        return pack(head, cipher.iv, cipher.doFinal(private)).also { private.fill(0) }
    }

    fun publicOf(file: ByteArray): PublicKey = publicKey(unpack(file).first)

    /** The escrow's private half, with the safe key it was locked under. Another key or any changed byte throws. */
    fun privateOf(file: ByteArray, safeKey: ByteArray): PrivateKey {
        val (head, iv, sealed) = unpack(file)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, under(safeKey), GCMParameterSpec(128, iv))
        cipher.updateAAD(head)
        val private = cipher.doFinal(sealed)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(private)).also { private.fill(0) }
    }

    // A key of its own from the safe key, which is for files only (see SafeCipher.fileKey)
    private fun under(safeKey: ByteArray) =
        SecretKeySpec(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(safeKey, "HmacSHA256")) }.doFinal("album id escrow".toByteArray()), "AES")

    private fun shared(private: PrivateKey, public: PublicKey, salt: ByteArray): SecretKeySpec {
        val secret = KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(public, true) }.generateSecret()
        val key = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(secret)
        secret.fill(0)
        return SecretKeySpec(key, "AES")
    }

    private fun publicKey(x509: ByteArray) = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))
}

/**
 * ID cards: Aadhaar, PAN, passport and other ID photos, kept like Private safe (encrypted by [SafeCipher] in
 * filesDir/safe/id, listed in safe.db under their own bucket, gone from MediaStore) but behind a gate of their own: the
 * phone's screen lock or fingerprint, never the privacy PIN, unless the phone has no screen lock. Opening one never opens
 * the other.
 *
 * Keys: one random 256-bit ID key encrypts every file. It is stored only wrapped:
 * - in id/key, by an Android Keystore key that needs the screen lock or fingerprint for every use (BiometricPrompt with a
 *   CryptoObject), as Private safe's own key file;
 * - in id/pin, sealed to the [Escrow], whose private half only the safe key opens, and so the privacy PIN. It's written
 *   whenever ID cards are open and the escrow exists (made the first time privacy unlocks), without asking for the PIN.
 *   Album only uses it when the phone has no screen lock, or, with the screen lock as well, after Android erased the
 *   Keystore key.
 * Android erases Keystore keys that need the screen lock when the screen lock is turned off. With the PIN's copy, ID cards
 * still open with the PIN, and the screen lock is re-armed once it's back. Without a PIN there's no copy, and the open page
 * says so and offers to set one. A key whose wrappings are all gone can't be opened by anything: [reset].
 */
object IdCards {
    /** The PIN's copy of the key, in Safe.idDir. Private safe's reset deletes it along with the escrow it's sealed to. */
    const val PIN_COPY = "pin"
    private const val ALIAS = "album_id"
    private const val TRY_AGAIN = "Couldn't check it's you. Try again."
    private const val HOUR = 60 * 60 * 1000L
    private val keyFile by lazy { AtomicFile(File(Safe.idDir, "key")) }
    private val copyFile by lazy { AtomicFile(File(Safe.idDir, PIN_COPY)) }
    private val escrowFile by lazy { AtomicFile(File(Safe.dir, "escrow")) } // with the safe key's files: its reset deletes it
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    private val openKey = MutableStateFlow<ByteArray?>(null)

    /** The ID key while ID cards are open, else null. Never copy it anywhere but memory. */
    val key = openKey.asStateFlow()

    private val copied = MutableStateFlow(true)

    /** Whether the privacy PIN keeps a copy of the key, so turning off the screen lock doesn't lose ID cards. Set by [gate]. */
    val pinCopy = copied.asStateFlow()

    /** Call before anything else, on the main thread; safe to call again. Leaving Album locks ID cards. */
    fun init(context: Context) {
        if (started) return
        started = true
        PrivacyLock.init(context)
        val app = context.applicationContext
        (app as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                if (!activity.isChangingConfigurations) lock()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Plain copies made for sharing, once the app they went to has had an hour to read them
        scope.launch(Dispatchers.IO) { shared(app).takeIf { System.currentTimeMillis() - it.lastModified() > HOUR }?.deleteRecursively() }
    }

    /** Opens ID cards with [key] (this takes it). */
    @Synchronized
    fun open(key: ByteArray) {
        openKey.value?.fill(0)
        openKey.value = key
    }

    /** Locks ID cards: the key leaves memory and the page shows its lock. A move holds its own copy and carries on. */
    @Synchronized
    fun lock() {
        openKey.value?.fill(0)
        openKey.value = null
    }

    // A copy of the open key, taken under the same lock as lock()'s wipe, so it's never half wiped
    @Synchronized
    private fun openCopy(): ByteArray? = openKey.value?.copyOf()

    // ---------- Which way in ----------

    /** Which way in there is now (see [idGate]), reading the key files and asking Keystore. Also updates [pinCopy]. */
    suspend fun gate(context: Context): IdGate = withContext(Dispatchers.IO) {
        val pin = PrivacyLock.setUp.value
        val hasKey = keyFile.baseFile.exists()
        val hasCopy = copyFile.baseFile.exists()
        val copy = hasCopy && pin && escrowFile.baseFile.exists()
        copied.value = copy
        val setUp = hasKey || hasCopy || !Safe.isEmpty(SafeItem.ID_CARDS)
        idGate(Safe.hasScreenLock(context), pin, setUp, hasKey && !erased(), copy)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The key file's cipher, ready to unwrap; a gone or erased Keystore key throws KeyPermanentlyInvalidatedException. */
    private fun unwrapping(): Pair<Cipher, ByteArray> {
        val saved = WrappedKey.decode(keyFile.readFully())
        val wrapping = keyStore().getKey(saved.alias, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, saved.iv))
        return cipher to saved.wrapped
    }

    /** Whether Android surely erased the key file's Keystore key. A doubt is not an erasure. */
    private fun erased(): Boolean = try {
        unwrapping()
        false
    } catch (e: KeyPermanentlyInvalidatedException) {
        true
    } catch (e: Exception) {
        false
    }

    // ---------- Unlocking ----------

    /**
     * Android's prompt titled [title], with the screen lock or fingerprint; then [onKey] gets the ID key (made the first
     * time). [onFail] gets a message, [KEY_ERASED] when Android erased the Keystore key (ask [gate] again), or null when
     * the person cancelled. Both on the main thread. [cancel] closes the prompt.
     */
    fun prompt(context: Context, title: String, description: String?, cancel: CancellationSignal, onKey: (ByteArray) -> Unit, onFail: (String?) -> Unit) =
        authenticate(context, title, description, cancel, onKey, onFail) {
            if (keyFile.baseFile.exists()) {
                val (cipher, wrapped) = unwrapping()
                cipher to { c -> c.doFinal(wrapped).also(::keepCopy) }
            } else {
                // The first time. Never over a PIN's copy, which may already encrypt photos: that one needs the PIN first.
                check(!copyFile.baseFile.exists()) { "has a copy" }
                // A Keystore key left by a setup that was cancelled wraps nothing yet, so it's reused, or replaced if erased
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                try {
                    cipher.init(Cipher.ENCRYPT_MODE, keyStore().getKey(ALIAS, null) as? SecretKey ?: Safe.newWrappingKey(ALIAS))
                } catch (e: KeyPermanentlyInvalidatedException) {
                    cipher.init(Cipher.ENCRYPT_MODE, Safe.newWrappingKey(ALIAS))
                }
                cipher to { c ->
                    val key = ByteArray(32).also(SecureRandom()::nextBytes)
                    save(ALIAS, c, key, fresh = true)
                    keepCopy(key)
                    key
                }
            }
        }

    /**
     * After the privacy PIN (or Forgot PIN) gave [safeKey], which this takes: the ID key from the PIN's copy (made the
     * first time, on a phone without a screen lock). With [rearm], Android's prompt then wraps it under a new Keystore key
     * before [onKey] gets it: the screen lock is back, and the PIN alone never opens ID cards while there's one.
     */
    fun unlockWithPin(context: Context, safeKey: ByteArray, rearm: Boolean, title: String, cancel: CancellationSignal, onKey: (ByteArray) -> Unit, onFail: (String?) -> Unit) {
        scope.launch {
            val key = try {
                withPin(safeKey)
            } catch (e: Exception) {
                CrashLog.note("id cards pin failed: ${e.javaClass.simpleName}")
                onFail("Couldn't open ID cards. Try again.")
                return@launch
            }
            if (!rearm) return@launch onKey(key)
            CrashLog.note("id cards re-arm")
            authenticate(context, title, "Your screen lock opens ID cards again from now on", cancel, onKey, { key.fill(0); onFail(it) }) {
                val alias = "${ALIAS}_${System.currentTimeMillis()}"
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, Safe.newWrappingKey(alias)) }
                cipher to { c -> key.also { save(alias, c, it, fresh = false) } }
            }
        }
    }

    private suspend fun withPin(safeKey: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        try {
            val empty = Safe.isEmpty(SafeItem.ID_CARDS)
            synchronized(this@IdCards) {
                val escrow = escrow(safeKey)
                if (copyFile.baseFile.exists()) return@withContext Escrow.open(copyFile.readFully(), escrow.private)
                // Set up on a phone with no screen lock: a new key whose only copy is the PIN's, read back before it's used
                check(!keyFile.baseFile.exists() && empty) { "set up already" }
                val key = ByteArray(32).also(SecureRandom()::nextBytes)
                val record = Escrow.seal(key, escrow.public)
                check(Escrow.open(record, escrow.private).contentEquals(key)) { "copy doesn't open" }
                write(copyFile, record)
                copied.value = true
                key
            }
        } finally {
            safeKey.fill(0)
        }
    }

    /** The escrow, opened with [safeKey]; made the first time. Never replaced: a copy may be sealed to it. */
    @Synchronized
    private fun escrow(safeKey: ByteArray): KeyPair {
        if (escrowFile.baseFile.exists()) {
            val file = escrowFile.readFully()
            return KeyPair(Escrow.publicOf(file), Escrow.privateOf(file, safeKey))
        }
        val pair = Escrow.newPair()
        write(escrowFile, Escrow.lock(pair, safeKey))
        CrashLog.note("id cards escrow made")
        return pair
    }

    /**
     * With [safeKey] from the privacy PIN (a copy is taken; privacy opening, or the open page's "Keep a copy for your PIN"):
     * the escrow is made if there's none yet, and if ID cards are open their key gets its PIN's copy. Never opens ID cards.
     * Best effort, off the main thread.
     */
    internal fun keepCopyWith(safeKey: ByteArray) {
        val safe = safeKey.copyOf()
        scope.launch(Dispatchers.IO) {
            try {
                if (!escrowFile.baseFile.exists()) escrow(safe)
                openCopy()?.let { key -> keepCopy(key).also { key.fill(0) } }
            } catch (e: Exception) {
                CrashLog.note("id cards escrow failed: ${e.javaClass.simpleName}")
            } finally {
                safe.fill(0)
            }
        }
    }

    /** Seals [key] to the escrow, if there is one and no copy yet, so the PIN can still open ID cards if the screen lock goes. */
    private fun keepCopy(key: ByteArray) {
        runCatching {
            synchronized(this) {
                if (copyFile.baseFile.exists() || !escrowFile.baseFile.exists()) return
                write(copyFile, Escrow.seal(key, Escrow.publicOf(escrowFile.readFully())))
            }
            CrashLog.note("id cards copy kept")
            copied.value = PrivacyLock.setUp.value
        }.onFailure { CrashLog.note("id cards copy failed: ${it.javaClass.simpleName}") }
    }

    /** Wraps [key] with [cipher] (Android has just checked it's you) as the key file, in place of [alias]'s predecessor. */
    @Synchronized
    private fun save(alias: String, cipher: Cipher, key: ByteArray, fresh: Boolean) {
        val old = runCatching { WrappedKey.decode(keyFile.readFully()).alias }.getOrNull()
        // A new key never replaces one that may already encrypt photos: of two setups at once, the second fails instead
        if (fresh) check(!keyFile.baseFile.exists()) { "set up already" }
        write(keyFile, WrappedKey(alias, cipher.iv, cipher.doFinal(key)).encode())
        if (old != null && old != alias) runCatching { keyStore().deleteEntry(old) }
        CrashLog.note(if (fresh) "id cards set up" else "id cards re-armed")
    }

    /** Writes [file] in one go, flushed to disk before it replaces the old one. */
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /**
     * BiometricPrompt with the screen lock or fingerprint, over the cipher [prepare] makes off the main thread; once
     * Android has checked it's you, its other half turns the cipher into the ID key, also off the main thread.
     */
    private fun authenticate(
        context: Context,
        title: String,
        description: String?,
        cancel: CancellationSignal,
        onKey: (ByteArray) -> Unit,
        onFail: (String?) -> Unit,
        prepare: () -> Pair<Cipher, (Cipher) -> ByteArray>,
    ) {
        if (!Safe.hasScreenLock(context)) return onFail(NEEDS_SCREEN_LOCK)
        scope.launch {
            val (cipher, finish) = try {
                withContext(Dispatchers.IO) { prepare() }
            } catch (e: KeyPermanentlyInvalidatedException) {
                onFail(KEY_ERASED)
                return@launch
            } catch (e: Exception) {
                CrashLog.note("id cards unlock failed: ${e.javaClass.simpleName}")
                onFail(TRY_AGAIN)
                return@launch
            }
            if (cancel.isCanceled) return@launch
            BiometricPrompt.Builder(context)
                .setTitle(title)
                .apply { description?.let(::setDescription) }
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    BiometricPrompt.CryptoObject(cipher), cancel, context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { finish(cipher) } }
                                    .onSuccess(onKey)
                                    .onFailure {
                                        CrashLog.note("id cards key failed: ${it.javaClass.simpleName}")
                                        onFail(TRY_AGAIN)
                                    }
                            }
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            val cancelled = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                            onFail(if (cancelled) null else message.toString())
                        }
                    },
                )
        }
    }

    /**
     * Empties ID cards when nothing can open them any more ([IdGate.Lost]). Checks again first, so a key that still opens
     * is never touched; callers confirm with a hold. Private safe isn't touched either.
     */
    fun reset(context: Context) = Safe.resetIdCards {
        if (gate(context) != IdGate.Lost) return@resetIdCards false
        lock()
        runCatching { keyStore().deleteEntry(WrappedKey.decode(keyFile.readFully()).alias) }
        true
    }

    // ---------- Sharing ----------

    private fun shared(context: Context) = File(context.cacheDir, "zip/id") // Album's FileProvider reaches only cache/zip

    /**
     * Decrypts [items] into Album's cache for the share sheet that this returns, which reads them through Album's
     * FileProvider with a one-off grant. They're plain copies, so earlier ones are deleted first, and any left from an
     * earlier run go after an hour ([init]). [key] is a copy this wipes. Throws when it can't.
     */
    suspend fun share(context: Context, items: List<SafeItem>, key: ByteArray): Intent = withContext(Dispatchers.IO) {
        try {
            val folder = shared(context).apply { deleteRecursively() }
            check(items.sumOf { it.size } + (50L shl 20) < context.cacheDir.usableSpace) { "no space" }
            val uris = items.mapTo(ArrayList<Uri>()) { item ->
                // A folder each, so two photos with the same name both go, under their own names
                val file = File(File(folder, "${item.id}").apply { mkdirs() }, item.name.replace('/', '_'))
                Safe.reader(item, key).use { r -> file.outputStream().use { r.inputStream().copyTo(it, 1 shl 20) } }
                FileProvider.getUriForFile(context, "${context.packageName}.zip", file)
            }
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            send.type = items.map { it.mime }.distinct().singleOrNull() ?: if (items.none { it.isVideo }) "image/*" else "*/*"
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // So the grant reaches the app picked in the chooser
            send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            Intent.createChooser(send, null)
        } finally {
            key.fill(0)
        }
    }
}
