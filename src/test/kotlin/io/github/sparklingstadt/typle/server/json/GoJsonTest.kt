package io.github.sparklingstadt.typle.server.json

import kotlin.test.Test
import kotlin.test.assertEquals

class GoJsonTest {
  @Test fun stringWithoutHtmlEscape() =
    assertEquals("\"A & B <x>\\u2028\"", GoJson.string("A & B <x>\u2028", escapeHtml = false))

  @Test fun stringWithHtmlEscape() =
    assertEquals("\"\\u0026\\u003c\\u003e\"", GoJson.string("&<>", escapeHtml = true))

  @Test fun controlCharacters() =
    assertEquals("\"\\n\\t\\r\\u0001\\\\\\\"\"", GoJson.string("\n\t\r\u0001\\\"", escapeHtml = false))

  @Test fun backspaceAndFormFeed() =
    assertEquals("\"\\b\\f\\u001f\"", GoJson.string("\b\u000c\u001f", escapeHtml = false))

  @Test fun loneSurrogateIsReplaced() =
    assertEquals("\"\\ufffd\"", GoJson.string("\ud800", escapeHtml = false))

  @Test fun surrogatePairIsKept() =
    assertEquals("\"\ud83d\ude00\"", GoJson.string("\ud83d\ude00", escapeHtml = false))

  @Test fun numbers() {
    assertEquals("1", GoJson.number(1.0))
    assertEquals("1.5", GoJson.number(1.5))
    assertEquals("100", GoJson.number(1e2))
    assertEquals("0.000001", GoJson.number(1e-6))
    assertEquals("1e-7", GoJson.number(1e-7))
    assertEquals("1e+21", GoJson.number(1e21))
    assertEquals("1.5e+300", GoJson.number(1.5e300))
    assertEquals("123456789012345680000", GoJson.number(1.2345678901234568e20))
    assertEquals("0.1", GoJson.number(0.1))
    assertEquals("-2.5", GoJson.number(-2.5))
    assertEquals("-0", GoJson.number(-0.0))
    assertEquals("0", GoJson.number(0.0))
  }

  @Test fun compactJsonbText() =
    assertEquals("""[{"a":"x <y>","b":[1,2]}]""", GoJson.compact("""[{"a": "x <y>", "b": [1, 2]}]""", escapeHtml = false))

  @Test fun compactWithHtmlEscape() =
    assertEquals("{\"a\":\"\\u003c\\u2028\"}", GoJson.compact("{\"a\": \"<\u2028\"}", escapeHtml = true))

  @Test fun compactKeepsEscapesAndSpacesInStrings() =
    assertEquals("{\"a b\":\"\\\" \\u00e9 \"}", GoJson.compact("{ \"a b\" : \"\\\" \\u00e9 \" }", escapeHtml = false))
}
