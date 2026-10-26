package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

// The fixtures are copies of typle-r's tests/fixtures/packs that have packs (data already in packs) and whose expected
// result needs no repair but separateLevels, which is all Kotlin does to a saved row (see readSaved): the app
// (tests/wordPacks.test.cjs) and Go (TestPackFixtures) read them into the same lists and packs.
private val fixtures = listOf(
  "duplicate-in-pack.json", "levels-equal.json", "levels-long.json", "levels-name.json", "levels-prefix.json", "levels-second-collision.json",
  "long-name-emoji.json", "pack-fields-kept.json",
)

private class PacksFixture(val lists: String, val packs: String, val expectedLists: JsonValue, val expectedPacks: JsonValue)

private fun packsFixture(name: String): PacksFixture {
  val top = JsonValue.parse(SeparateLevelsTest::class.java.getResource("/packs/$name")!!.readText()) as JsonObject
  val expected = top.members.getValue("expected") as JsonObject
  return PacksFixture(top.members.getValue("lists").toJson(), top.members.getValue("packs").toJson(),
    expected.members.getValue("lists"), expected.members.getValue("packs"))
}

private fun leveled(id: String, packId: String, levels: List<String>, name: String = id) =
  WordList(id, name, listOf(Word("ls", "ls", "")), emptyList(), "x", packId = packId,
    extra = if (levels.isEmpty()) "" else levels.joinToString(",", """{"levels":[""", "]}") { "\"$it\"" })

class SeparateLevelsTest {
  // The cases of Go's TestPackFixtures for a row with packs.
  @Test fun readsTheFixtures() {
    for (name in fixtures) {
      val f = packsFixture(name)
      val w = readSaved(f.lists, "[]", f.packs)
      val lists = encode(w.lists)
      assertEquals(f.expectedLists, JsonValue.parse(lists), "$name lists")
      assertEquals(f.expectedPacks, JsonValue.parse(encodePacks(w.packs)), "$name packs")
      // The result is what a save may send, and reading it again changes nothing.
      checkLibrary(JsonValue.parse(lists), JsonValue.parse(encodePacks(w.packs)))
      assertEquals(lists, encode(readSaved(lists, "[]", encodePacks(w.packs)).lists), "$name again")
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

  // Only later lists change, only where they meet, and only within a pack; the rest of a list is kept as it is.
  @Test fun separatesWhereTheyMeet() {
    val first = leveled("a", "p", listOf("中級1"))
    val second = leveled("b", "p", listOf("中級1", "A")).copy(extra = """{"future":1,"levels":["中級1","A"]}""")
    val other = leveled("c", "q", listOf("中級1"))
    val separated = separateLevels(listOf(first, second, other))
    assertSame(first, separated[0])
    assertEquals("""{"future":1,"levels":["中級1（2）","A"]}""", separated[1].extra)
    assertSame(other, separated[2])
    // A list without levels gets a name no other list of its pack has, compared trimmed and lowercased.
    val named = separateLevels(listOf(leveled("x", "p", listOf("Easy")), leveled("y", "p", emptyList(), name = "Easy"),
      leveled("z", "p", emptyList(), name = " easy（2） ")))
    assertEquals("Easy（3）", named[1].name)
    // Data without overlaps is the same lists.
    val apart = listOf(first, leveled("d", "p", listOf("中級2")))
    assertEquals(apart, separateLevels(apart))
  }

  // A row without packs (lists saved before packs existed) is read as it is: Kotlin does not make its packs.
  @Test fun leavesRowsWithoutPacks() {
    val lists = """[{"id":"a","name":"a","group":"G","levels":["初級"],"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]},""" +
      """{"id":"b","name":"b","group":"G","levels":["初級"],"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""
    assertEquals(decode(lists, "[]"), readSaved(lists, "[]", null))
  }
}
