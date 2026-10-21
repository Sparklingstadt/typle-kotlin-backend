package io.github.sparklingstadt.typle.server.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The saved row of server/internal/workspace/workspace_test.go.
private const val LISTS = """[{
  "id": "l1", "name": " 英単語 ", "createdAt": "2026-10-10T01:23:45.000Z", "shared": true, "shareId": "s",
  "words": [{ "display": "apple", "input": "apple", "annotation": "りんご" }],
  "records": [
    { "time": 5, "date": "2024/09/22 10:05" },
    { "id": "r1", "time": 63.5, "date": "2026-10-10T02:00:00.000Z", "mistakes": 2, "accuracy": 97.5, "wordCount": 1,
      "device": { "id": "d1", "name": "自宅", "layout": "JIS" } }
  ]
}]"""

private const val DEVICES = """[{ "id": "d1", "name": " 自宅 ", "layout": " JIS " }]"""

private fun list(records: String) =
  """[{"id":"l","name":"n","createdAt":"c","words":[{"display":"a","input":"a","annotation":""}],"records":[$records]}]"""

private fun word(fields: String) = """[{"id":"l","name":"n","createdAt":"c","words":[{$fields}],"records":[]}]"""

class WorkspaceTest {
  @Test fun decodesSavedRow() {
    val w = decode(LISTS, DEVICES)
    assertEquals(1, w.lists.size)
    assertEquals(" 英単語 ", w.lists[0].name)
    assertEquals(2, w.lists[0].records.size)
    val record = w.lists[0].records[1]
    assertEquals(Record("r1", 63.5, "2026-10-10T02:00:00.000Z", 2, 97.5, 1, Device("d1", "自宅", "JIS")), record)
    assertEquals(Record(null, 5.0, "2024/09/22 10:05", null, null, null, null), w.lists[0].records[0])
    assertEquals(listOf(Device("d1", "自宅", "JIS")), w.devices)
  }

  @Test fun decodesEmptyRow() {
    for (devices in listOf("[]", "null")) {
      val w = decode("[]", devices)
      assertEquals(emptyList(), w.lists)
      assertEquals(emptyList(), w.devices)
    }
  }

  @Test fun refusesWhatTheAppRefuses() {
    val words = """"words":[{"display":"a","input":"a","annotation":""}],"records":[]"""
    val cases = mapOf(
      "lists not JSON" to ("{" to "[]"),
      "lists null" to ("null" to "[]"),
      "lists object" to ("{}" to "[]"),
      "null list" to ("[null]" to "[]"),
      "list a number" to ("[1]" to "[]"),
      "empty id" to ("""[{"id":"","name":"n","createdAt":"c",$words}]""" to "[]"),
      "id a number" to ("""[{"id":1,"name":"n","createdAt":"c",$words}]""" to "[]"),
      "duplicate id" to ("""[{"id":"l","name":"n","createdAt":"c",$words},{"id":"l","name":"m","createdAt":"c",$words}]""" to "[]"),
      "blank name" to ("""[{"id":"l","name":" ","createdAt":"c",$words}]""" to "[]"),
      "no createdAt" to ("""[{"id":"l","name":"n",$words}]""" to "[]"),
      "no words" to ("""[{"id":"l","name":"n","createdAt":"c","words":[],"records":[]}]""" to "[]"),
      "words null" to ("""[{"id":"l","name":"n","createdAt":"c","words":null,"records":[]}]""" to "[]"),
      "null word" to ("""[{"id":"l","name":"n","createdAt":"c","words":[null],"records":[]}]""" to "[]"),
      "blank input" to (word(""""display":"a","input":" ","annotation":""""") to "[]"),
      "blank display" to (word(""""display":"　","input":"a","annotation":""""") to "[]"),
      "no annotation" to (word(""""display":"a","input":"a"""") to "[]"),
      "annotation null" to (word(""""display":"a","input":"a","annotation":null""") to "[]"),
      "no records" to ("""[{"id":"l","name":"n","createdAt":"c","words":[{"display":"a","input":"a","annotation":""}]}]""" to "[]"),
      "null record" to (list("null") to "[]"),
      "negative time" to (list("""{"time":-1,"date":"2026-01-01"}""") to "[]"),
      "time a string" to (list("""{"time":"1","date":"2026-01-01"}""") to "[]"),
      "time out of range" to (list("""{"time":1e400,"date":"2026-01-01"}""") to "[]"),
      "no date" to (list("""{"time":1}""") to "[]"),
      "bad date" to (list("""{"time":1,"date":"yesterday"}""") to "[]"),
      "accuracy 101" to (list("""{"time":1,"date":"2026-01-01","accuracy":101}""") to "[]"),
      "negative accuracy" to (list("""{"time":1,"date":"2026-01-01","accuracy":-0.5}""") to "[]"),
      "fraction mistake" to (list("""{"time":1,"date":"2026-01-01","mistakes":1.5}""") to "[]"),
      "mistakes 1.0" to (list("""{"time":1,"date":"2026-01-01","mistakes":1.0}""") to "[]"),
      "mistakes 1e2" to (list("""{"time":1,"date":"2026-01-01","mistakes":1e2}""") to "[]"),
      "mistakes past int64" to (list("""{"time":1,"date":"2026-01-01","mistakes":9223372036854775808}""") to "[]"),
      "negative mistakes" to (list("""{"time":1,"date":"2026-01-01","mistakes":-1}""") to "[]"),
      "zero wordCount" to (list("""{"time":1,"date":"2026-01-01","wordCount":0}""") to "[]"),
      "wordCount 1.0" to (list("""{"time":1,"date":"2026-01-01","wordCount":1.0}""") to "[]"),
      "record id a number" to (list("""{"id":1,"time":1,"date":"2026-01-01"}""") to "[]"),
      "bad device" to (list("""{"time":1,"date":"2026-01-01","device":{"id":"d","name":" ","layout":""}}""") to "[]"),
      "device a string" to (list("""{"time":1,"date":"2026-01-01","device":"d"}""") to "[]"),
      "device name a number" to (list("""{"time":1,"date":"2026-01-01","device":{"id":"d","name":1,"layout":""}}""") to "[]"),
      "devices not array" to ("[]" to "{}"),
      "default device" to ("[]" to """[{"id":"default","name":"x","layout":""}]"""),
      "duplicate device" to ("[]" to """[{"id":"d","name":"x","layout":""},{"id":"d","name":"y","layout":""}]"""),
      "device no layout" to ("[]" to """[{"id":"d","name":"x"}]"""),
      "device null" to ("[]" to "[null]"),
      "long device name" to ("[]" to """[{"id":"d","name":"${"x".repeat(101)}","layout":""}]"""),
      "too many devices" to ("[]" to (1..101).joinToString(",", "[", "]") { """{"id":"d$it","name":"x","layout":""}""" }),
    )
    for ((name, row) in cases) {
      assertFailsWith<InvalidWorkspaceException>(name) { decode(row.first, row.second) }
    }
  }

  @Test fun acceptsWhatGoAccepts() {
    val w = decode(list("""{"time":1e2,"date":"2026-01-01","accuracy":1e1,"mistakes":-0}"""), "[]")
    assertEquals(Record(null, 100.0, "2026-01-01", 0, 10.0, null, null), w.lists[0].records[0])
    // A device copied into a record keeps a missing or null field as "" (Go's plain string fields); only a registered
    // device needs all three.
    val withDevice = decode(list("""{"time":1,"date":"2026-01-01","device":{"id":"d","name":"n","layout":null}}"""), "[]")
    assertEquals(Device("d", "n", ""), withDevice.lists[0].records[0].device)
  }

  // The last of a repeated name wins, as in Go and JavaScript.
  @Test fun lastRepeatedNameWins() {
    val w = decode(word(""""display":"a","display":"b","input":"a","annotation":"""""), "[]")
    assertEquals("b", w.lists[0].words[0].display)
  }

  @Test fun nullInAnOptionalFieldIsLeftOut() {
    val w = decode(list("""{"id":null,"time":1,"date":"2026-10-10T00:00:00.000Z","mistakes":null,"accuracy":null,"wordCount":null,"device":null}"""), "[]")
    assertEquals(Record(null, 1.0, "2026-10-10T00:00:00.000Z", null, null, null, null), w.lists[0].records[0])
    assertFalse("null" in encode(w.lists))
  }

  @Test fun checkLimits() {
    fun words(n: Int) = List(n) { Word("a", "a", "") }
    val records = List(MAX_RECORDS) { Record(null, 1.0, "2026-01-01", null, null, null, null) }
    val ok = listOf(
      WordList("a", "a".repeat(MAX_LIST_NAME), words(MAX_WORDS), records, "x"),
      WordList("b", "𠮷".repeat(MAX_LIST_NAME / 2), listOf(Word("a".repeat(MAX_WORD_TEXT), "a".repeat(MAX_WORD_TEXT),
        "a".repeat(MAX_ANNOTATION))), emptyList(), "x"),
    )
    checkLimits(ok)
    val base = WordList("c", "c", words(1), emptyList(), "x")
    val tooMuch = mapOf(
      "words" to base.copy(words = words(MAX_WORDS + 1)),
      "records" to base.copy(records = records + records.first()),
      "name" to base.copy(name = "a".repeat(MAX_LIST_NAME + 1)),
      "name in UTF-16 units" to base.copy(name = "𠮷".repeat(MAX_LIST_NAME / 2) + "a"),
      "input" to base.copy(words = listOf(Word("a", "a".repeat(MAX_WORD_TEXT + 1), ""))),
      "display" to base.copy(words = listOf(Word("a".repeat(MAX_WORD_TEXT + 1), "a", ""))),
      "annotation" to base.copy(words = listOf(Word("a", "a", "a".repeat(MAX_ANNOTATION + 1)))),
    )
    for ((name, over) in tooMuch) {
      val error = assertFailsWith<WorkspaceError>(name) { checkLimits(listOf(ok[0], over)) }
      assertEquals("invalidWorkspace", error.key)
    }
    assertTrue(MAX_LISTS == 100)
  }
}
