package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The fixtures are copies of typle-r's tests/fixtures/pack-file, which the app (tests/packFile.test.cjs) and Go
// (server/internal/workspace/packfile_test.go) check against: the three write the same bytes.
private class Fixture(val lists: String, val packs: String, val packId: String, val file: String)

private fun fixture(name: String): Fixture {
  val text = PackFileTest::class.java.getResource("/pack-file/$name")!!.readText()
  val top = JsonValue.parse(text) as JsonObject
  val library = top.members.getValue("library") as JsonObject
  return Fixture(library.members.getValue("lists").toJson(), library.members.getValue("packs").toJson(),
    (top.members.getValue("packId") as JsonString).value, (top.members.getValue("file") as JsonString).value)
}

private val fixtures = listOf("basic.json", "full.json")

// value with the members of every object in reverse order, as jsonb may store them in another order than written.
private fun reversed(value: JsonValue): JsonValue = when (value) {
  is JsonObject -> JsonObject(LinkedHashMap<String, JsonValue>().apply {
    for ((name, member) in value.members.entries.reversed()) put(name, reversed(member))
  })
  is JsonArray -> JsonArray(value.items.map(::reversed))
  else -> value
}

// The cases of Go's TestEncodePackFileFixtures and TestPackFileEncodeAgain.
class PackFileTest {
  @Test fun encodesTheFixtures() {
    for (name in fixtures) {
      val f = fixture(name)
      assertEquals(f.file, encodePackFile(decode(f.lists, "[]", f.packs), f.packId), name)
    }
  }

  @Test fun missingPack() {
    val f = fixture("basic.json")
    val error = assertFailsWith<WorkspaceError> { encodePackFile(decode(f.lists, "[]", f.packs), "nope") }
    assertEquals("packNotFound", error.key)
    // Lists saved before packs existed have no packs here.
    assertEquals("packNotFound", assertFailsWith<WorkspaceError> { encodePackFile(decode(f.lists, "[]"), f.packId) }.key)
  }

  // A file read back is written again byte for byte, whatever its spacing and the order of its keys.
  @Test fun encodesAgain() {
    for (name in fixtures) {
      val f = fixture(name)
      val compact = JsonValue.parse(f.file).toJson()
      assertEquals(f.file, encodePackFileAgain(compact), "$name compact")
      assertEquals(f.file, encodePackFileAgain(reversed(JsonValue.parse(f.file)).toJson()), "$name reordered")
    }
    // Fields Typle does not know are dropped; null optional fields read as left out.
    val extra = """{"lists":[{"words":[{"x":1,"annotation":"","input":"a","display":"a"}],"levels":null,"name":"L","y":2}],""" +
      """"pack":{"tags":null,"name":"P","description":null,"z":3},"version":1.0,"format":"typle-pack","w":4}"""
    assertEquals(
      "{\n  \"format\": \"typle-pack\",\n  \"version\": 1,\n  \"pack\": {\n    \"name\": \"P\"\n  },\n  \"lists\": [\n    {\n" +
        "      \"name\": \"L\",\n      \"words\": [\n        {\n          \"display\": \"a\",\n          \"input\": \"a\",\n" +
        "          \"annotation\": \"\"\n        }\n      ]\n    }\n  ]\n}\n",
      encodePackFileAgain(extra))
  }

  @Test fun unreadableAgain() {
    for (data in listOf("[]", "{}", """{"format":"typle-pack","version":2,"pack":{"name":"P"},"lists":[]}""",
      """{"format":"typle-pack","version":1,"pack":{"name":"P"},"lists":[]}""",
      """{"format":"typle-pack","version":1,"pack":{},"lists":[{"name":"L","words":[]}]}""",
      """{"format":"typle-pack","version":1,"pack":{"name":"P","tags":[null]},"lists":[{"name":"L","words":[]}]}""")) {
      assertFailsWith<InvalidWorkspaceException>(data) { encodePackFileAgain(data) }
    }
  }

  // Strings are escaped as JSON.stringify escapes them: < > & and U+2028 as themselves, control characters escaped.
  @Test fun escapesAsJavaScript() {
    val lists = """[{"id":"l","name":"<&>\u2028\u2029","packId":"p","createdAt":"x",""" +
      """"words":[{"display":"\"\\/\b\f\n\r\t\u0001","input":"é😀","annotation":""}],"records":[]}]"""
    val file = encodePackFile(decode(lists, "[]", """[{"id":"p","name":"P","createdAt":"x"}]"""), "p")
    assertEquals(
      "      \"name\": \"<&>\u2028\u2029\",\n" +
        "      \"words\": [\n        {\n          \"display\": \"\\\"\\\\/\\b\\f\\n\\r\\t\\u0001\",\n" +
        "          \"input\": \"é😀\",\n",
      file.substringAfter("    {\n").substringBefore("          \"annotation\""))
  }
}
