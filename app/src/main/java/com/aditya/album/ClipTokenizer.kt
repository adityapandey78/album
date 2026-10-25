package com.aditya.album

import java.text.Normalizer

/** How many token ids the text encoder reads: start of text, the text, end of text, then zeros. */
const val TEXT_TOKENS = 32

/** Bundled with the app: the merges are small (0.5 MB, less zipped) and every CLIP-style model uses the same ones. */
const val MERGES_ASSET = "describe/bpe_merges.txt"

private const val START = 49406
private const val END = 49407

/**
 * CLIP's byte-pair tokenizer, which Perception Encoder's text encoder was trained with (Meta's tokenizer.py, itself
 * OpenAI CLIP's). The text is cleaned and lowercased, split into words, single digits and runs of punctuation, and
 * each piece's UTF-8 bytes are merged into tokens in [merges] order (one "a b" pair per line, lowest rank first).
 * ClipTokenizerTest checks the ids against Meta's tokenizer. Not thread-safe (it caches pieces).
 */
class ClipTokenizer(merges: List<String>) {
    private val ranks = HashMap<String, Int>(merges.size * 2)
    private val ids = HashMap<String, Int>(END * 2)
    private val cache = HashMap<String, List<Int>>()

    init {
        // Ids: the 256 byte symbols, the same ending a word ("</w>"), one token per merge, then start and end of text
        BYTE_ORDER.forEachIndexed { i, b ->
            ids[BYTE_CHAR[b].toString()] = i
            ids[BYTE_CHAR[b] + "</w>"] = 256 + i
        }
        merges.forEachIndexed { rank, pair ->
            ranks[pair] = rank
            ids[pair.replace(" ", "")] = 512 + rank
        }
        ids["<start_of_text>"] = START
        ids["<end_of_text>"] = END
    }

    /** [text] as [TEXT_TOKENS] ids. Longer text is cut, keeping end of text last, as the model was trained. */
    fun encode(text: String): IntArray {
        val tokens = mutableListOf(START)
        for (piece in PIECES.findAll(clean(text))) {
            val special = ids[piece.value]?.takeIf { it >= START } // typed "<end_of_text>" is that token, as in Meta's
            tokens += if (special != null) listOf(special) else bpe(piece.value.toByteArray().map { BYTE_CHAR[it.toInt() and 0xff] }.joinToString(""))
        }
        tokens += END
        val out = IntArray(TEXT_TOKENS)
        tokens.take(TEXT_TOKENS).forEachIndexed { i, id -> out[i] = id }
        if (tokens.size > TEXT_TOKENS) out[TEXT_TOKENS - 1] = END
        return out
    }

    /** One piece's ids: its symbols merged, the lowest-ranked adjacent pair first, until no pair has a rank. */
    private fun bpe(piece: String): List<Int> = cache.getOrPut(piece) {
        var word = piece.map { it.toString() }.toMutableList()
        word[word.lastIndex] = word.last() + "</w>"
        while (word.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val rank = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best < 0) break
            val first = word[best]
            val second = word[best + 1]
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) { // every occurrence of the pair, left to right
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += word[i++]
                }
            }
            word = merged
        }
        word.map { ids.getValue(it) }
    }
}

// GPT-2's byte-to-character table: printable bytes stand for themselves, the other 68 take the characters from U+0100 on.
private val PRINTABLE = (33..126) + (161..172) + (174..255)
private val BYTE_ORDER = PRINTABLE + (0..255).filter { it !in PRINTABLE }
private val BYTE_CHAR = CharArray(256).also { table ->
    var next = 256
    for (b in BYTE_ORDER) table[b] = if (b in PRINTABLE) b.toChar() else (next++).toChar()
}

// Meta's pattern: the special tokens, English contractions, runs of letters, single digits, runs of anything else
private val PIECES = Regex("""<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|\p{L}+|\p{N}|[^\s\p{L}\p{N}]+""", RegexOption.IGNORE_CASE)

/**
 * Meta's "lower" cleaning: ftfy.fix_text, HTML entities decoded twice, runs of white space made one space, lowercase.
 * Of ftfy, the steps that typed text can need: NFC, curly quotes made straight, Latin ligatures and full-width
 * characters made plain, invisible control characters dropped. Its repair of mis-decoded text (mojibake) is left out:
 * a phone keyboard doesn't produce any, and it's most of ftfy.
 */
internal fun clean(text: String): String {
    val s = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        when (cp) {
            0x2bc, in 0x2018..0x201b -> s.append('\'')
            in 0x201c..0x201f -> s.append('"')
            in 0xfb00..0xfb06 -> s.append(LIGATURES[cp - 0xfb00])
            in 0xff01..0xff5e -> s.append((cp - 0xfee0).toChar()) // full-width ASCII
            0x3000 -> s.append(' ')
            0x85, 0x2028, 0x2029 -> s.append('\n')
            in 0x00..0x08, 0x0b, in 0x0e..0x1f, 0x7f, in 0x206a..0x206f, 0xfeff, in 0xfff9..0xfffc,
            in 0x1d173..0x1d17a, in 0xe0000..0xe007f -> Unit // ftfy's control characters
            else -> s.appendCodePoint(cp)
        }
    }
    val fixed = unescape(unescape(Normalizer.normalize(s, Normalizer.Form.NFC)))
    val out = StringBuilder(fixed.length)
    for (c in fixed) {
        if (c.isWhitespace()) {
            if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
        } else out.append(c)
    }
    return out.trim().toString().lowercase()
}

private val LIGATURES = listOf("ff", "fi", "fl", "ffi", "ffl", "ſt", "st")

// Python's html.unescape for what people type: numeric references and the common named ones. Others stay as typed.
private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);""")
private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

private fun unescape(s: String) = ENTITY.replace(s) { m ->
    val name = m.groupValues[1]
    val code = when {
        name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)
        name.startsWith("#") -> name.drop(1).toIntOrNull()
        else -> null
    }
    when {
        code == null -> NAMED.getValue(name)
        code in 1..0x10ffff && code !in 0xd800..0xdfff -> String(Character.toChars(code))
        else -> "�"
    }
}
