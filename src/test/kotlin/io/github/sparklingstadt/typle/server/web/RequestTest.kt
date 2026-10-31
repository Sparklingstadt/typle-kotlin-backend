package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonValue
import io.github.sparklingstadt.typle.server.store.Change
import io.github.sparklingstadt.typle.server.workspace.Device
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val WORDS = """[{"id":"l","name":"n","createdAt":"c","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""

class RequestTest {
  private fun fields(json: String) = JsonValue.parse(json) as JsonObject

  // The cases of TestUnstorable in server/internal/web/request_test.go.
  @Test fun unstorable() {
    val cases = mapOf(
      """["a"]""" to false,
      """["\u0000"]""" to true,
      """{"a\u0000":1}""" to true,
      """["\\u0000"]""" to false,
      """["\\\u0000"]""" to true,
      """["\ud800"]""" to true,
      """["\uD800x"]""" to true,
      """["\udfff"]""" to true,
      """["\ud800A"]""" to true,
      """["\ud800\ud800"]""" to true,
      """{"\udc00":1}""" to true,
      """["𠮷"]""" to false,
      """["\ud842\udfb7"]""" to false,
      """["𠮷", "\\ud800"]""" to false,
      """["あé"]""" to false,
      """["𠮷\ud800"]""" to true,
      """[{"x":{"y":["ok","\ud83d"]}}]""" to true,
    )
    for ((raw, want) in cases) assertEquals(want, unstorable(JsonValue.parse(raw)), raw)
  }

  // As deep as the parser lets a body nest, on a thread with a small stack, which recursion would overflow.
  @Test fun unstorableDeeplyNested() {
    val results = arrayOfNulls<Boolean>(4)
    val thread = Thread(null, {
      results[0] = unstorable(JsonValue.parse("[".repeat(10000) + "]".repeat(10000)))
      results[1] = unstorable(JsonValue.parse("[".repeat(9999) + "\"\\u0000\"" + "]".repeat(9999)))
      results[2] = unstorable(JsonValue.parse("{\"a\":".repeat(9999) + "\"\\ud800\"" + "}".repeat(9999)))
      results[3] = unstorable(JsonValue.parse("{\"a\":".repeat(9999) + "\"ok\"" + "}".repeat(9999)))
    }, "small-stack", 128L * 1024)
    thread.start()
    thread.join()
    assertEquals(listOf<Boolean?>(false, true, true, false), results.toList())
  }

  @Test fun safeInteger() {
    for ((raw, want) in mapOf("0" to 0L, "1" to 1L, "1.0" to 1L, "1e2" to 100L, "-0" to 0L, "9007199254740991" to 9007199254740991L)) {
      assertEquals(want, safeInteger(JsonValue.parse(raw)), raw)
    }
    for (raw in listOf("-1", "1.5", "9007199254740992", "1e400", "\"1\"", "true", "null")) {
      assertNull(safeInteger(JsonValue.parse(raw)), raw)
    }
    assertNull(safeInteger(null))
  }

  @Test fun parseObject() {
    assertEquals(fields("""{"a":1}"""), parseObject("""{"a":1}"""))
    for (body in listOf("[]", "null", "1", "\"x\"", "{", "")) assertFailsWith<InvalidRequestException>(body) { parseObject(body) }
  }

  @Test fun decodesSaves() {
    assertEquals(3L to Change(emptyList(), emptyList(), hasLists = false, hasDevices = false), decodeFields(fields("""{"revision":3}""")))
    val lists = decodeFields(fields("""{"revision":1,"lists":$WORDS}""")).second
    assertEquals(true, lists.hasLists)
    assertEquals(false, lists.hasDevices)
    assertEquals("l", lists.lists.single().id)
    // Lists sent without packs are each put in a pack of their own (typle-r v2.0.0).
    assertEquals(true, lists.hasPacks)
    assertEquals(listOf("pack-l"), lists.packs.map { it.id })
    assertEquals("pack-l", lists.lists.single().packId)
    val devices = decodeFields(fields("""{"revision":1,"devices":[{"id":"d","name":" n ","layout":""}]}""")).second
    assertEquals(Change(emptyList(), listOf(Device("d", "n", "")), hasLists = false, hasDevices = true), devices)
    // Interests may be all a save sets; null is none.
    assertEquals(2L to Change(emptyList(), emptyList(), hasLists = false, hasDevices = false, interests = listOf("language"), hasInterests = true),
      decodeFields(fields("""{"revision":2,"interests":["language"]}""")))
    assertEquals(Change(emptyList(), emptyList(), hasLists = false, hasDevices = false, hasInterests = true),
      decodeFields(fields("""{"revision":2,"interests":null}""")).second)
    val both = decodeFields(fields("""{"revision":1,"lists":$WORDS,"interests":["a"]}""")).second
    assertEquals(listOf("a") to true, both.interests to both.hasInterests)
  }

  // The revision is judged before the legacy profile, and that before the lists, as in validateWorkspaceRequest.
  @Test fun refusesSaves() {
    val invalid = listOf(
      """{}""", """{"revision":-1}""", """{"revision":"1"}""", """{"revision":1.5,"profiles":[]}""",
      """{"revision":1,"lists":null}""", """{"revision":1,"devices":null}""", """{"revision":1,"lists":{}}""",
      """{"revision":1,"lists":[{"id":""}]}""", """{"revision":1,"lists":["\u0000"]}""",
      """{"revision":1,"devices":[{"id":"d","name":"\ud800","layout":""}]}""",
    )
    for (body in invalid) assertFailsWith<InvalidRequestException>(body) { decodeFields(fields(body)) }
    val legacy = listOf(
      """{"revision":1,"profileId":"other"}""", """{"revision":1,"profileId":null}""", """{"revision":1,"profileId":1}""",
      """{"revision":1,"profiles":[]}""", """{"revision":1,"profileId":"other","lists":null}""", """{"revision":0,"profileId":"default"}""",
    )
    // Saves of apps with profiles are no longer taken (typle-r v2.0.0).
    for (body in legacy) assertFailsWith<InvalidRequestException>(body) { decodeFields(fields(body)) }
  }

  // Only the lists and devices are looked at for what jsonb cannot store.
  @Test fun otherFieldsAreNotJudgedForStorage() {
    assertEquals(1L, decodeFields(fields("""{"revision":1,"note":"\u0000","lists":$WORDS}""")).first)
  }

  @Test fun readLimited() {
    assertEquals(4, readLimited(ByteArrayInputStream(ByteArray(4)), 4).size)
    assertFailsWith<TooLargeException> { readLimited(ByteArrayInputStream(ByteArray(5)), 4) }
  }
}
