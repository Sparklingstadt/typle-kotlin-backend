package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The cases of server/internal/workspace/interests_test.go, which are the inputs of decodeInterests in
// tests/interests.test.cjs.
class InterestsTest {
  private val many = List(MAX_INTERESTS + 1) { "x/$it" }

  @Test fun decodesInterests() {
    val accepted = mapOf<String?, List<String>>(
      null to emptyList(),
      "" to emptyList(),
      "null" to emptyList(),
      "[]" to emptyList(),
      """["language","language/zh"]""" to listOf("language", "language/zh"),
      """["x/unknown","programming"]""" to listOf("x/unknown", "programming"),
      """["${"a".repeat(MAX_INTEREST_LENGTH)}"]""" to listOf("a".repeat(MAX_INTEREST_LENGTH)),
      """["${"𩸽".repeat(MAX_INTEREST_LENGTH / 2)}"]""" to listOf("𩸽".repeat(MAX_INTEREST_LENGTH / 2)),
      encodeInterests(many.take(MAX_INTERESTS)) to many.take(MAX_INTERESTS),
    )
    for ((data, want) in accepted) assertEquals(want, decodeInterests(data), data)
  }

  @Test fun refusesInterests() {
    val refused = mapOf(
      "not a list" to "{}",
      "a string" to "\"language\"",
      "not a string" to """["a",1]""",
      "null item" to """["a",null]""",
      "too many" to encodeInterests(many),
      "too long" to """["${"a".repeat(MAX_INTEREST_LENGTH + 1)}"]""",
      "too long UTF16" to """["${"𩸽".repeat(MAX_INTEREST_LENGTH / 2 + 1)}"]""",
      "duplicate" to """["language","language"]""",
      "empty" to """[""]""",
      "not JSON" to "[",
    )
    for ((name, data) in refused) assertFailsWith<InvalidWorkspaceException>(name) { decodeInterests(data) }
  }

  @Test fun encodesInterests() {
    assertEquals("[]", encodeInterests(emptyList()))
    assertEquals("""["language","language/zh","<&>"]""", encodeInterests(listOf("language", "language/zh", "<&>")))
  }

  // The default interests are the app's DEFAULT_INTERESTS, which tests/fixtures/default-interests.json of typle-r holds
  // (src/test/resources/default-interests.json is a copy of it).
  @Test fun defaultInterests() {
    val text = InterestsTest::class.java.getResource("/default-interests.json")!!.readText()
    val want = (JsonValue.parse(text) as JsonArray).items.map { (it as JsonString).value }
    assertEquals(want, DEFAULT_INTERESTS)
    assertEquals(23, DEFAULT_INTERESTS.size)
    checkInterests(DEFAULT_INTERESTS)
  }
}
