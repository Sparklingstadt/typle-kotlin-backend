// Package json writes JSON as Go's encoding/json does, so that what the Kotlin backend signs, stores and answers is the
// same text as the Go backend's (server/internal/session, server/internal/workspace, server/internal/web).
package io.github.sparklingstadt.typle.server.json

import java.math.BigDecimal

object GoJson {
  private const val HEX = "0123456789abcdef"

  /**
   * A JSON string as Go's encoder writes it: control characters as short escapes or \u00XX, U+2028 and U+2029 always
   * escaped, an unpaired surrogate (invalid UTF-8 to Go) as �, and with [escapeHtml] < > & as < > &
   * (Go's default; the session and the stored lists turn it off, as JSON.stringify writes them).
   */
  fun string(value: String, escapeHtml: Boolean): String {
    val out = StringBuilder(value.length + 2).append('"')
    var i = 0
    while (i < value.length) {
      val c = value[i]
      when {
        c == '\\' || c == '"' -> out.append('\\').append(c)
        c == '\b' -> out.append("\\b")
        c == '\u000c' -> out.append("\\f")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c < ' ' -> unicodeEscape(out, c.code)
        escapeHtml && (c == '<' || c == '>' || c == '&') -> unicodeEscape(out, c.code)
        c == ' ' || c == ' ' -> unicodeEscape(out, c.code)
        c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
          out.append(c).append(value[i + 1])
          i++
        }
        c.isSurrogate() -> out.append("\\ufffd")
        else -> out.append(c)
      }
      i++
    }
    return out.append('"').toString()
  }

  /**
   * A JSON number as Go writes a float64: the shortest digits that read back as [value], in plain notation from 1e-6
   * up to 1e21 and in exponent notation (1e-7, 1e+21) outside it, as JavaScript does.
   */
  fun number(value: Double): String {
    require(value.isFinite()) { "JSON has no $value" }
    val negative = value < 0 || (value == 0.0 && 1 / value < 0)
    val sign = if (negative) "-" else ""
    if (value == 0.0) return sign + "0"
    // Double.toString gives the shortest digits that read back as the value (JDK 19 and later).
    val decimal = BigDecimal(Math.abs(value).toString()).stripTrailingZeros()
    val abs = Math.abs(value)
    if (abs >= 1e-6 && abs < 1e21) return sign + decimal.toPlainString()
    val digits = decimal.unscaledValue().toString()
    val exponent = digits.length - 1 - decimal.scale()
    val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
    val exponentText = if (exponent < 0) "-" + (-exponent) else "+" + exponent.toString().padStart(2, '0')
    return sign + mantissa + "e" + exponentText
  }

  /**
   * [raw], a valid JSON text, without the whitespace between its tokens, as Go's json.Compact and its encoder write a
   * json.RawMessage: strings are kept as written, escapes and all, except that U+2028 and U+2029 are escaped and, with
   * [escapeHtml], < > &.
   */
  fun compact(raw: String, escapeHtml: Boolean): String {
    val out = StringBuilder(raw.length)
    var inString = false
    var escaped = false
    for (c in raw) {
      when {
        escapeHtml && (c == '<' || c == '>' || c == '&') -> unicodeEscape(out, c.code)
        c == ' ' || c == ' ' -> unicodeEscape(out, c.code)
        inString -> {
          out.append(c)
          when {
            escaped -> escaped = false
            c == '\\' -> escaped = true
            c == '"' -> inString = false
          }
        }
        c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {}
        else -> {
          out.append(c)
          if (c == '"') inString = true
        }
      }
    }
    return out.toString()
  }

  private fun unicodeEscape(out: StringBuilder, code: Int) {
    out.append("\\u")
    for (shift in intArrayOf(12, 8, 4, 0)) out.append(HEX[(code shr shift) and 0xf])
  }
}
