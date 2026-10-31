package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.store.Saved
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RECORD_LIST =
  """[{"id":"a","name":"A","packId":"p","createdAt":"2026-10-01","words":[{"display":"a","input":"a","annotation":""}],"records":[]}]"""

private const val RECORD_PACKS = """[{"id":"p","name":"P","createdAt":"2026-10-01"}]"""

// POST and DELETE /api/workspace/records (typle-r v1.2.0, v1.4.0), as Go's records_test.go.
class RecordRoutesTest {
  private val store = FakeStore(saved = Saved(RECORD_LIST, "[]", RECORD_PACKS, 3))

  private fun <T> serve(block: TestServer.() -> T): T = TestServer { typleWeb(testConfig(store)) }.use { it.block() }

  private fun TestServer.send(method: String, body: String, headers: Map<String, String> = mapOf(
    "Origin" to TEST_BASE, "Content-Type" to "application/json", "Cookie" to sessionCookie(TEST_OWNER),
  )) = request(method, "/api/workspace/records", body, headers)

  private val record = """{"id":"r1","time":2.5,"date":"2026-10-02T10:00:00.000Z","mistakes":0,"accuracy":100,"device":{"id":"d","name":"Mac","layout":"JIS"}}"""

  @Test fun addsResendsAndDeletes() = serve {
    val added = send("POST", """{"accountId":"$TEST_OWNER","listId":"a","record":$record}""")
    assertEquals(201, added.statusCode(), added.body())
    assertTrue(added.body().startsWith("{\"lists\":") && added.body().contains(",\"revision\":4,\"accountId\":\"$TEST_OWNER\",\"record\":{\"id\":\"r1\""), added.body())
    assertTrue("\"wordCount\":1,\"device\":{\"id\":\"d\",\"name\":\"Mac\",\"layout\":\"JIS\"}}}" in added.body(), added.body())
    expectJsonHeaders("add", added)
    // Sent again, the record is not added again: 200 with the saved record, and nothing written.
    val again = send("POST", """{"accountId":"$TEST_OWNER","listId":"a","record":${record.replace("2.5", "9")}}""")
    assertEquals(200, again.statusCode())
    assertTrue("\"revision\":4," in again.body() && "\"time\":2.5" in again.body(), again.body())
    val deleted = send("DELETE", """{"accountId":"$TEST_OWNER","listId":"a","recordId":"r1"}""")
    assertEquals(200, deleted.statusCode())
    assertTrue("\"records\":[]" in deleted.body() && "\"record\"" !in deleted.body(), deleted.body())
    // Already gone is what was asked for.
    for (body in listOf("""{"accountId":"$TEST_OWNER","listId":"a","recordId":"r1"}""", """{"accountId":"$TEST_OWNER","listId":"gone","recordId":"r1"}""")) {
      assertEquals(200, send("DELETE", body).statusCode(), body)
    }
  }

  @Test fun refusesRequests() = serve {
    fun add(fields: String) = send("POST", """{"accountId":"$TEST_OWNER",$fields}""")
    for (fields in listOf(
      """"listId":"a"""", """"listId":"","record":$record""", """"listId":"a","record":${record.replace("\"id\":\"r1\",", "")}""",
      """"listId":"a","record":${record.replace("}}", "},\"extra\":1}")}""", """"listId":"a","record":$record,"more":1""",
      """"listId":"a","record":${record.replace("\"time\":2.5", "\"time\":\"2.5\"")}""",
      """"listId":"a","record":${record.replace("\"mistakes\":0", "\"mistakes\":1.5")}""",
      """"listId":"a","record":${record.replace("\"id\":\"r1\"", "\"id\":\"a b\"")}""",
    )) expectError(add(fields), 400, "invalidRecord", fields)
    expectError(add(""""listId":"gone","record":$record"""), 404, "recordListDeleted")
    expectError(add(""""listId":"a","record":${record.replace(",\"device\":{\"id\":\"d\",\"name\":\"Mac\",\"layout\":\"JIS\"}", ",\"deviceId\":\"none\"")}"""),
      400, "deviceNotFound")
    expectError(send("POST", "[]"), 400, "invalidRecord")
    expectError(send("POST", """{"accountId":"github:1","listId":"a","record":$record}"""), 401, "loginAccountChanged")
    expectError(send("DELETE", """{"accountId":"$TEST_OWNER","listId":"a"}"""), 400, "invalidRecord")
    expectError(send("POST", "{}", headers = mapOf("Content-Type" to "application/json", "Cookie" to sessionCookie(TEST_OWNER))), 403, "forbiddenRequest")
    expectError(send("POST", "{}", headers = mapOf("Origin" to TEST_BASE, "Content-Type" to "application/json")), 401, "loginRequired")
    expectError(request("GET", "/api/workspace/records"), 405, "methodNotAllowed")
    // An app's token needs no Origin.
    val app = send("POST", """{"accountId":"$TEST_OWNER","listId":"a","record":$record}""",
      mapOf("Authorization" to "Bearer " + appToken(TEST_OWNER), "Content-Type" to "application/json"))
    assertEquals(201, app.statusCode(), app.body())
  }

  @Test fun storeErrors() = serve {
    store.failure = WorkspaceError("workspaceTooLarge")
    expectError(send("POST", """{"accountId":"$TEST_OWNER","listId":"a","record":$record}"""), 413, "workspaceTooLarge")
    store.failure = IllegalStateException("down")
    expectError(send("DELETE", """{"accountId":"$TEST_OWNER","listId":"a","recordId":"r1"}"""), 503, "workspaceSaveFailed")
  }
}
