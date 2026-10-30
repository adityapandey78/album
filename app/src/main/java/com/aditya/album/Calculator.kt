package com.aditya.album

import android.icu.text.NumberFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

// The Calculator disguise: a calculator that really works and looks like the phone's own, so it holds up when someone
// tries it. Typing the privacy PIN and = opens Album; to anyone else, that's just a number.

/** A calculator's sum as typed: digits, '.', '%' and the operators, kept exactly as pressed (leading zeros too). */
object Calc {
    const val PLUS = '+'
    const val MINUS = '−' // the minus sign, not a hyphen, as calculators show it
    const val TIMES = '×'
    const val DIVIDE = '÷'
    private const val OPERATORS = "+−×÷"

    /** Keys in one sum: a calculator's display, not a spreadsheet. */
    const val MAX = 40

    /** The 12 digits calculators show; a result is rounded to these. */
    private val SHOWN = MathContext(12)

    fun isOperator(c: Char) = c in OPERATORS

    /** [input] after [key] (a digit, an operator, '.' or '%'); unchanged when the key makes no sense there, as on any. */
    fun press(input: String, key: Char): String {
        val last = input.lastOrNull()
        val term = input.takeLastWhile { !isOperator(it) }
        return when {
            input.length >= MAX && !isOperator(key) -> input
            key.isDigit() -> if (last == '%') input else input + key
            key == '.' -> when {
                '.' in term || last == '%' -> input
                term.isEmpty() -> input + "0."
                else -> input + key
            }
            key == '%' -> if (last != null && (last.isDigit() || last == '.')) input + key else input
            isOperator(key) -> when {
                last == null -> if (key == MINUS) input + key else input // a sum can start negative
                isOperator(last) -> if (input.length == 1) input else input.dropLast(1) + key // a second operator replaces the first
                else -> input + key
            }
            else -> input
        }
    }

    /**
     * The value of [input], × and ÷ before + and −, a '%' term as a hundredth; null when it's incomplete (empty, or ending
     * in an operator) or divides by zero.
     */
    fun evaluate(input: String): BigDecimal? {
        if (input.isEmpty() || isOperator(input.last())) return null
        val numbers = mutableListOf<BigDecimal>()
        val operators = mutableListOf<Char>()
        var i = 0
        var negative = false
        if (input[0] == MINUS) {
            negative = true
            i = 1
        }
        while (i < input.length) {
            val start = i
            // A minus right after E is the exponent's (a last answer like 1.5E−7), not a subtraction
            while (i < input.length && (!isOperator(input[i]) || input[i - 1] == 'E')) i++
            val term = input.substring(start, i)
            if (term.isEmpty()) return null
            var n = term.removeSuffix("%").replace(MINUS, '-').toBigDecimalOrNull() ?: return null
            if (term.endsWith('%')) n = n.movePointLeft(2)
            numbers += if (negative) n.negate() else n
            negative = false
            if (i < input.length) operators += input[i++]
        }
        // × and ÷ first, folding into the number on their left; then + and − from left to right
        val terms = mutableListOf(numbers[0])
        val signs = mutableListOf<Char>()
        for ((k, op) in operators.withIndex()) {
            val n = numbers[k + 1]
            when (op) {
                TIMES -> terms[terms.lastIndex] = terms.last().multiply(n)
                DIVIDE -> {
                    if (n.signum() == 0) return null
                    terms[terms.lastIndex] = terms.last().divide(n, MathContext.DECIMAL64)
                }
                else -> {
                    terms += n
                    signs += op
                }
            }
        }
        var total = terms[0]
        for ((k, op) in signs.withIndex()) total = if (op == PLUS) total.add(terms[k + 1]) else total.subtract(terms[k + 1])
        return total
    }

    /** [n] as shown, and as the next sum starts from it: 12 digits at most, very large or small ones as 1.5E15. */
    fun format(n: BigDecimal): String {
        val r = n.round(SHOWN).stripTrailingZeros()
        val size = r.abs()
        val text = if (r.signum() != 0 && (size >= BigDecimal("1E12") || size < BigDecimal("1E-6"))) {
            r.toString().replace("E+", "E")
        } else {
            r.toPlainString()
        }
        return text.replace('-', MINUS)
    }

    /**
     * [input] for the display: each whole number without its leading zeros and grouped by [group] (the phone's locale),
     * decimals and the rest as typed.
     */
    fun display(input: String, group: (String) -> String): String = buildString {
        var i = 0
        var fraction = false
        while (i < input.length) {
            val c = input[i]
            if (c.isDigit()) {
                val start = i
                while (i < input.length && input[i].isDigit()) i++
                val digits = input.substring(start, i)
                append(if (fraction) digits else group(digits.trimStart('0').ifEmpty { "0" }))
            } else {
                fraction = c == '.'
                append(c)
                i++
            }
        }
    }
}

/** The calculator's own colours, always dark as phone calculators are: a plain system look with no sign of Album's accent. */
private object CalcColors {
    val ground = Color(0xFF101113)
    val key = Color(0xFF23252A)
    val fn = Color(0xFF3A3D44)
    val pressed = Color(0xFF4A4E57) // the design's brightness(1.3) on a pressed key
    val ink = Color(0xFFF4F4F4)
    val muted = Color(0xFF9A9CA1)
    val op = Color(0xFFF2A65A)
    val eq = Color(0xFFF2A65A)
    val onEq = Color(0xFF1D1305)
}

/**
 * A working calculator that looks like the phone's own: Roboto, round keys, an orange "=". "=" on a bare number of the
 * PIN's length hands it to [onPin] as typed (0042 stays 0042), and the calculator shows the number as any calculator
 * would, right PIN or not. Holding "=" runs [onHold]: the PIN pad, with the fingerprint and Forgot PIN.
 */
@Composable
fun CalculatorScreen(pinLength: Int?, onPin: (String) -> Unit, onHold: () -> Unit) {
    val k = CalcColors
    var input by rememberSaveable { mutableStateOf("") }
    var answered by rememberSaveable { mutableStateOf(false) } // input is the last answer: a digit starts a new sum
    var last by rememberSaveable { mutableStateOf("") } // the sum that answer came from, shown above it
    var cantDivide by rememberSaveable { mutableStateOf(false) }
    val grouping = remember { NumberFormat.getIntegerInstance() }
    fun shown(text: String) = Calc.display(text) { grouping.format(BigInteger(it)) }

    fun press(key: Char) {
        cantDivide = false
        val from = if (answered && (key.isDigit() || key == '.')) "" else input
        input = Calc.press(from, key)
        answered = false
    }

    fun equals() {
        val sum = input.trimEnd(Calc::isOperator) // "5+" = is 5, as on most calculators
        if (sum.isEmpty() || answered) return
        pinAttempt(sum, pinLength)?.let(onPin)
        val value = Calc.evaluate(sum)
        if (value == null) {
            cantDivide = true
        } else {
            last = sum
            input = Calc.format(value)
            answered = true
        }
    }

    fun clear() {
        input = ""
        answered = false
        cantDivide = false
    }

    // Above the number: the sum an answer came from, or while typing a sum, what it comes to so far
    val above = when {
        answered -> shown(last)
        input.none(Calc::isOperator) -> ""
        else -> Calc.evaluate(input)?.let { shown(Calc.format(it)) }.orEmpty()
    }
    // Round keys a quarter of the screen across, made smaller when all five rows wouldn't fit (sideways, large fonts)
    val config = LocalConfiguration.current
    val keySize = minOf((config.screenHeightDp * 0.085f).dp, ((config.screenWidthDp - 76) / 4f).dp).coerceIn(44.dp, 88.dp)
    val roboto = TextStyle(fontFamily = FontFamily.Default)

    Column(
        Modifier.fillMaxSize().background(k.ground).systemBarsPadding().padding(start = 20.dp, end = 20.dp, top = Space.s6, bottom = Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.s2).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            horizontalAlignment = Alignment.End,
        ) {
            Text(above, Modifier.heightIn(min = 24.dp), style = roboto.copy(fontSize = 20.sp), color = k.muted, maxLines = 1, textAlign = TextAlign.End)
            BasicText(
                if (cantDivide) "Can't divide by 0" else shown(input).ifEmpty { "0" },
                Modifier.fillMaxWidth(),
                style = roboto.copy(color = if (cantDivide) k.muted else k.ink, fontWeight = FontWeight.Light, textAlign = TextAlign.End),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 56.sp),
            )
        }
        Column(Modifier.width(keySize * 4 + Space.s3 * 3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            // Circles, pressing to 0.94 over 100 ms as the design's keys do
            @Composable
            fun RowScope.Key(description: String, fill: Color = k.key, onLongClick: (() -> Unit)? = null, onClick: () -> Unit, face: @Composable () -> Unit) =
                PinKey(
                    Modifier.weight(1f).aspectRatio(1f), description, fill = fill, pressed = k.pressed, pressScale = 0.94f, pressMs = 100,
                    onLongClick = onLongClick, onClick = onClick, content = face,
                )

            @Composable
            fun Label(text: String, ink: Color = k.ink) = Text(text, style = roboto.copy(fontSize = 28.sp), color = ink)

            @Composable
            fun Line(content: @Composable RowScope.() -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), content = content)

            Line {
                Key("Clear", k.fn, onClick = ::clear) { Label("C") }
                Key("Percent", k.fn, onClick = { press('%') }) { Label("%") }
                Key("Delete", k.fn, onClick = {
                    input = if (answered) "" else input.dropLast(1)
                    answered = false
                    cantDivide = false
                }) { BackspaceIcon(k.ink) }
                Key("Divide", onClick = { press(Calc.DIVIDE) }) { Label("${Calc.DIVIDE}", k.op) }
            }
            listOf("789" to (Calc.TIMES to "Multiply"), "456" to (Calc.MINUS to "Minus"), "123" to (Calc.PLUS to "Plus")).forEach { (digits, op) ->
                Line {
                    digits.forEach { d -> Key("$d", onClick = { press(d) }) { Label("$d") } }
                    Key(op.second, onClick = { press(op.first) }) { Label("${op.first}", k.op) }
                }
            }
            Line {
                Key("Double zero", onClick = {
                    press('0')
                    press('0')
                }) { Label("00") }
                Key("0", onClick = { press('0') }) { Label("0") }
                Key("Point", onClick = { press('.') }) { Label(".") }
                Key("Equals", k.eq, onLongClick = onHold, onClick = ::equals) { Label("=", k.onEq) }
            }
        }
    }
}
