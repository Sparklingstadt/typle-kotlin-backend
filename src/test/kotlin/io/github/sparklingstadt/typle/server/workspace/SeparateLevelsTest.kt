package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

// The fixtures are copies of typle-r's tests/fixtures/packs: the app (tests/wordPacks.test.cjs) and Go (TestPackFixtures,
// which reads them without following the catalog too) read them into the same lists and packs (Go's normalized:
// repairLibrary, which ends with separateLevels).
private val fixtures = java.io.File(SeparateLevelsTest::class.java.getResource("/packs")!!.toURI()).list()!!
  .filter { it.endsWith(".json") }.sorted()

private class PacksFixture(val lists: String, val packs: String?, val expectedLists: JsonValue, val expectedPacks: JsonValue)

private fun packsFixture(name: String): PacksFixture {
  val top = JsonValue.parse(SeparateLevelsTest::class.java.getResource("/packs/$name")!!.readText()) as JsonObject
  val expected = top.members.getValue("expected") as JsonObject
  return PacksFixture(top.members.getValue("lists").toJson(), top.members.getValue("packs").takeIf { it != io.github.sparklingstadt.typle.server.json.JsonNull }?.toJson(),
    expected.members.getValue("lists"), expected.members.getValue("packs"))
}

private fun leveled(id: String, packId: String, levels: List<String>, name: String = id) =
  WordList(id, name, listOf(Word("ls", "ls", "")), emptyList(), "x", packId = packId,
    extra = if (levels.isEmpty()) "" else levels.joinToString(",", """{"levels":[""", "]}") { "\"$it\"" })

// Go's normalized: the row decoded and its library repaired (repairLibrary, which separates the levels), before it
// follows the catalog.
private fun normalized(lists: String, devices: String, packs: String?): Workspace {
  val w = decode(lists, devices, packs)
  val (repairedPacks, repairedLists) = repairLibrary(w.packs, w.lists)
  return w.copy(lists = repairedLists, packs = repairedPacks)
}

class SeparateLevelsTest {
  // The cases of Go's TestPackFixtures for a row with packs.
  @Test fun readsTheFixtures() {
    for (name in fixtures) {
      val f = packsFixture(name)
      val w = normalized(f.lists, "[]", f.packs)
      val lists = encode(w.lists)
      assertEquals(f.expectedLists, JsonValue.parse(lists), "$name lists")
      assertEquals(f.expectedPacks, JsonValue.parse(encodePacks(w.packs)), "$name packs")
      // The result is what a save may send, and reading it again changes nothing.
      checkLibrary(JsonValue.parse(lists), JsonValue.parse(encodePacks(w.packs)))
      assertEquals(lists, encode(normalized(lists, "[]", encodePacks(w.packs)).lists), "$name again")
    }
  }

  // A level or name with （n） stays within its limit in UTF-16 code units, and a character of two units is not split
  // (Go's TestSeparateLevelsLimit).
  @Test fun staysWithinTheLimits() {
    val lists = (0 until 12).map { leveled("l$it", "p", listOf("a".repeat(MAX_LEVEL))) } +
      leveled("m", "q", emptyList(), name = "😀".repeat(25)) + leveled("n", "q", listOf("😀".repeat(25)))
    val separated = separateLevels(lists)
    assertEquals(listOf("a".repeat(46) + "（12）"), levelsOf(separated[11]))
    for (list in separated) {
      assert(utf16Length(list.name) <= MAX_LIST_NAME && levelsOf(list).all { utf16Length(it) <= MAX_LEVEL }) { list.id }
    }
    assertEquals(listOf("😀".repeat(23) + "（2）"), levelsOf(separated[13]))
    // A name cut inside a character of two units drops its first half.
    assertEquals("😀".repeat(98) + "（2）", fit("😀".repeat(100), "（2）", MAX_LIST_NAME))
  }

  // Several levels of a list are joined into one with ・, trimmed and without empty ones, within MAX_LEVEL UTF-16 code
  // units (Go's TestCollapseLevels).
  @Test fun joinsSeveralLevels() {
    val cases = listOf(
      listOf("基本", "初級") to listOf("基本・初級"),
      listOf(" 基本 ", "", "  ", "初級") to listOf("基本・初級"),
      listOf("基本") to listOf("基本"),
      listOf("", " ") to emptyList(),
      listOf("・基本・ ", " ・ ", "初級・") to listOf("基本・初級"),
      listOf("・", " ") to emptyList(),
      listOf("a".repeat(48), "😀") to listOf("a".repeat(48)),
      listOf("a".repeat(30), "b".repeat(30)) to listOf("a".repeat(30) + "・" + "b".repeat(19)),
      listOf("😀".repeat(20), "😀".repeat(20)) to listOf("😀".repeat(20) + "・" + "😀".repeat(4)),
    )
    for ((levels, want) in cases) assertEquals(want, levelsOf(separateLevels(listOf(leveled("a", "p", levels)))[0]), "$levels")
    // A list left without levels has no levels member, and the fields Typle does not know are kept.
    val none = separateLevels(listOf(leveled("a", "p", listOf("", " ")).copy(extra = """{"future":1,"levels":["",""]}""")))
    assertEquals("""{"future":1}""", none[0].extra)
  }

  // Only later lists change, only where they meet, and only within a pack; the rest of a list is kept as it is.
  @Test fun separatesWhereTheyMeet() {
    val first = leveled("a", "p", listOf("中級1"))
    val second = leveled("b", "p", listOf("中級1")).copy(extra = """{"future":1,"levels":["中級1"]}""")
    val other = leveled("c", "q", listOf("中級1"))
    val separated = separateLevels(listOf(first, second, other))
    assertSame(first, separated[0])
    assertEquals("""{"future":1,"levels":["中級1（2）"]}""", separated[1].extra)
    assertSame(other, separated[2])
    // A list without levels gets a name no other list of its pack has, compared trimmed and lowercased.
    val named = separateLevels(listOf(leveled("x", "p", listOf("Easy")), leveled("y", "p", emptyList(), name = "Easy"),
      leveled("z", "p", emptyList(), name = " easy（2） ")))
    assertEquals("Easy（3）", named[1].name)
    // Data without overlaps is the same lists.
    val apart = listOf(first, leveled("d", "p", listOf("中級2")))
    assertEquals(apart, separateLevels(apart))
  }

  // A row without packs is no longer converted by its groups (typle-r v2.0.0): each list gets a pack of its own.
  @Test fun givesRowsWithoutPacksAPackEach() {
    val lists = """[{"id":"a","name":"a","group":"G","levels":["初級"],"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]},""" +
      """{"id":"b","name":"b","group":"G","levels":["初級"],"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""
    val w = readSaved(lists, "[]", null)
    assertEquals(listOf("pack-a" to "a", "pack-b" to "b"), w.packs.map { it.id to it.name })
    assertEquals(listOf(listOf("初級"), listOf("初級")), w.lists.map(::levelsOf))
  }
}
