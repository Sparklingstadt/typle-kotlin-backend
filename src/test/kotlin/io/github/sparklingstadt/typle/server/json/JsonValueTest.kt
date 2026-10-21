package io.github.sparklingstadt.typle.server.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JsonValueTest {
  @Test fun parsesValues() {
    val value = JsonValue.parse(" {\"a\": [1, -2.5e+3, true, false, null, \"x\\n\\u00e9\\/\"], \"b\": {}} ")
    assertIs<JsonObject>(value)
    val items = (value.members["a"] as JsonArray).items
    assertEquals(JsonNumber("1"), items[0])
    assertEquals(JsonNumber("-2.5e+3"), items[1])
    assertEquals(JsonBool(true), items[2])
    assertEquals(JsonBool(false), items[3])
    assertEquals(JsonNull, items[4])
    assertEquals(JsonString("x\n\u00e9/"), items[5])
    assertEquals(JsonObject(emptyMap()), value.members["b"])
  }

  // The last of a repeated name wins, as in Go's map and in JavaScript.
  @Test fun lastRepeatedNameWins() =
    assertEquals(JsonString("b"), (JsonValue.parse("{\"a\":\"a\",\"a\":\"b\"}") as JsonObject).members["a"])

  @Test fun surrogateEscapes() {
    assertEquals(JsonString("\ud83d\ude00"), JsonValue.parse("\"\\ud83d\\ude00\""))
    // Kept as the lone unit, so that a save can be refused for it (PostgreSQL's jsonb cannot store it).
    assertEquals(JsonString("\ud800"), JsonValue.parse("\"\\ud800\""))
    assertEquals(JsonString("\u0000"), JsonValue.parse("\"\\u0000\""))
  }

  @Test fun refusesWhatGoRefuses() {
    for (text in listOf("", " ", "[01]", "[1.]", "[.5]", "[+1]", "[1e]", "[NaN]", "[abc]", "[True]", "{a:1}", "[1,]",
      "{\"a\":1,}", "[1 2]", "\"a", "\"\\x\"", "\"\\u12\"", "\"a\u0001\"", "[] []", "{\"a\"}", "nul", "[-]", "'a'")) {
      assertFailsWith<JsonSyntaxException>(text) { JsonValue.parse(text) }
    }
  }

  @Test fun nestingIsLimitedAsInGo() {
    JsonValue.parse("[".repeat(10000) + "]".repeat(10000))
    assertFailsWith<JsonSyntaxException> { JsonValue.parse("[".repeat(10001) + "]".repeat(10001)) }
    assertFailsWith<JsonSyntaxException> { JsonValue.parse("[".repeat(1 shl 20)) }
  }

  @Test fun writesCompactJson() =
    assertEquals("{\"a\":[1,-2.5e+3,true,null,\"<\\u2028\"],\"b\":{}}",
      JsonValue.parse("{\"a\": [1, -2.5e+3, true, null, \"<\\u2028\"], \"b\": {}}").toJson())
}
