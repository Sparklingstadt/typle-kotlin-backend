package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The fixtures are copies of typle-r's tests/fixtures/follow, which the app (tests/followedPacks.test.cjs) and Go
// (TestFollowFixtures in server/internal/workspace/follow_test.go) check against.
private val fixtures = listOf(
  "migrate-copies.json", "migrate-unconverted.json", "sync-added-removed.json", "sync-duplicate-origin.json",
  "sync-keeps-name.json", "sync-position.json", "sync-removed-pack.json", "sync-renamed-reordered.json", "sync-words.json",
)

private fun json(name: String) = JsonValue.parse(FollowTest::class.java.getResource("/follow/$name")!!.readText()) as JsonObject

private fun JsonObject.member(name: String) = members.getValue(name)

// The official packs of a fixture, in the shape the catalog holds them.
private fun officialOf(value: JsonValue): List<OfficialPack> = (value as JsonArray).items.map { item ->
  val fields = item as JsonObject
  OfficialPack((fields.member("catalogId") as JsonString).value, (fields.member("version") as JsonNumber).literal.toInt(),
    decodePacks(JsonArray(listOf(fields.member("pack")))).single(), decodeLists(fields.member("lists")))
}

// A library read as Go's decode reads it before it follows the catalog, as far as Kotlin does: lists saved before packs
// existed are converted (convertLibrary) and lists with packs are separated (separateLevels); the fixtures need no other
// repair. Then it follows official.
private fun follow(lists: JsonValue, packs: JsonValue, official: List<OfficialPack>): Pair<String, String> {
  val (read, readLists) = if (packs == JsonNull) convertLibrary(decodeLists(lists))
  else decodePacks(packs) to separateLevels(decodeLists(lists))
  val (followedPacks, followedLists) = followOfficial(read, readLists, official)
  return encode(followedLists) to encodePacks(followedPacks)
}

class FollowTest {
  @Test fun followsTheFixtures() {
    for (name in fixtures) {
      val fixture = json(name)
      val official = officialOf(fixture.member("official"))
      val library = fixture.member("library") as JsonObject
      val expected = fixture.member("expected") as JsonObject
      val (lists, packs) = follow(library.member("lists"), library.member("packs"), official)
      assertEquals(expected.member("lists"), JsonValue.parse(lists), "$name lists")
      assertEquals(expected.member("packs"), JsonValue.parse(packs), "$name packs")
      checkPacks(decodePacks(JsonValue.parse(packs)), decodeLists(JsonValue.parse(lists)))
      // Reading the result again changes nothing.
      assertEquals(lists to packs, follow(JsonValue.parse(lists), JsonValue.parse(packs), official), "$name again")
    }
  }

  // Go's TestOfficialPacks.
  @Test fun readsOfficialPacks() {
    val official = officialPacks()
    // The same catalog ids, in the same order, as officialPacks() in src/plugins/packs/catalog.ts.
    assertEquals(30, official.size)
    assertEquals("pack-8f3cf116-1f9f-4579-a071-e600094b104d", official[0].catalogId)
    assertEquals("pack-ef974a4b-3c7a-4eeb-9979-6721a0a1c81a", official[1].catalogId)
    for (entry in official) {
      assertTrue(entry.version == 1 && entry.pack.id == entry.catalogId && entry.pack.origin == Origin(entry.catalogId, 1.0) &&
        entry.lists.isNotEmpty(), entry.catalogId)
      for (list in entry.lists) assertTrue(list.packId == entry.catalogId && list.records.isEmpty() && listOriginId(list) == null, list.id)
    }
    // Since v0.85.0 the official lists are the lists of the sparklingstadt account, in its order.
    assertEquals(67, official.sumOf { it.lists.size })
    assertEquals("8f3cf116-1f9f-4579-a071-e600094b104d", official[0].lists[0].id)
    // The group Arch Linuxコマンド is named after the list arch-linux, which comes first and has no group.
    val arch = official.single { entry -> entry.lists.any { it.id == "arch-linux-beginner" } }
    assertEquals("Arch Linuxコマンド（2）" to "プログラミング", arch.pack.name to arch.pack.category)
    assertTrue(arch.lists.any { it.id == "arch-linux-advanced" })
    // The packs and lists together are a library that can be saved.
    checkPacks(official.map { it.pack }, decodeLists(JsonValue.parse(encode(official.flatMap { it.lists }))))
    assertEquals(official, catalog)
  }

  // Go's TestDecodeFollowsTheCatalog: a saved row read by the server follows the real catalog.
  @Test fun readSavedFollowsTheCatalog() {
    val first = catalog[0]
    val packs = """[{"id":"${first.catalogId}","name":"Old","createdAt":"2026-10-01T00:00:00.000Z"},
      {"id":"f","name":"Mine","description":"old","origin":{"catalogId":"${first.catalogId}","version":1,"followed":true},"createdAt":"2026-10-01T00:00:00.000Z"},
      {"id":"own","name":"Own","createdAt":"2026-10-01T00:00:00.000Z"}]"""
    val lists = """[{"id":"old","name":"Old","packId":"${first.catalogId}","createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]},
      {"id":"fl","name":"Old","packId":"f","origin":{"listId":"${first.lists[0].id}"},"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],
        "records":[{"time":1,"date":"2026-10-02T00:00:00.000Z"}]},
      {"id":"o","name":"Own","packId":"own","createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""
    val w = readSaved(lists, "[]", packs)
    assertEquals(listOf("f", "own"), w.packs.map { it.id })
    val followed = w.packs[0]
    assertEquals(Pack("f", "Mine", first.pack.description, first.pack.category, first.pack.tags, Origin(first.catalogId, 1.0, true),
      "2026-10-01T00:00:00.000Z"), followed)
    val mine = w.lists.filter { it.packId == "f" }
    assertEquals(first.lists.size, mine.size)
    assertTrue(mine[0].id == "fl" && mine[0].records.size == 1 && mine[0].name == first.lists[0].name && mine[0].words == first.lists[0].words,
      mine[0].toString())
    for (list in mine.drop(1)) assertEquals("f:" + listOriginId(list), list.id)
    assertEquals("o", w.lists.last().id)
    // A row without packs is not converted, and so not followed.
    assertEquals(decode(lists, "[]"), readSaved(lists, "[]", null))
  }

  // Go's TestDecodeReadsFollowedOrigins.
  @Test fun readsFollowedOrigins() {
    val list = """[{"id":"a","name":"a","packId":"p","createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""
    fun pack(origin: String) = """[{"id":"p","name":"P","createdAt":"x","origin":{"catalogId":"c","version":1$origin}}]"""
    val c = OfficialPack("c", 1, Pack("c", "C", createdAt = "x"), decodeLists(JsonValue.parse(list.replace("\"p\"", "\"c\""))))
    // An origin without followed is a copy from before v0.98.0, which is removed; followed: true is the only other value.
    for (followed in listOf("", ""","followed":null""")) {
      val w = decode(list, "[]", pack(followed))
      assertEquals(emptyList<Pack>() to emptyList<WordList>(), followOfficial(w.packs, w.lists, listOf(c)), followed)
    }
    for (followed in listOf(""","followed":false""", ""","followed":"yes"""", ""","followed":1""")) {
      assertFailsWith<InvalidWorkspaceException>(followed) { decode(list, "[]", pack(followed)) }
    }
    assertEquals(Origin("c", 1.0, true), decode(list, "[]", pack(""","followed":true""")).packs[0].origin)
    // A followed origin is written back with followed.
    assertEquals("""[{"id":"p","name":"P","origin":{"catalogId":"c","version":1,"followed":true},"createdAt":"x"}]""",
      encodePacks(listOf(Pack("p", "P", origin = Origin("c", 1.0, true), createdAt = "x"))))
  }

  // Go's TestDecodeListOrigin: a list origin with a string listId is kept (with the fields Typle does not know in it), and
  // any other is dropped.
  @Test fun readsListOrigins() {
    fun read(origin: String) = decodeLists(JsonValue.parse(
      """[{"id":"a","name":"a",$origin"createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]""")).single()
    assertEquals("x", listOriginId(read(""""origin":{"listId":"x"},""")))
    for (bad in listOf("null", "\"x\"", "{}", """{"listId":1}""", "[]", """{"listId":null}""")) {
      val list = read(""""origin":$bad,""")
      assertNull(listOriginId(list), bad)
      assertEquals("", list.extra, bad)
    }
    assertTrue(""""origin":{"listId":"x","memo":1}""" in encode(listOf(read(""""origin":{"listId":"x","memo":1},"""))))
    assertTrue("origin" !in encode(listOf(read(""))))
  }
}
