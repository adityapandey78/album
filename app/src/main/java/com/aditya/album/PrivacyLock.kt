package com.aditya.album

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.view.Window
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A privacy PIN is this many digits. */
val PIN_LENGTHS = 4..8

/**
 * The privacy PIN's file: the safe key sealed under the PIN, stretched with PBKDF2 and then run through an HMAC whose key
 * never leaves this phone's Keystore ([device]). So the file is useless off the phone, and every guess on it has to go
 * through this phone's secure hardware. Layout: "ALPN", version 1, the PIN's length, PBKDF2's iterations, a 16-byte
 * salt, then the IV's length, the IV and the sealed key; everything before the IV is authenticated with it.
 */
object PinSeal {
    const val ITERATIONS = 100_000 // ponytail: about 0.1 s on a phone; stored per file, so it can go up later
    private val MAGIC = "ALPN".toByteArray()
    private const val VERSION: Byte = 1
    private const val HEADER = 4 + 1 + 1 + 4 + 16

    /** Seals [key] under [pin]. [device] is the Keystore's HMAC (a software one in tests). */
    fun seal(key: ByteArray, pin: String, device: (ByteArray) -> ByteArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        require(isPin(pin)) { "not a PIN" }
        val salt = ByteArray(16).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).put(pin.length.toByte()).putInt(iterations).put(salt).array()
        val derived = pinKey(pin, salt, iterations, device)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"))
        } finally {
            derived.fill(0)
        }
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(key)
        return header + byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed
    }

    /** The key sealed in [record], or null when [pin] isn't the one it was sealed with. Damage throws. */
    fun open(record: ByteArray, pin: String, device: (ByteArray) -> ByteArray): ByteArray? {
        val b = ByteBuffer.wrap(record)
        val magic = ByteArray(4).also { b.get(it) }
        if (!magic.contentEquals(MAGIC) || b.get() != VERSION) throw GeneralSecurityException("not a PIN file")
        if (b.get().toInt() != pin.length) return null // the pad only sends this many digits; anything else is wrong
        val iterations = b.getInt()
        if (iterations !in 1..10_000_000) throw GeneralSecurityException("bad PIN file")
        val salt = ByteArray(16).also { b.get(it) }
        val iv = ByteArray(b.get().toInt()).also { b.get(it) }
        val sealed = ByteArray(b.remaining()).also { b.get(it) }
        val derived = pinKey(pin, salt, iterations, device)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
        } finally {
            derived.fill(0)
        }
        cipher.updateAAD(record, 0, HEADER)
        return try {
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    /** How many digits the PIN in [record] has, so the pad can check it as soon as the last one is in. */
    fun length(record: ByteArray): Int = record[5].toInt()

    private fun pinKey(pin: String, salt: ByteArray, iterations: Int, device: (ByteArray) -> ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val stretched = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return device(stretched).also { stretched.fill(0) }
    }
}

fun isPin(text: String) = text.length in PIN_LENGTHS && text.all { it in '0'..'9' }

/** Wrong PINs in a row, and when the last one was on the boot clock, the wall clock and which boot (see [waitLeft]). */
data class Strikes(val count: Int, val elapsed: Long = 0, val wall: Long = 0, val boot: Int = 0)

/** Wrong PINs allowed before the first wait. */
const val FREE_TRIES = 5

/** How long to wait after [strikes] wrong PINs in a row: none for the first few, then longer each time, up to an hour. */
fun waitAfter(strikes: Int): Long = when {
    strikes < FREE_TRIES -> 0
    strikes == FREE_TRIES -> 30_000
    strikes == FREE_TRIES + 1 -> 60_000
    strikes == FREE_TRIES + 2 -> 5 * 60_000
    strikes == FREE_TRIES + 3 -> 15 * 60_000
    strikes == FREE_TRIES + 4 -> 30 * 60_000
    else -> 60 * 60_000
}

/**
 * Time still to wait (ms) after [s]. Measured on the boot clock while the phone hasn't restarted, which changing the time
 * can't skip, else on the wall clock, so restarting can't skip it either. A clock set back counts as no time passed.
 */
fun waitLeft(s: Strikes, elapsed: Long, wall: Long, boot: Int): Long {
    val wait = waitAfter(s.count)
    if (wait == 0L) return 0
    val since = if (boot == s.boot && elapsed >= s.elapsed) elapsed - s.elapsed else wall - s.wall
    return (wait - since.coerceAtLeast(0)).coerceIn(0, wait)
}

/** What a wrong PIN shows: a plain "Wrong PIN" until the last free tries, then how many are left before a wait. */
fun wrongPinNote(strikes: Int): String {
    val left = FREE_TRIES - strikes
    return when {
        left > 2 || left <= 0 -> "Wrong PIN"
        left == 1 -> "Wrong PIN. 1 more try before a wait."
        else -> "Wrong PIN. $left more tries before a wait."
    }
}

/** "0:30", "14:05": a wait as minutes and seconds, rounded up so it never shows 0:00 while still waiting. */
fun waitText(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** The result of checking a PIN. */
sealed interface PinCheck {
    /** Right: privacy can open with [key], the safe key. */
    class Right(val key: ByteArray) : PinCheck

    /** Wrong, the [strikes]th in a row. */
    class Wrong(val strikes: Int) : PinCheck

    /** Too many wrong PINs: try again in [ms]. */
    class Wait(val ms: Long) : PinCheck

    /** The PIN can't be checked any more (its Keystore key is gone): only Forgot PIN gets back in. */
    data object Broken : PinCheck
}

/**
 * Album's privacy lock: its own PIN, apart from the phone's screen lock, for Private safe, Hidden items and hiding or
 * unhiding anything. Unlocking opens the safe key (see [Safe]); while it's open, privacy is unlocked.
 *
 * One unlock lasts while a private page is on screen, and [WINDOW_MS] after the last private action or page, so hiding a
 * few people, then checking Hidden items, asks once. It ends at once when Album leaves the screen (home, another app,
 * screen off), on Lock now, and when you leave Private safe, which locks as it always did.
 */
object PrivacyLock {
    /** How long an unlock lasts after the last private action: long enough to tidy several things, short enough that a phone left on a table doesn't stay open. */
    const val WINDOW_MS = 2 * 60_000L
    private const val PIN_ALIAS = "album_pin"
    private const val FINGERPRINT = "fingerprint"
    private const val FAILS = "fails"
    private const val FAIL_ELAPSED = "fail_elapsed"
    private const val FAIL_WALL = "fail_wall"
    private const val FAIL_BOOT = "fail_boot"
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("privacy", Context.MODE_PRIVATE) }
    private val pinFile by lazy { AtomicFile(File(Safe.dir, "pin")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var holders = 0
    private var expiry: Job? = null

    private val setUpNow = MutableStateFlow(false)

    /** Whether there's a privacy PIN. Before that, the first private thing asks for one. */
    val setUp = setUpNow.asStateFlow()

    private val fingerprintOn = MutableStateFlow(true)

    /** Settings > Unlock with fingerprint: whether the PIN pad offers the fingerprint. Forgot PIN uses it either way. */
    val fingerprint = fingerprintOn.asStateFlow()

    private val locks = MutableStateFlow(0)

    /** Goes up each time privacy locks, so a flow that proved it's you (Forgot PIN, Change PIN) starts again. */
    val lockCount = locks.asStateFlow()

    /** Set after a PIN unlock when Android erased the fingerprint-or-screen-lock wrapping: [PrivacyHost] offers to re-arm it. */
    val rearmOffer = MutableStateFlow(false)

    /** A result for the main snackbar, with Undo when there's one. Shown by [PrivacyHost]. */
    class Notice(val text: String, val undo: (() -> Unit)? = null)

    val notices = Channel<Notice>(Channel.UNLIMITED)

    /** Call before anything else, on the main thread; safe to call again. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Safe.init(app)
        setUpNow.value = pinFile.baseFile.exists() // a quick look, so no flow ever offers setup over an existing PIN
        scope.launch { fingerprintOn.value = withContext(Dispatchers.IO) { prefs.getBoolean(FINGERPRINT, true) } }
        // Leaving Album locks everything, whichever of its screens was showing; turning the phone only rebuilds the screen
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
    }

    /** Whether privacy is unlocked now. Composables watch [Safe.key] instead. */
    val isOpen get() = Safe.key.value != null

    // ---------- The session ----------

    /** Opens privacy with [key], the safe key (this takes it). An unlock while already open keeps the open one. */
    internal fun open(key: ByteArray) {
        if (Safe.key.value == null) Safe.open(key) else key.fill(0)
        touch()
    }

    /**
     * A private page is on screen in [window]: privacy stays unlocked while it is, and screenshots and the recents preview
     * are blocked. Pair each with [release]. Counted, so two private pages crossing in a transition never unblock each other.
     */
    fun hold(window: Window?) {
        if (holders++ == 0) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        expiry?.cancel()
    }

    fun release(window: Window?) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        touch()
    }

    /** A private action: privacy stays unlocked for [WINDOW_MS] more, or for as long as a private page is on screen. */
    fun touch() {
        expiry?.cancel()
        expiry = if (holders == 0 && isOpen) scope.launch {
            delay(WINDOW_MS)
            lock()
        } else null
    }

    /** Locks privacy: the safe key and its decrypted thumbnails leave memory, and private pages show their lock. */
    fun lock() {
        expiry?.cancel()
        expiry = null
        Safe.lock()
        locks.value++
    }

    // ---------- The PIN ----------

    /** The PIN's length, so the pad knows when it's complete; null without a readable PIN file. */
    fun pinLength(): Int? = runCatching { PinSeal.length(pinFile.readFully()) }.getOrNull()

    /** Checks [pin], counting wrong ones (they survive a restart) and refusing while a wait is on. */
    suspend fun check(pin: String): PinCheck = withContext(Dispatchers.IO) {
        synchronized(this@PrivacyLock) {
            waitNow().takeIf { it > 0 }?.let { return@withContext PinCheck.Wait(it) }
            val record = runCatching { pinFile.readFully() }.getOrNull()
            val device = device(create = false)
            if (record == null || device == null) {
                CrashLog.note("privacy pin unreadable: ${record == null} ${device == null}")
                return@withContext PinCheck.Broken
            }
            val key = try {
                PinSeal.open(record, pin, device)
            } catch (e: Exception) {
                CrashLog.note("privacy pin check failed: ${e.javaClass.simpleName}")
                return@withContext PinCheck.Broken
            }
            if (key != null) {
                clearStrikes()
                return@withContext PinCheck.Right(key)
            }
            val count = prefs.getInt(FAILS, 0) + 1
            prefs.edit().putInt(FAILS, count).putLong(FAIL_ELAPSED, SystemClock.elapsedRealtime()).putLong(FAIL_WALL, System.currentTimeMillis())
                .putInt(FAIL_BOOT, boot()).commit()
            CrashLog.note("privacy wrong pin $count")
            PinCheck.Wrong(count)
        }
    }

    /** How long the pad must still wait after wrong PINs, in ms; 0 when it needn't. */
    fun waitNow(): Long {
        val s = Strikes(prefs.getInt(FAILS, 0), prefs.getLong(FAIL_ELAPSED, 0), prefs.getLong(FAIL_WALL, 0), prefs.getInt(FAIL_BOOT, 0))
        return waitLeft(s, SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot())
    }

    private fun boot() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun clearStrikes() {
        if (prefs.getInt(FAILS, 0) != 0) prefs.edit().remove(FAILS).remove(FAIL_ELAPSED).remove(FAIL_WALL).remove(FAIL_BOOT).commit()
    }

    /**
     * Seals [key] (the safe key, left as it is) under [pin] and saves it in place of any older PIN, in one go. The new file
     * is read back with the PIN first, so a PIN change can never leave an unopenable file behind.
     */
    suspend fun savePin(key: ByteArray, pin: String) = withContext(Dispatchers.IO) {
        synchronized(this@PrivacyLock) {
            val device = checkNotNull(device(create = true))
            val record = PinSeal.seal(key, pin, device)
            check(PinSeal.open(record, pin, device)?.contentEquals(key) == true) { "PIN file doesn't open" }
            val out = pinFile.startWrite()
            try {
                out.write(record)
                pinFile.finishWrite(out)
            } catch (e: Exception) {
                pinFile.failWrite(out)
                throw e
            }
            clearStrikes()
            setUpNow.value = true
            CrashLog.note("privacy pin saved")
        }
    }

    /** For [Safe.reset], which deleted the PIN's file with the safe it opened: the next private thing sets a new PIN. */
    internal fun forgetPin() {
        clearStrikes()
        setUpNow.value = false
    }

    /**
     * The HMAC under this phone's own Keystore key, which never leaves its secure hardware. Made once, with the first PIN,
     * and never replaced while it exists: a new one couldn't open the PIN's file. Null without one and without [create].
     */
    @Synchronized
    private fun device(create: Boolean): ((ByteArray) -> ByteArray)? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(PIN_ALIAS, null) as? SecretKey ?: if (create) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
                .apply { init(KeyGenParameterSpec.Builder(PIN_ALIAS, KeyProperties.PURPOSE_SIGN).build()) }
                .generateKey()
        } else {
            return null
        }
        return { input -> Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input) }
    }

    // ---------- Fingerprint, setup, Forgot PIN ----------

    /** Whether the pad offers the fingerprint: it's on in Settings, the phone has one, and its Keystore key is still there. */
    fun fingerprintReady(context: Context) = fingerprintOn.value && Safe.hasFingerprint(context) && !Safe.wrapLost.value && Safe.isSetUp

    fun setFingerprint(on: Boolean) {
        fingerprintOn.value = on
        prefs.edit().putBoolean(FINGERPRINT, on).apply()
        CrashLog.note("privacy fingerprint $on")
    }

    /** Whether Forgot PIN can work: the phone has a screen lock and the safe key's other wrapping is still there. */
    fun canRecover(context: Context) = Safe.hasScreenLock(context) && Safe.isSetUp && !Safe.wrapLost.value

    /**
     * After choosing [pin] for the first time: Android's prompt (fingerprint or screen lock) makes the safe key, or opens a
     * v1.0 safe's, and the PIN's file is saved. Then [onKey] gets the key. [onFail] as in [Safe.prompt].
     */
    fun setUp(context: Context, pin: String, cancel: CancellationSignal, onKey: (ByteArray) -> Unit, onFail: (String?) -> Unit) {
        Safe.prompt(context, "Confirm it's you", "So your fingerprint or screen lock can reset your privacy PIN if you forget it", Safe.Proof.SetUp, cancel, { key ->
            scope.launch {
                runCatching { savePin(key, pin) }
                    .onSuccess {
                        CrashLog.note("privacy set up")
                        onKey(key)
                    }
                    .onFailure {
                        CrashLog.note("privacy set up failed: ${it.javaClass.simpleName}")
                        key.fill(0)
                        onFail("Couldn't save your PIN. Try again.")
                    }
            }
        }, onFail)
    }

    private var rearmOffered = false

    /**
     * After a PIN unlock: if Android erased the other wrapping but the screen lock is back, [PrivacyHost] offers to re-arm
     * it, once while Album runs (Settings keeps a row for it after Not now).
     */
    internal fun afterPin(context: Context) {
        if (rearmOffered) return
        val app = context.applicationContext
        scope.launch {
            Safe.checkKey()
            if ((Safe.wrapLost.value || !Safe.isSetUp) && Safe.hasScreenLock(app)) {
                rearmOffered = true
                rearmOffer.value = true
            }
        }
    }

    /** Wraps the open safe key again under a new Keystore key (see [Safe.rearm]). Privacy must be open. */
    fun rearm(context: Context, cancel: CancellationSignal, onDone: () -> Unit, onFail: (String?) -> Unit) {
        val key = Safe.key.value?.copyOf() ?: return onFail(null)
        Safe.rearm(context, key, cancel, {
            CrashLog.note("privacy re-armed")
            notices.trySend(Notice("Forgot PIN and fingerprint unlock are back on"))
            onDone()
        }, onFail)
    }
}
