package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.store.PgStore
import io.github.sparklingstadt.typle.server.store.Share
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.decode
import io.github.sparklingstadt.typle.server.workspace.encodePackFile
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

private val sharedAt = Instant.parse("2026-10-26T09:30:15.123456789Z")

private const val A_SHARED_ID = "0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d"

// The cases of server/internal/web/shares_test.go. Go's tests also check which operation a failure is reported as;
// here failures are only logged.
class ShareRoutesTest {
  private val store = FakeStore()

  private fun <T> serve(config: Config = testConfig(store), block: TestServer.() -> T): T =
    TestServer { typleWeb(config) }.use { it.block() }

  /**
   * A signed-in request to /api/shares from the app's own origin, with a JSON body when there is one; change edits the
   * headers.
   */
  private fun TestServer.share(
    method: String, target: String, body: String? = null, owner: String? = TEST_OWNER,
    change: (MutableMap<String, String>) -> Unit = {},
  ): HttpResponse<String> {
    val headers = HashMap<String, String>()
    if (method != "GET" && method != "HEAD") headers["Origin"] = TEST_BASE
    if (body != null) headers["Content-Type"] = "application/json"
    if (owner != null) headers["Cookie"] = sessionCookie(owner)
    change(headers)
    return request(method, target, body, headers)
  }

  @Test fun loginRequired() = serve {
    expectError(share("GET", "/api/shares", owner = null), 401, "loginRequired")
    expectError(share("PUT", "/api/shares", """{"packId":"p"}""", owner = null), 401, "loginRequired")
    expectError(share("DELETE", "/api/shares?pack=p", owner = null), 401, "loginRequired")
    assertEquals(emptyList(), store.calls)
  }

  // A cookie's request must come from the app's own origin, with a JSON body, as PUT /api/workspace; an app's token
  // needs no Origin.
  @Test fun forbidden() = serve {
    store.put = Share("i", "p", "", sharedAt)
    val body = """{"packId":"p"}"""
    expectError(share("PUT", "/api/shares", body) { it.remove("Origin") }, 403, "forbiddenRequest", "no origin")
    expectError(share("PUT", "/api/shares", body) { it["Origin"] = "https://evil.example" }, 403, "forbiddenRequest", "other origin")
    expectError(share("PUT", "/api/shares", body) { it["Content-Type"] = "text/plain" }, 403, "forbiddenRequest", "text/plain")
    expectError(share("DELETE", "/api/shares?pack=p") { it.remove("Origin") }, 403, "forbiddenRequest", "delete without origin")
    expectError(share("DELETE", "/api/shares?pack=p") { it["Origin"] = "https://evil.example" }, 403, "forbiddenRequest", "delete other origin")
    assertEquals(emptyList(), store.calls)

    val token: (MutableMap<String, String>) -> Unit = {
      it.remove("Origin")
      it["Authorization"] = "Bearer " + appToken(TEST_OWNER)
    }
    assertEquals(200, share("PUT", "/api/shares", body, owner = null, change = token).statusCode(), "app token")
    assertEquals(204, share("DELETE", "/api/shares?pack=p", owner = null, change = token).statusCode(), "app token delete")
  }

  // A cookie's DELETE from another origin is refused before the store is asked.
  @Test fun deleteWrongOrigin() = serve {
    expectError(share("DELETE", "/api/shares?pack=p") { it["Origin"] = "https://evil.example" }, 403, "forbiddenRequest")
    assertEquals(emptyList(), store.calls)
  }

  @Test fun putInvalid() = serve {
    store.put = Share("i", "p", "", sharedAt)
    for (body in listOf("[]", "null", "\"p\"", "{}", """{"packId":""}""", """{"packId":1}""", """{"packId":null}""",
      """{"packId":"p","more":1}""", "{", """{"packId":"${"p".repeat(5000)}"}""")) {
      expectError(share("PUT", "/api/shares", body), 400, "invalidWorkspace", body.take(30))
    }
    expectError(share("PUT", "/api/shares") { it["Content-Type"] = "application/json" }, 400, "invalidWorkspace", "empty")
    expectError(share("DELETE", "/api/shares"), 400, "invalidWorkspace", "no pack")
    expectError(share("DELETE", "/api/shares?pack="), 400, "invalidWorkspace", "empty pack")
    assertEquals(emptyList(), store.calls)
  }

  @Test fun put() = serve {
    store.put = Share(A_SHARED_ID, "pk", "Pack K", sharedAt)
    val response = share("PUT", "/api/shares", """{"packId":"pk"}""")
    assertEquals(200 to """{"id":"$A_SHARED_ID","packId":"pk","name":"Pack K","updatedAt":"2026-10-26T09:30:15.123Z"}""" + "\n",
      response.statusCode() to response.body())
    expectJsonHeaders("put", response)
    expectSecurityHeaders("put", response)
    assertEquals(TEST_OWNER to "pk", store.owner to store.packId)
  }

  @Test fun putRefused() {
    val cases = listOf(
      Triple(WorkspaceError("packNotFound"), 404, "packNotFound"),
      Triple(WorkspaceError("invalidWorkspace"), 400, "invalidWorkspace"),
      Triple(IllegalStateException("down"), 503, "workspaceSaveFailed"),
    )
    for ((failure, status, key) in cases) {
      store.putFailure = failure
      serve { expectError(share("PUT", "/api/shares", """{"packId":"pk"}"""), status, key, key) }
    }
  }

  @Test fun delete() {
    serve {
      val response = share("DELETE", "/api/shares?pack=p%20k")
      assertEquals(204 to "", response.statusCode() to response.body())
      assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null))
      expectSecurityHeaders("delete", response)
      assertEquals(TEST_OWNER to "p k", store.owner to store.packId)
    }
    store.failure = IllegalStateException("down")
    serve { expectError(share("DELETE", "/api/shares?pack=p"), 503, "workspaceSaveFailed") }
  }

  @Test fun list() {
    serve {
      val response = share("GET", "/api/shares")
      assertEquals(200 to "{\"shares\":[]}\n", response.statusCode() to response.body())
      expectJsonHeaders("empty", response)
      store.shares = listOf(Share("b", "two", "Two", sharedAt), Share("a", "<one>", "<One>", sharedAt.minusSeconds(3600)))
      val two = share("GET", "/api/shares")
      assertEquals(
        """{"shares":[{"id":"b","packId":"two","name":"Two","updatedAt":"2026-10-26T09:30:15.123Z"},""" +
          """{"id":"a","packId":"\u003cone\u003e","name":"\u003cOne\u003e","updatedAt":"2026-10-26T08:30:15.123Z"}]}""" + "\n",
        two.body())
      assertEquals(TEST_OWNER, store.owner)
      val head = share("HEAD", "/api/shares")
      assertEquals(200 to "", head.statusCode() to head.body())
    }
    store.failure = IllegalStateException("down")
    serve { expectError(share("GET", "/api/shares"), 503, "databaseUnavailable") }
  }

  @Test fun method() = serve {
    for (method in listOf("POST", "PATCH")) {
      val response = share(method, "/api/shares")
      expectError(response, 405, "methodNotAllowed", method)
      assertEquals("GET, HEAD, PUT, DELETE", response.headers().firstValue("Allow").orElse(null), method)
    }
  }

  @Test fun sharedNotFound() = serve {
    for (target in listOf("/api/shared", "/api/shared?id=", "/api/shared?id=nope", "/api/shared?id=$A_SHARED_ID")) {
      val response = request("GET", target)
      expectError(response, 404, "sharedPackNotFound", target)
      assertEquals("noindex", response.headers().firstValue("X-Robots-Tag").orElse(null), target)
    }
    // The store tells what is not a link (an id that is not a UUID included); the handler passes the id as it came.
    assertEquals(A_SHARED_ID, store.sharedId)
  }

  // Anyone with the link reads the snapshot itself, without a cookie, and nothing keeps it.
  @Test fun shared() = serve {
    val snapshot = """{"format":"typle-pack","version":1,"pack":{"name":"<P>"},"lists":[]}"""
    store.shared = snapshot
    val response = request("GET", "/api/shared?id=$A_SHARED_ID")
    assertEquals(200 to snapshot, response.statusCode() to response.body())
    val headers = response.headers()
    assertEquals("application/json; charset=utf-8", headers.firstValue("Content-Type").orElse(null))
    assertEquals(listOf("no-store"), headers.allValues("Cache-Control"))
    assertFalse(headers.firstValue("Vary").isPresent, "Vary")
    expectSecurityHeaders("shared", response)
    assertEquals("noindex", headers.firstValue("X-Robots-Tag").orElse(null), "X-Robots-Tag")
    assertEquals(A_SHARED_ID, store.sharedId)
  }

  @Test fun sharedHeadAndMethod() = serve {
    store.shared = """{"format":"typle-pack"}"""
    val head = request("HEAD", "/api/shared?id=$A_SHARED_ID")
    assertEquals(200 to "", head.statusCode() to head.body())
    assertEquals("no-store", head.headers().firstValue("Cache-Control").orElse(null))
    val post = request("POST", "/api/shared?id=$A_SHARED_ID")
    expectError(post, 405, "methodNotAllowed")
    assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(null))
    assertEquals("noindex", post.headers().firstValue("X-Robots-Tag").orElse(null), "POST")
  }

  @Test fun sharedDown() {
    store.failure = IllegalStateException("down")
    serve {
      val down = request("GET", "/api/shared?id=$A_SHARED_ID")
      expectError(down, 503, "databaseUnavailable")
      assertEquals("noindex", down.headers().firstValue("X-Robots-Tag").orElse(null), "503")
    }
  }

  // With a real database (TYPLE_TEST_DATABASE_URL, skipped without it): a saved pack is shared, listed, read by anyone
  // without the owner and unshared; a save that removes a shared pack ends its link.
  @Test fun realDatabase() {
    val connection = System.getenv("TYPLE_TEST_DATABASE_URL").orEmpty()
    assumeTrue(connection.isNotEmpty(), "TYPLE_TEST_DATABASE_URL is not set")
    val owner = "github:8" + Random.nextLong(100_000_000_000L, 999_999_999_999L)
    val cookie = sessionCookie(owner)
    val list = """{"id":"one","name":"One","packId":"p","createdAt":"2026-09-06","words":[{"display":"<a>","input":"a","annotation":""}],"records":[]}"""
    val pack = """{"id":"p","name":"P","category":"C","createdAt":"2026-09-06"}"""
    PgStore.open(connection).use { real ->
      serve(testConfig(real)) {
        fun save(revision: Int, fields: String) = request("PUT", "/api/workspace", """{"accountId":"$owner","revision":$revision,$fields}""",
          mapOf("Origin" to TEST_BASE, "Content-Type" to "application/json", "Cookie" to cookie))

        // An account that has saved nothing has no packs to share here (see the README).
        expectError(share("PUT", "/api/shares", """{"packId":"p"}""", owner), 404, "packNotFound", "no row")
        assertEquals(200, save(0, """"lists":[$list],"packs":[$pack]""").statusCode())
        expectError(share("PUT", "/api/shares", """{"packId":"nope"}""", owner), 404, "packNotFound")
        val put = share("PUT", "/api/shares", """{"packId":"p"}""", owner)
        assertEquals(200, put.statusCode(), put.body())
        val id = put.body().substringAfter("\"id\":\"").substringBefore('"')
        assertTrue(put.body().startsWith("""{"id":"$id","packId":"p","name":"P","updatedAt":""""), put.body())
        val again = share("PUT", "/api/shares", """{"packId":"p"}""", owner)
        assertEquals(id, again.body().substringAfter("\"id\":\"").substringBefore('"'), "same link")
        assertTrue(id in share("GET", "/api/shares", owner = owner).body())

        val read = request("GET", "/api/shared?id=$id")
        assertEquals(200 to encodePackFile(decode("[$list]", "[]", "[$pack]"), "p"), read.statusCode() to read.body())
        assertFalse(owner in read.body())
        assertEquals(204, share("DELETE", "/api/shares?pack=p", owner = owner).statusCode())
        expectError(request("GET", "/api/shared?id=$id"), 404, "sharedPackNotFound")

        // A save with packs that no longer has the pack deletes its link; a save of the lists alone does not.
        val shared = share("PUT", "/api/shares", """{"packId":"p"}""", owner).body().substringAfter("\"id\":\"").substringBefore('"')
        assertEquals(200, save(1, """"lists":[$list]""").statusCode())
        assertEquals(200, request("GET", "/api/shared?id=$shared").statusCode(), "after a lists-only save")
        val other = list.replace("\"p\"", "\"q\"")
        assertEquals(200, save(2, """"lists":[$other],"packs":[${pack.replace("\"p\"", "\"q\"")}]""").statusCode())
        expectError(request("GET", "/api/shared?id=$shared"), 404, "sharedPackNotFound", "after the pack is removed")
        assertEquals("{\"shares\":[]}\n", share("GET", "/api/shares", owner = owner).body())
      }
    }
  }

  // With a real database: lists sent alone (an app that does not know packs) may overlap and are saved as sent, and
  // /api/workspace answers them so; a link to their pack shares them separated, as Go reads the row (Go's
  // TestPutLevelsWithoutPacksRealStore). Sent with their packs, they are refused.
  @Test fun realDatabaseSeparatesLevels() {
    val connection = System.getenv("TYPLE_TEST_DATABASE_URL").orEmpty()
    assumeTrue(connection.isNotEmpty(), "TYPLE_TEST_DATABASE_URL is not set")
    val owner = "github:8" + Random.nextLong(100_000_000_000L, 999_999_999_999L)
    val cookie = sessionCookie(owner)
    fun list(id: String, levels: String) =
      """{"id":"$id","name":"$id","packId":"p","levels":$levels,"createdAt":"2026-09-06","words":[{"display":"a","input":"a","annotation":""}],"records":[]}"""
    val pack = """{"id":"p","name":"P","createdAt":"2026-09-06"}"""
    PgStore.open(connection).use { real ->
      serve(testConfig(real)) {
        val headers = mapOf("Origin" to TEST_BASE, "Content-Type" to "application/json", "Cookie" to cookie)
        fun save(revision: Int, fields: String) =
          request("PUT", "/api/workspace", """{"accountId":"$owner","revision":$revision,$fields}""", headers)

        assertEquals(200, save(0, """"lists":[${list("a", "[\"初級\"]")}],"packs":[$pack]""").statusCode())
        val overlapping = """"lists":[${list("a", "[\"初級\"]")},${list("b", "[\"初級\",\"A\"]")}]"""
        expectError(save(1, "$overlapping,\"packs\":[$pack]"), 400, "levelsConflict")
        assertEquals(200, save(1, overlapping).statusCode())
        val workspace = request("GET", "/api/workspace", headers = mapOf("Cookie" to cookie)).body()
        assertTrue("""["初級","A"]""" in workspace && "初級（2）" !in workspace, workspace)

        val put = share("PUT", "/api/shares", """{"packId":"p"}""", owner)
        assertEquals(200, put.statusCode(), put.body())
        val read = request("GET", "/api/shared?id=" + put.body().substringAfter("\"id\":\"").substringBefore('"'))
        assertEquals(200, read.statusCode())
        assertTrue("\"初級（2）\",\n" in read.body(), read.body())
      }
    }
  }
}
