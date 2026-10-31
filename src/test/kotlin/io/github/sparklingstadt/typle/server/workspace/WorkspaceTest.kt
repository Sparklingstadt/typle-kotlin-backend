package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonValue
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
    assertTrue(MAX_LISTS == 150)
  }

  // The labels of lists sent by an app that does not know packs: the group is that of lists saved before packs, which
  // becomes a pack's name when they are read.
  @Test fun checkLimitsOfLabels() {
    val base = WordList("c", "c", listOf(Word("a", "a", "")), emptyList(), "x")
    checkLimits(listOf(base.copy(category = "a".repeat(MAX_CATEGORY), tags = List(MAX_TAGS) { "a".repeat(MAX_TAG) }),
      base.copy(group = "𠮷".repeat(MAX_GROUP / 2))))
    val tooMuch = mapOf(
      "category" to base.copy(category = "a".repeat(MAX_CATEGORY + 1)),
      "tags" to base.copy(tags = List(MAX_TAGS + 1) { "" }),
      "tag" to base.copy(tags = listOf("a".repeat(MAX_TAG + 1))),
      "group" to base.copy(group = "a".repeat(MAX_GROUP + 1)),
      "group in UTF-16 units" to base.copy(group = "𠮷".repeat(MAX_GROUP / 2) + "a"),
    )
    for ((name, over) in tooMuch) {
      assertEquals("invalidWorkspace", assertFailsWith<WorkspaceError>(name) { checkLimits(listOf(over)) }.key, name)
    }
  }

  // A list keeps its packId, group, category and tags as they are stored; they are read, not left in extra.
  @Test fun decodesLabels() {
    val w = decode("""[{"id":"a","name":"A","packId":"p","group":"G","category":"語学","tags":["英語","<b>"],"createdAt":"x",""" +
      """"words":[{"display":"a","input":"a","annotation":""}],"records":[]},""" +
      """{"id":"b","name":"B","category":null,"tags":null,"group":null,"packId":null,"createdAt":"x",""" +
      """"words":[{"display":"a","input":"a","annotation":""}],"records":[]}]""", "[]")
    val a = w.lists[0]
    assertEquals(listOf("p", "G", "語学", listOf("英語", "<b>"), ""), listOf(a.packId, a.group, a.category, a.tags, a.extra))
    val b = w.lists[1]
    assertEquals(listOf("", "", "", emptyList<String>()), listOf(b.packId, b.group, b.category, b.tags))
    assertTrue(""""name":"A","packId":"p","createdAt"""" in encode(w.lists), encode(w.lists))
    for (labels in listOf(""""category":1""", """"tags":"a"""", """"tags":[1]""", """"tags":[null]""", """"packId":1""", """"group":1""")) {
      assertFailsWith<InvalidWorkspaceException>(labels) {
        decode("""[{"id":"a","name":"A",$labels,"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]""", "[]")
      }
    }
  }

  // decodePacks of src/core/wordPacks.ts, as Go's TestDecodeRefusesBadPacks.
  @Test fun decodesPacks() {
    val pack = """{"id":"p","name":"P","createdAt":"2026-10-01T00:00:00.000Z","future":1}"""
    val list = """[{"id":"a","name":"a","packId":"p","createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""
    val w = decode(list, "[]", "[$pack]")
    assertEquals(listOf(Pack("p", "P", createdAt = "2026-10-01T00:00:00.000Z", extra = """{"future":1}""")), w.packs)
    assertEquals(emptyList(), decode(list, "[]").packs)
    for (bad in listOf("null", "{}", """[{"id":1,"name":"P","createdAt":"x"}]""", """[{"id":"","name":"P","createdAt":"x"}]""",
      """[{"id":"p","createdAt":"x"}]""", """[{"id":"p","name":"P"}]""", """[{"id":"p","name":"P","createdAt":"x","tags":"x"}]""",
      """[{"id":"p","name":"P","createdAt":"x","tags":[null]}]""", """[{"id":"p","name":"P","createdAt":"x","origin":{"catalogId":"c"}}]""",
      """[{"id":"p","name":"P","createdAt":"x","origin":"c"}]""", "[null]", "[$pack,$pack]")) {
      assertFailsWith<InvalidWorkspaceException>(bad) { decode(list, "[]", bad) }
    }
  }

  // EncodePacks: what a pack does not have is left out, and the fields Typle does not know are kept.
  @Test fun encodesPacks() {
    val packs = decode("[]", "[]", """[{"id":"a","name":"A","description":"","category":"語学","tags":["英語","<b>"],"createdAt":"x"},""" +
      """{"id":"b","name":"B","tags":[],"origin":{"catalogId":"c","version":2.50,"x":1},"createdAt":"x","future":[1]}]""").packs
    assertEquals("""[{"id":"a","name":"A","category":"語学","tags":["英語","<b>"],"createdAt":"x"},""" +
      """{"id":"b","name":"B","origin":{"catalogId":"c","version":2.5},"createdAt":"x","future":[1]}]""", encodePacks(packs))
    assertEquals("[]", encodePacks(emptyList()))
  }

  // validateLibrary (src/core/wordPacks.ts), as Go's TestCheckPacks.
  @Test fun checksPacks() {
    val list = WordList("a", "a", listOf(Word("ls", "ls", "")), emptyList(), "2026-10-01T00:00:00.000Z", packId = "p")
    val pack = Pack("p", "P", createdAt = "2026-10-01T00:00:00.000Z")
    checkPacks(listOf(pack), listOf(list))
    val q = pack.copy(id = "q", name = "Q")
    fun many(n: Int) = List(n) { pack.copy(id = "p$it", name = "P$it") } to List(n) { list.copy(id = "l$it", name = "l$it", packId = "p$it") }
    val (manyPacks, manyLists) = many(MAX_LISTS + 1)
    val cases = mapOf(
      "a list in a pack that does not exist" to (listOf(pack, q) to listOf(list.copy(packId = "r"),
        list.copy(id = "b", name = "b", packId = "q"), list.copy(id = "c", name = "c"))),
      "a list without a packId" to (listOf(pack) to listOf(list, list.copy(id = "b", name = "b", packId = ""))),
      "a pack without lists" to (listOf(pack, q) to listOf(list)),
      "two packs with one name" to (listOf(pack, pack.copy(id = "q", name = " p ")) to
        listOf(list, list.copy(id = "b", name = "b", packId = "q"))),
      "two packs with one id" to (listOf(pack, pack.copy(name = "Q")) to listOf(list)),
      "a blank pack name" to (listOf(pack.copy(name = " ")) to listOf(list)),
      "a pack name over the limit" to (listOf(pack.copy(name = "n".repeat(MAX_PACK_NAME + 1))) to listOf(list)),
      "a description over the limit" to (listOf(pack.copy(description = "d".repeat(MAX_PACK_DESCRIPTION + 1))) to listOf(list)),
      "a category over the limit" to (listOf(pack.copy(category = "c".repeat(MAX_CATEGORY + 1))) to listOf(list)),
      "a tag over the limit" to (listOf(pack.copy(tags = listOf("t".repeat(MAX_TAG + 1)))) to listOf(list)),
      "more tags than the limit" to (listOf(pack.copy(tags = List(MAX_TAGS + 1) { "t$it" })) to listOf(list)),
      "a list name repeated in a pack" to (listOf(pack) to listOf(list, list.copy(id = "b", name = "A"))),
      "a list with a group" to (listOf(pack) to listOf(list.copy(group = "g"))),
      "a list with a category" to (listOf(pack) to listOf(list.copy(category = "c"))),
      "a list with tags" to (listOf(pack) to listOf(list.copy(tags = listOf("t")))),
    )
    for ((reason, case) in cases) {
      assertEquals("invalidWorkspace", assertFailsWith<WorkspaceError>(reason) { checkPacks(case.first, case.second) }.key, reason)
    }
    // Packs have no limit of their own (every pack has a list, so the list limit bounds them), and a list name may be
    // used again in another pack.
    checkPacks(manyPacks, manyLists)
    checkPacks(listOf(pack, q), listOf(list, list.copy(id = "b", name = " A ", packId = "q")))
  }

  // Lists of a pack whose paths (levels, or the name without levels) overlap are levelsConflict, as Go's TestCheckPacks.
  @Test fun checksLevelOverlaps() {
    val pack = Pack("p", "P", createdAt = "x")
    val list = WordList("a", "a", listOf(Word("ls", "ls", "")), emptyList(), "x", packId = "p")
    fun leveled(id: String, levels: String) = list.copy(id = id, name = id, extra = """{"levels":$levels}""")
    fun refused(vararg lists: WordList) = assertFailsWith<WorkspaceError> { checkPacks(listOf(pack), lists.toList()) }.key
    assertEquals("levelsConflict", refused(leveled("a", """["初級"]"""), leveled("b", """["初級"]""")))
    assertEquals("levelsConflict", refused(list.copy(name = "初級"), leveled("b", """["初級"]""")))
    // Different levels (case and spaces count) do not overlap, nor do the same levels in another pack.
    checkPacks(listOf(pack), listOf(leveled("a", """["初級"]"""), leveled("b", """["初級（2）"]"""), leveled("c", """["Easy"]"""),
      leveled("d", """["easy"]"""), leveled("e", """[" Easy"]"""), leveled("f", """["中級1"]"""), leveled("g", """["中級2"]""")))
    val q = pack.copy(id = "q", name = "Q")
    checkPacks(listOf(pack, q), listOf(leveled("a", """["初級"]"""), leveled("b", """["初級"]""").copy(packId = "q")))
  }

  // A list with several levels (up to 5, sent by an app before v0.97.0) is taken and not checked for overlaps; more is
  // invalidWorkspace (Go's TestCheckPacksLevels).
  @Test fun checksSeveralLevels() {
    val pack = Pack("p", "P", createdAt = "x")
    val list = WordList("a", "a", listOf(Word("ls", "ls", "")), emptyList(), "x", packId = "p")
    fun leveled(id: String, levels: String) = list.copy(id = id, name = id, extra = """{"levels":$levels}""")
    fun refused(vararg lists: WordList) = assertFailsWith<WorkspaceError> { checkPacks(listOf(pack), lists.toList()) }.key
    checkPacks(listOf(pack), listOf(leveled("a", """["基本","初級"]"""), leveled("b", """["基本"]"""), leveled("c", """["基本","初級"]""")))
    checkPacks(listOf(pack), listOf(leveled("a", """["x","y","z","u","v"]""")))
    assertEquals("invalidWorkspace", refused(leveled("a", """["1","2","3","4","5","6"]""")))
    assertEquals("levelsConflict", refused(leveled("a", """["x"]"""), leveled("b", """["x"]""")))
    checkPacks(listOf(pack), listOf(leveled("a", """["基本"]"""), leveled("b", """["基本（2）"]"""), leveled("c", """[]""")))
  }

  // Pack names are compared trimmed and lowercased as JavaScript's toLowerCase does.
  @Test fun packNamesCompareAsJavaScript() {
    val list = WordList("a", "a", listOf(Word("ls", "ls", "")), emptyList(), "x", packId = "p")
    fun packs(first: String, second: String) = listOf(Pack("p", first, createdAt = "x"), Pack("q", second, createdAt = "x"))
    val lists = listOf(list, list.copy(id = "b", packId = "q"))
    for ((first, second) in listOf("\u0130" to "i\u0307", "ΟΔΟΣ" to "οδο\u03c2", "Linux" to "\u3000linux ")) {
      assertFailsWith<WorkspaceError>(first) { checkPacks(packs(first, second), lists) }
    }
    // Σ at the end of a word is ς, so it is not σ.
    checkPacks(packs("ΟΔΟΣ", "οδο\u03c3"), lists)
  }

  // validateLibrary for a save with lists and packs, read as they were sent, as Go's TestCheckLibrary.
  @Test fun checksLibrary() {
    val list = """{"id":"a","name":"a","packId":"p","createdAt":"2026-10-01T00:00:00.000Z","words":[{"display":"ls","input":"ls","annotation":""}],"records":[]}"""
    val pack = """{"id":"p","name":"P","createdAt":"2026-10-01T00:00:00.000Z"}"""
    fun withList(fields: String) = list.replace(""""records":[]""", """"records":[],$fields""")
    fun withPack(fields: String) = pack.replace(""""createdAt"""", """$fields,"createdAt"""")
    fun check(lists: String, packs: String) = checkLibrary(JsonValue.parse(lists), JsonValue.parse(packs))
    check("[$list]", "[${withPack(""""description":"d","category":"c","tags":["t"],"origin":{"catalogId":"x","version":1}""")}]")
    val cases = mapOf(
      "an empty category" to ("[${withList(""""category":""""")}]" to "[$pack]"),
      "a category" to ("[${withList(""""category":"c"""")}]" to "[$pack]"),
      "a null category" to ("[${withList(""""category":null""")}]" to "[$pack]"),
      "empty tags" to ("[${withList(""""tags":[]""")}]" to "[$pack]"),
      "tags" to ("[${withList(""""tags":["t"]""")}]" to "[$pack]"),
      "an empty group" to ("[${withList(""""group":""""")}]" to "[$pack]"),
      "a group" to ("[${withList(""""group":"g"""")}]" to "[$pack]"),
      "a pack that is not there" to ("[${list.replace("\"p\"", "\"missing\"")}]" to "[$pack]"),
      "a pack without lists" to ("[$list]" to "[$pack,${withPack(""""category":"c"""").replace("\"p\"", "\"q\"")}]"),
      "a null description" to ("[$list]" to "[${withPack(""""description":null""")}]"),
      "a null pack category" to ("[$list]" to "[${withPack(""""category":null""")}]"),
      "null pack tags" to ("[$list]" to "[${withPack(""""tags":null""")}]"),
      "a null origin" to ("[$list]" to "[${withPack(""""origin":null""")}]"),
      "packs not an array" to ("[$list]" to "{}"),
      "null packs" to ("[$list]" to "null"),
      "a pack without a name" to ("[$list]" to """[{"id":"p","createdAt":"2026-10-01T00:00:00.000Z"}]"""),
      "an unreadable list" to ("""[{"id":""}]""" to "[$pack]"),
      "lists not an array" to ("{}" to "[$pack]"),
    )
    for ((name, case) in cases) {
      assertEquals("invalidWorkspace", assertFailsWith<WorkspaceError>(name) { check(case.first, case.second) }.key, name)
    }
  }
}
