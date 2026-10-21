package io.github.sparklingstadt.typle.server.workspace

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextTest {
  @Test fun trimsAsJavaScript() {
    assertEquals("a", trimJS(" ﻿ a 　"))
    assertEquals("a", trimJS("\t\n\u000b\u000c\r        a"))
    assertEquals("\u0085a", trimJS("\u0085a"))
    assertEquals("​a", trimJS("​a"))
    assertEquals("", trimJS(" 　 "))
  }

  @Test fun utf16Length() {
    assertEquals(2, utf16Length("😀"))
    assertEquals(3, utf16Length("aあé"))
  }

  // The cases of TestParseTime in server/internal/workspace/workspace_test.go.
  @Test fun parsesStoredDates() {
    fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, nanos: Int = 0) =
      LocalDateTime.of(y, mo, d, h, mi, s, nanos).atZone(ZoneId.systemDefault()).toInstant()
    val cases = mapOf(
      "2026-10-10T02:00:00.000Z" to Instant.parse("2026-10-10T02:00:00Z"),
      "2026-09-26T03:01:23.866446Z" to Instant.parse("2026-09-26T03:01:23.866446Z"),
      "2026-10-10T11:00:00+09:00" to Instant.parse("2026-10-10T02:00:00Z"),
      "2026-10-10T02:00Z" to Instant.parse("2026-10-10T02:00:00Z"),
      "2026-01-01" to Instant.parse("2026-01-01T00:00:00Z"),
      "2026-01-01T09:30" to local(2026, 1, 1, 9, 30, 0),
      "2026-01-01T09:30:15.5" to local(2026, 1, 1, 9, 30, 15, 500_000_000),
      "2024/09/22 10:05" to local(2024, 9, 22, 10, 5, 0),
      "2024/9/2 10:05:07" to local(2024, 9, 2, 10, 5, 7),
      "2024/09/22" to local(2024, 9, 22, 0, 0, 0),
    )
    for ((input, want) in cases) assertEquals(want, parseTime(input), input)
    for (input in listOf("", "yesterday", "2026-13-01", "10:05", "2026-02-30", "2026-01-01T24:00", "2026/1/1 9:60")) {
      assertNull(parseTime(input), input)
    }
  }
}
