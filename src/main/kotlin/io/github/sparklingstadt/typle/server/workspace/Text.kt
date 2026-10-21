package io.github.sparklingstadt.typle.server.workspace

import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

// What JavaScript's trim removes: White_Space and U+FEFF, but not U+0085.
private fun isJsSpace(c: Char) = when (c) {
  '\t', '\n', '\u000b', '\u000c', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　',
  '﻿' -> true
  else -> c in ' '..' '
}

/** JavaScript's trim. The app trims names and words with it. */
fun trimJS(value: String): String = value.trim(::isJsSpace)

/** The length of value as JavaScript measures it, in UTF-16 code units. */
fun utf16Length(value: String): Int = value.length

// The forms Go's ParseTime (server/internal/workspace/time.go) reads, as its layouts parse them: hours and the slash
// form's month and day take one or two digits, every other number its full width, and seconds may have a fraction.
private const val CLOCK = "T([0-9]{1,2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]+))?)?"
private val zoned = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})${CLOCK}(Z|[+-][0-9]{2}:[0-9]{2})")
private val utcDate = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
private val localIso = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})$CLOCK")
private val slashed =
  Regex("([0-9]{4})/([0-9]{1,2})/([0-9]{1,2})(?: ([0-9]{1,2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]+))?)?)?")

/**
 * Reads a stored date as JavaScript's Date.parse does for the forms Typle stores: toISOString for its own records, a
 * date alone as UTC, and a date with a time but no zone, or the slash form, as the server's local time.
 */
fun parseTime(value: String): Instant? {
  zoned.matchEntire(value)?.let { m ->
    val local = dateTime(m.groupValues) ?: return null
    val zone = m.groupValues[8]
    if (zone == "Z") return local.toInstant(ZoneOffset.UTC)
    val hours = zone.substring(1, 3).toInt()
    val minutes = zone.substring(4, 6).toInt()
    if (hours > 24 || minutes > 60) return null
    val seconds = (hours * 3600 + minutes * 60) * (if (zone[0] == '-') -1 else 1)
    return local.toInstant(ZoneOffset.UTC).minusSeconds(seconds.toLong())
  }
  utcDate.matchEntire(value)?.let { m ->
    return dateTime(m.groupValues.take(4) + listOf("0", "00", "", ""))?.toInstant(ZoneOffset.UTC)
  }
  val local = (localIso.matchEntire(value) ?: slashed.matchEntire(value))?.let { m ->
    dateTime(m.groupValues.map { it })
  } ?: return null
  return local.atZone(ZoneId.systemDefault()).toInstant()
}

// groups: whole match, year, month, day, hour, minute, second, fraction.
private fun dateTime(groups: List<String>): LocalDateTime? {
  val year = groups[1].toInt()
  val month = groups[2].toInt()
  val day = groups[3].toInt()
  val hour = groups.getOrNull(4).orEmpty().ifEmpty { "0" }.toInt()
  val minute = groups.getOrNull(5).orEmpty().ifEmpty { "0" }.toInt()
  val second = groups.getOrNull(6).orEmpty().ifEmpty { "0" }.toInt()
  if (month !in 1..12 || day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) return null
  if (hour > 23 || minute > 59 || second > 59) return null
  // Go reads at most nine digits of a fraction and drops the rest.
  val nanos = groups.getOrNull(7).orEmpty().take(9).padEnd(9, '0').toInt()
  return LocalDateTime.of(year, month, day, hour, minute, second, nanos)
}
