package io.github.sparklingstadt.typle.server.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncodeTest {
  private val stored = """[{
    "id": "l1", "name": " 英単語 ", "createdAt": "2026-10-10T01:23:45.000Z", "shared": true, "shareId": "s",
    "words": [{ "display": "apple", "input": "apple", "annotation": "りんご" }],
    "records": [
      { "time": 5, "date": "2024/09/22 10:05" },
      { "id": "r1", "time": 63.5, "date": "2026-10-10T02:00:00.000Z", "mistakes": 2, "accuracy": 97.5, "wordCount": 1,
        "device": { "id": "d1", "name": "自宅", "layout": "JIS" } }
    ]
  }]"""

  // What Go's workspace.Encode writes for the same row.
  @Test fun encodesAsGo() {
    val w = decode(stored, "[]")
    assertEquals(
      """[{"id":"l1","name":" 英単語 ","createdAt":"2026-10-10T01:23:45.000Z",""" +
        """"words":[{"display":"apple","input":"apple","annotation":"りんご"}],""" +
        """"records":[{"time":5,"date":"2024/09/22 10:05"},{"id":"r1","time":63.5,"date":"2026-10-10T02:00:00.000Z",""" +
        """"mistakes":2,"accuracy":97.5,"wordCount":1,"device":{"id":"d1","name":"自宅","layout":"JIS"}}]}]""",
      encode(w.lists))
    assertEquals(w, decode(encode(w.lists), "[]"))
  }

  @Test fun htmlIsNotEscaped() {
    val encoded = encode(listOf(WordList("a", "<&>", listOf(Word("a", "a", "")), emptyList(), "x")))
    assertTrue("\"<&>\"" in encoded, encoded)
  }

  @Test fun emptyParts() {
    assertTrue("\"records\":[]" in encode(listOf(WordList("a", "a", listOf(Word("a", "a", "")), emptyList(), "x"))))
    assertEquals("[]", encode(emptyList()))
    assertEquals("[]", encodeDevices(emptyList()))
    assertEquals("""[{"id":"d","name":"<n>","layout":""}]""", encodeDevices(listOf(Device("d", "<n>", ""))))
  }

  @Test fun numbersAsGo() {
    val w = decode("""[{"id":"l","name":"n","createdAt":"c","words":[{"display":"a","input":"a","annotation":""}],
      "records":[{"time":1e2,"date":"2026-01-01","accuracy":97.50,"mistakes":-0}]}]""", "[]")
    assertTrue("""{"time":100,"date":"2026-01-01","mistakes":0,"accuracy":97.5}""" in encode(w.lists), encode(w.lists))
  }

  // Like the app's decodeLists, fields Typle does not know are kept: only shared and shareId are dropped from lists.
  // They follow the known fields, with sorted names and compact values, as Go writes them.
  @Test fun keepsUnknownFields() {
    val w = decode("""[{ "id": "l1", "name": "a", "createdAt": "x", "shared": true, "shareId": "s", "color": "red",
      "words": [{ "display": "a", "input": "a", "annotation": "", "zeta": 1, "alpha": {"x": [1, 2]} }],
      "records": [{ "time": 1, "date": "2026-10-10T00:00:00.000Z", "keys": [1, 2],
        "device": { "id": "d1", "name": "自宅", "layout": "JIS", "model": "HHKB" } }] }]""", "[]")
    val encoded = encode(w.lists)
    assertEquals(
      """[{"id":"l1","name":"a","createdAt":"x","words":[{"display":"a","input":"a","annotation":"","alpha":{"x":[1,2]},"zeta":1}],""" +
        """"records":[{"time":1,"date":"2026-10-10T00:00:00.000Z","device":{"id":"d1","name":"自宅","layout":"JIS","model":"HHKB"},""" +
        """"keys":[1,2]}],"color":"red"}]""",
      encoded)
    assertFalse("share" in encoded)
    assertEquals(w, decode(encoded, "[]"))
  }

  @Test fun registeredDevicesAreTrimmedAndKeepNoExtra() {
    val w = decode("[]", """[{"id":"d","name":" n ","layout":" JIS ","model":"x"}]""")
    assertEquals("""[{"id":"d","name":"n","layout":"JIS"}]""", encodeDevices(w.devices))
  }
}
