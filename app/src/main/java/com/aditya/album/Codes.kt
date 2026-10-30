package com.aditya.album

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume

// QR codes and barcodes in a photo: ML Kit's bundled scanner reads them on the phone (offline; nothing leaves it) once a
// photo has settled on screen for a moment, and the viewer shows a badge for what it found. What a code holds is worked
// out here from its text, and so are the links, phone numbers and e-mail addresses in a photo's words (TextLens). Opening
// anything always asks first, showing exactly what will open; copying is always offered.

/** What a QR code, a barcode or a bit of a photo's text holds. [raw] is what Copy copies. */
sealed interface CodeContent {
    val raw: String

    /** A web address: always http or https, nothing else opens a browser. */
    data class Link(val url: String) : CodeContent {
        override val raw get() = url
    }

    data class Phone(val number: String) : CodeContent {
        override val raw get() = number
    }

    data class Email(val address: String, val subject: String? = null, val body: String? = null) : CodeContent {
        override val raw get() = address
    }

    data class Sms(val number: String, val message: String? = null) : CodeContent {
        override val raw get() = number
    }

    /** [password] is null for an open network. [security] as the code says it: "WPA", "SAE", "WEP", or null. */
    data class Wifi(val ssid: String, val password: String?, val security: String?, val hidden: Boolean = false) : CodeContent {
        override val raw get() = password ?: ssid
    }

    /** A UPI payment request, opened as it is ([uri]). [payee] is the UPI ID the money would go to. */
    data class Upi(val uri: String, val payee: String, val name: String?, val amount: String?, val note: String?) : CodeContent {
        override val raw get() = payee
    }

    data class Geo(val uri: String, val lat: Double, val lng: Double) : CodeContent {
        override val raw get() = coordinates(lat, lng)
    }

    data class Contact(val name: String?, val phones: List<String>, val emails: List<String>) : CodeContent {
        override val raw get() = (listOfNotNull(name) + phones + emails).joinToString("\n")
    }

    /** Anything else. [product] for a shop barcode (EAN, UPC), which is only ever a number. */
    data class Plain(val text: String, val product: Boolean = false) : CodeContent {
        override val raw get() = text
    }
}

// ---------- What a code holds ----------

/** What [text], a code's contents, holds: the formats phones' cameras and payment apps read, else plain text. */
fun contentOf(text: String, product: Boolean = false): CodeContent {
    val t = text.trim()
    val lower = t.lowercase(Locale.ROOT)
    val found = when {
        product -> null
        lower.startsWith("http://") || lower.startsWith("https://") -> linkOf(t)
        lower.startsWith("www.") -> linkOf("https://$t")
        lower.startsWith("upi://") -> upiOf(t)
        lower.startsWith("wifi:") -> wifiOf(t.drop(5))
        lower.startsWith("tel:") -> phoneOf(decode(t.drop(4), plusIsSpace = false))
        lower.startsWith("mailto:") -> mailtoOf(t.drop(7))
        lower.startsWith("matmsg:") -> fields(t.drop(7)).let { f -> f["TO"]?.let { emailOf(it, f["SUB"], f["BODY"]) } }
        lower.startsWith("smsto:") || lower.startsWith("sms:") -> smsOf(t.substringAfter(':'))
        lower.startsWith("geo:") -> geoOf(t)
        lower.startsWith("begin:vcard") -> vcardOf(t)
        lower.startsWith("mecard:") -> mecardOf(t.drop(7))
        EMAIL.matches(t) -> CodeContent.Email(t)
        else -> null
    }
    return found ?: CodeContent.Plain(t, product)
}

private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

/** An http(s) address with a host and no spaces, as browsers take it; null for anything else. */
private fun linkOf(url: String) = CodeContent.Link(url).takeIf { Regex("""(?i)https?://[^\s/?#]+[^\s]*""").matches(url) }

private fun phoneOf(number: String) = number.trim().takeIf { n -> n.count { it.isDigit() } >= 3 && n.all { it.isDigit() || it in "+-() ." } }?.let { CodeContent.Phone(it) }

private fun emailOf(address: String, subject: String? = null, body: String? = null) =
    address.trim().takeIf { EMAIL.matches(it) }?.let { CodeContent.Email(it, subject?.ifBlank { null }, body?.ifBlank { null }) }

/** "name@example.com?subject=Hi&body=..." */
private fun mailtoOf(rest: String): CodeContent? {
    val q = query(rest.substringAfter('?', ""))
    return emailOf(decode(rest.substringBefore('?'), plusIsSpace = false), q["subject"], q["body"])
}

/** "smsto:+919876543210:Hello" or "sms:+919876543210?body=Hello" */
private fun smsOf(rest: String): CodeContent? {
    val number = rest.substringBefore('?').substringBefore(':')
    val message = rest.substringAfter('?', "").let { query(it)["body"] } ?: rest.substringAfter(':', "").ifBlank { null }
    return phoneOf(decode(number, plusIsSpace = false))?.let { CodeContent.Sms(it.number, message) }
}

/** "upi://pay?pa=shop@okbank&pn=Shop&am=250.00&tn=Order": only one with a UPI ID (name@handle) to pay. */
private fun upiOf(uri: String): CodeContent? {
    val q = query(uri.substringAfter('?', ""))
    val payee = q["pa"]?.trim()?.takeIf { Regex("""[\w.\-]+@[\w.\-]+""").matches(it) } ?: return null
    return CodeContent.Upi(uri, payee, q["pn"]?.ifBlank { null }, q["am"]?.ifBlank { null }, q["tn"]?.ifBlank { null })
}

/** "WIFI:T:WPA;S:Home;P:secret;H:false;;" (the WIFI: already gone), with \ escaping ; , : " and \ in values. */
private fun wifiOf(rest: String): CodeContent? {
    val f = fields(rest)
    val ssid = f["S"]?.takeIf { it.isNotEmpty() } ?: return null
    val security = f["T"]?.takeIf { it.isNotBlank() && !it.equals("nopass", ignoreCase = true) }
    return CodeContent.Wifi(ssid, f["P"]?.takeIf { security != null && it.isNotEmpty() }, security, f["H"].equals("true", ignoreCase = true))
}

/** "geo:12.9716,77.5946" (an altitude or a ?q= after it is left out). */
private fun geoOf(uri: String): CodeContent? {
    val parts = uri.drop(4).substringBefore('?').substringBefore(';').split(',')
    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
    val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
    return if (lat in -90.0..90.0 && lng in -180.0..180.0) CodeContent.Geo(uri, lat, lng) else null
}

/** A vCard's name, phone numbers and e-mail addresses: what Add to contacts fills in. */
private fun vcardOf(card: String): CodeContent? {
    // Long lines are folded: a line starting with a space continues the one before
    val lines = card.replace(Regex("""\r?\n[ \t]"""), "").lines()
    fun values(key: String) = lines.filter { it.substringBefore(':').substringBefore(';').equals(key, ignoreCase = true) }
        .map { it.substringAfter(':').trim() }.filter { it.isNotEmpty() }
    // N is "Family;Given;Middle;Prefix;Suffix"
    val name = values("FN").firstOrNull() ?: values("N").firstOrNull()?.split(';')?.let { n -> listOf(n.getOrNull(1), n[0]).filterNot { it.isNullOrBlank() }.joinToString(" ") }
    return contactOf(name, values("TEL"), values("EMAIL"))
}

/** "MECARD:N:Kumar,Ravi;TEL:+919876543210;EMAIL:ravi@example.com;;" (the MECARD: already gone). */
private fun mecardOf(rest: String): CodeContent? {
    val all = fieldList(rest)
    val name = all.firstOrNull { it.first == "N" }?.second?.split(',')?.map { it.trim() }?.reversed()?.filter { it.isNotEmpty() }?.joinToString(" ")
    return contactOf(name, all.filter { it.first == "TEL" }.map { it.second }, all.filter { it.first == "EMAIL" }.map { it.second })
}

private fun contactOf(name: String?, phones: List<String>, emails: List<String>) =
    if (name.isNullOrBlank() && phones.isEmpty() && emails.isEmpty()) null else CodeContent.Contact(name?.ifBlank { null }, phones, emails)

/** "K:value;K:value;;" with \ escapes, as Wi-Fi codes, MECARD and MATMSG write them; keys in capitals. */
private fun fieldList(body: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val part = StringBuilder()
    var key: String? = null
    var escaped = false
    fun end() {
        key?.let { out += it.uppercase(Locale.ROOT) to part.toString() }
        key = null
        part.clear()
    }
    for (ch in body) {
        when {
            escaped -> part.append(ch).also { escaped = false }
            ch == '\\' -> escaped = true
            ch == ';' -> end()
            ch == ':' && key == null -> key = part.toString().trim().also { part.clear() }
            else -> part.append(ch)
        }
    }
    end()
    return out
}

private fun fields(body: String): Map<String, String> = fieldList(body).toMap()

/** "a=1&b=two%20words" as a map, values decoded. */
private fun query(q: String): Map<String, String> = q.split('&').filter { '=' in it }.associate { decode(it.substringBefore('=')).lowercase(Locale.ROOT) to decode(it.substringAfter('=')) }

/** %-escapes decoded. A + is a space only in query values ([plusIsSpace]); in a number or an address it's itself. */
private fun decode(s: String, plusIsSpace: Boolean = true) =
    runCatching { URLDecoder.decode(if (plusIsSpace) s else s.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(s)

// ---------- Links in a photo's words ----------

// Domains written without https:// or www. count only with these endings, so "No.1" or "report.pdf" never become links
private const val TLDS = "com|in|org|net|edu|gov|io|co|app|dev|info|me|ai|xyz|shop|store|online|site|tech|biz|us|uk"
private val URL_IN_TEXT = Regex("""(?i:https?://|www\.)[^\s<>"]+|\b(?:[A-Za-z0-9-]+\.)+(?:$TLDS)\b(?:/[^\s<>"]*)?""")
// Spaces only, not new lines: two numbers on lines of their own stay two numbers. Never part of a longer run of digit
// groups ("1234 5678 9012") or an amount ("1,25,000"): those are left whole, and then aren't dialable.
private val PHONE_IN_TEXT = Regex("""(?<![\w+(,.])(?<!\d[ -])[+(]?\d[\d \-()]{7,16}\d(?!\w)(?![ -]\d)""")

/**
 * Web addresses, e-mail addresses and phone numbers in [text], read from a photo, in reading order and each once.
 * Phone numbers are the ones that dial as written: Indian mobiles (10 digits from 6), numbers with their STD code (0...)
 * or toll-free 1800, and anything with a + country code. Other digit runs (dates, amounts, Aadhaar) are left alone.
 */
fun linksIn(text: String): List<CodeContent> {
    val found = sortedMapOf<Int, CodeContent>()
    val taken = ArrayList<IntRange>()
    fun add(range: IntRange, content: CodeContent?) {
        if (content == null || taken.any { it.first <= range.last && range.first <= it.last }) return
        taken += range
        found[range.first] = content
    }
    EMAIL.findAll(text).forEach { add(it.range, CodeContent.Email(it.value)) }
    URL_IN_TEXT.findAll(text).forEach { m ->
        val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
        add(m.range.first until m.range.first + url.length, linkOf(if (url.startsWith("http", ignoreCase = true)) url else "https://$url"))
    }
    PHONE_IN_TEXT.findAll(text).forEach { m -> if (dialable(m.value)) add(m.range, CodeContent.Phone(m.value.trim())) }
    return found.values.distinct()
}

private fun dialable(number: String): Boolean {
    val digits = number.filter { it.isDigit() }
    return when {
        number.trimStart().startsWith('+') -> digits.length in 8..15
        digits.length == 10 -> digits[0] in '6'..'9'
        digits.length == 11 -> digits.startsWith("0") || digits.startsWith("1800")
        else -> false
    }
}

/** How a link, number or address reads on a chip: no https://, and long ones cut short. */
fun chipLabel(content: CodeContent): String {
    val text = when (content) {
        is CodeContent.Link -> content.url.replace(Regex("^(?i)https?://"), "").trimEnd('/')
        else -> content.raw
    }
    return if (text.length > 32) text.take(31) + "…" else text
}

// ---------- Finding codes ----------

/** Longest side a photo is read at for codes: a QR code on a poster across the room still has a few pixels per square. */
private const val CODES_PX = 1600

/** How long a photo stays on screen before it's read: one swiped past isn't worth it. */
private const val SETTLE_MS = 400L

// Keyed by edit version too, so a photo changed in place is read again.
// ponytail: one small entry per photo viewed, never trimmed; bound it if a session ever views tens of thousands.
private val scanned = mutableStateMapOf<Pair<Uri, Int>, List<CodeContent>>()

/**
 * The QR codes and barcodes in photo [m], read once [active] (it's the photo on screen and still) has held a moment:
 * null until then, empty when there are none.
 */
@Composable
fun rememberCodes(m: Media, active: Boolean): List<CodeContent>? {
    val context = LocalContext.current
    // One scanner while the viewer is open: loading its model for every photo would cost more than reading the photo
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()) }
    DisposableEffect(scanner) { onDispose { scanner.close() } }
    val key = m.uri to editVersion(m.uri)
    LaunchedEffect(key, active) {
        if (!active || key in scanned) return@LaunchedEffect
        delay(SETTLE_MS)
        scanned[key] = scanCodes(context, m.uri, scanner)
    }
    return scanned[key]
}

private val PRODUCT_FORMATS = setOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)

/** Every code ML Kit can read in [uri], each once; none when it has none or can't be read. */
private suspend fun scanCodes(context: Context, uri: Uri, scanner: BarcodeScanner): List<CodeContent> {
    val started = SystemClock.elapsedRealtime()
    val bitmap = withContext(Dispatchers.IO) { decodeUpright(context, uri, CODES_PX) } ?: return emptyList()
    val codes = suspendCancellableCoroutine<List<Barcode>?> { c ->
        scanner.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { c.resume(if (it.isSuccessful) it.result else null) }
    }.orEmpty()
    CrashLog.note("codes: ${codes.size} in ${SystemClock.elapsedRealtime() - started} ms")
    return codes.mapNotNull { code -> code.rawValue?.takeIf { it.isNotBlank() }?.let { contentOf(it, product = code.format in PRODUCT_FORMATS) } }.distinct()
}

// ---------- Words for each kind ----------

/** What the code is, for a list row: "Link", "Wi-Fi network". */
private fun CodeContent.kind() = when (this) {
    is CodeContent.Link -> "Link"
    is CodeContent.Phone -> "Phone number"
    is CodeContent.Email -> "E-mail address"
    is CodeContent.Sms -> "Text message"
    is CodeContent.Wifi -> "Wi-Fi network"
    is CodeContent.Upi -> "UPI payment"
    is CodeContent.Geo -> "Place"
    is CodeContent.Contact -> "Contact"
    is CodeContent.Plain -> if (product) "Barcode" else "Text"
}

/** The viewer's badge for the codes in a photo: what the one code is, or how many there are. */
fun codesBadge(found: List<CodeContent>): String = found.singleOrNull()?.let {
    when (it) {
        is CodeContent.Link -> "Link"
        is CodeContent.Phone -> "Phone"
        is CodeContent.Email -> "E-mail"
        is CodeContent.Sms -> "Message"
        is CodeContent.Wifi -> "Wi-Fi"
        is CodeContent.Upi -> "UPI"
        is CodeContent.Geo -> "Place"
        is CodeContent.Contact -> "Contact"
        is CodeContent.Plain -> if (it.product) "Barcode" else "QR code"
    }
} ?: "${found.size} codes"

// ponytail: no wifi, call or payment glyphs in the bundled icon subset yet; add them to tools/icons.py if these read poorly
fun CodeContent.icon(): Sym = when (this) {
    is CodeContent.Link -> Sym.Link
    is CodeContent.Phone, is CodeContent.Sms -> Sym.PhoneAndroid
    is CodeContent.Email -> Sym.Mail
    is CodeContent.Geo -> Sym.LocationOn
    is CodeContent.Contact -> Sym.Person
    is CodeContent.Wifi, is CodeContent.Upi, is CodeContent.Plain -> Sym.QrCode2
}

/** The question the sheet asks before anything opens, or a plain title when there's nothing to open. */
private fun CodeContent.question() = when (this) {
    is CodeContent.Link -> "Open in browser?"
    is CodeContent.Phone -> "Open in phone app?"
    is CodeContent.Email -> "Open in e-mail app?"
    is CodeContent.Sms -> "Open in messages?"
    is CodeContent.Wifi -> "Join this Wi-Fi network?"
    is CodeContent.Upi -> "Pay with a UPI app?"
    is CodeContent.Geo -> "Open in maps?"
    is CodeContent.Contact -> "Add to contacts?"
    is CodeContent.Plain -> if (product) "Barcode" else "Text in this code"
}

private fun CodeContent.openLabel() = when (this) {
    is CodeContent.Wifi -> "Join"
    is CodeContent.Upi -> "Open UPI app"
    is CodeContent.Contact -> "Open contacts"
    else -> "Open"
}

private fun CodeContent.copyLabel() = when (this) {
    is CodeContent.Link -> "Copy link"
    is CodeContent.Phone, is CodeContent.Sms -> "Copy number"
    is CodeContent.Email -> "Copy address"
    is CodeContent.Wifi -> if (password != null) "Copy password" else "Copy name"
    is CodeContent.Upi -> "Copy UPI ID"
    else -> "Copy"
}

/**
 * The app that opens [content], with what it holds filled in; null when it can only be copied. Album hands over the
 * details and nothing else: the phone app dials only after its own call button, the UPI app asks for the PIN, Android
 * asks before it saves a network.
 */
private fun openIntent(content: CodeContent): Intent? = when (content) {
    is CodeContent.Link -> Intent(Intent.ACTION_VIEW, Uri.parse(content.url)).addCategory(Intent.CATEGORY_BROWSABLE)
    is CodeContent.Phone -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", content.number, null))
    is CodeContent.Email -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", content.address, null))
        .putExtra(Intent.EXTRA_SUBJECT, content.subject).putExtra(Intent.EXTRA_TEXT, content.body)
    is CodeContent.Sms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", content.number, null)).putExtra("sms_body", content.message)
    is CodeContent.Upi -> Intent(Intent.ACTION_VIEW, Uri.parse(content.uri))
    is CodeContent.Geo -> Intent(Intent.ACTION_VIEW, Uri.parse(content.uri))
    is CodeContent.Contact -> Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.NAME, content.name)
        .putExtra(ContactsContract.Intents.Insert.PHONE, content.phones.firstOrNull())
        .putExtra(ContactsContract.Intents.Insert.EMAIL, content.emails.firstOrNull())
    is CodeContent.Wifi -> wifiIntent(content)
    is CodeContent.Plain -> null
}

/**
 * Android's own "save this network" screen for [wifi], which asks before saving. WEP, and anything Android won't take as a
 * suggestion, opens Wi-Fi settings instead, where the copied password can go.
 */
private fun wifiIntent(wifi: CodeContent.Wifi): Intent {
    val suggestion = if (wifi.security.equals("WEP", ignoreCase = true)) null else runCatching {
        WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).apply {
            wifi.password?.let { if (wifi.security.equals("SAE", ignoreCase = true)) setWpa3Passphrase(it) else setWpa2Passphrase(it) }
            if (wifi.hidden) setIsHiddenSsid(true)
        }.build()
    }.onFailure { CrashLog.note("wifi suggestion refused: $it") }.getOrNull()
    return if (suggestion == null) Intent(Settings.ACTION_WIFI_SETTINGS)
    else Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
}

/** Copies [text]; a password is marked sensitive, so Android doesn't show it in its copy preview. */
private fun copy(context: Context, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("From photo", text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

// ---------- Sheets ----------

/**
 * What was found in a photo: one code (or a tapped link from its text) opens its own sheet at once; several are listed
 * first. [onCopied] confirms a copy (the viewer's snackbar).
 */
@Composable
fun CodesSheet(found: List<CodeContent>, onCopied: () -> Unit, onDismiss: () -> Unit) {
    var chosen by remember(found) { mutableStateOf(found.singleOrNull()) }
    chosen?.let { return CodeSheet(it, onCopied, onDismiss) }
    Sheet(onDismiss, title = "${found.size} codes in this photo") {
        found.forEach { content ->
            MenuRow(content.icon(), content.kind(), {
                CrashLog.note("code pick")
                chosen = content
            }, sub = chipLabel(content))
        }
    }
}

/**
 * One code: the question ("Open in browser?"), everything that would be opened, in full, then Copy and the button that
 * opens it. Plain text and shop barcodes only copy.
 */
@Composable
private fun CodeSheet(content: CodeContent, onCopied: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    Sheet(onDismiss, title = content.question()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(bottom = Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s1),
        ) {
            @Composable
            fun Line(text: String, label: String? = null) = Text(if (label == null) text else "$label  $text", style = type.body, color = c.ink)
            when (content) {
                is CodeContent.Link -> Line(content.url)
                is CodeContent.Phone -> Line(content.number)
                is CodeContent.Email -> {
                    Line(content.address)
                    content.subject?.let { Line(it, "Subject") }
                }
                is CodeContent.Sms -> {
                    Line(content.number)
                    content.message?.let { Line(it) }
                }
                is CodeContent.Wifi -> {
                    Line(content.ssid, "Network")
                    content.password?.let { Line(it, "Password") }
                    Text("Android asks before it saves the network.", style = type.caption, color = c.inkMuted)
                }
                is CodeContent.Upi -> {
                    content.name?.let { Text(it, style = type.titleMd, color = c.ink) }
                    Line(content.payee, "UPI ID")
                    content.amount?.let { Line("₹$it", "Amount") }
                    content.note?.let { Line(it, "Note") }
                    // QR codes stuck over a shop's own are a common fraud: the name the UPI app shows is what to check
                    Text("Check the name in your UPI app before you pay.", style = type.caption, color = c.inkMuted)
                }
                is CodeContent.Geo -> Line(coordinates(content.lat, content.lng))
                is CodeContent.Contact -> (listOfNotNull(content.name) + content.phones + content.emails).forEach { Line(it) }
                is CodeContent.Plain -> Line(content.text)
            }
        }
        val open = openIntent(content)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            AlbumButton(content.copyLabel(), {
                CrashLog.note("code copy")
                copy(context, content.raw, sensitive = content is CodeContent.Wifi && content.password != null)
                onDismiss()
                onCopied()
            }, Modifier.weight(1f).heightIn(min = 56.dp), if (open == null) ButtonVariant.Primary else ButtonVariant.Tonal, Sym.ContentCopy)
            if (open != null) {
                AlbumButton(content.openLabel(), {
                    CrashLog.note("code open ${content.kind()}")
                    onDismiss()
                    context.startSafely(open)
                }, Modifier.weight(1.5f).heightIn(min = 56.dp), ButtonVariant.Primary, Sym.OpenInNew)
            }
        }
    }
}
