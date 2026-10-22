package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.session.newSession
import io.github.sparklingstadt.typle.server.store.ConflictException
import io.github.sparklingstadt.typle.server.store.Saved
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ONE_LIST =
  """{"id":"one","name":"One","createdAt":"2026-09-06","words":[{"display":"a","input":"a","annotation":""}],"records":[]}"""

private fun payload(owner: String, fields: String) = """{"accountId":"$owner",$fields}"""

// The cases of server/internal/web/workspace_test.go.
class WorkspaceRoutesTest {
  private val store = FakeStore()

  private fun <T> serve(config: Config = testConfig(store), block: TestServer.() -> T): T =
    TestServer { typleWeb(config) }.use { it.block() }

  private fun TestServer.get(cookie: String? = sessionCookie(TEST_OWNER), headers: Map<String, String> = emptyMap()) =
    request("GET", "/api/workspace", headers = headers + (cookie?.let { mapOf("Cookie" to it) } ?: emptyMap()))

  private fun TestServer.put(
    body: String?, cookie: String? = sessionCookie(TEST_OWNER),
    headers: Map<String, String> = mapOf("Origin" to TEST_BASE, "Content-Type" to "application/json"),
  ): HttpResponse<String> = request("PUT", "/api/workspace", body, headers + (cookie?.let { mapOf("Cookie" to it) } ?: emptyMap()))

  @Test fun loginRequired() = serve {
    val good = sessionCookie(TEST_OWNER)
    val tampered = good.dropLast(1) + (if (good.endsWith("A")) "B" else "A")
    val other = "typle-session=" + Keys.create("another secret").seal(newSession(TEST_OWNER, "", "", System.currentTimeMillis()))
    val cases = mapOf(
      "no cookie" to null,
      "auth.js cookie" to "authjs.session-token=x",
      "tampered" to tampered,
      "other secret" to other,
      "__Host- over http" to sessionCookie(TEST_OWNER, secure = true),
      "not a GitHub login" to sessionCookie("google:1"),
    )
    for ((name, cookie) in cases) expectError(get(cookie), 401, "loginRequired", name)
  }

  @Test fun secureCookieName() = serve(testConfig(store, base = "https://typle.example")) {
    expectError(get(sessionCookie(TEST_OWNER)), 401, "loginRequired")
    assertEquals(200, get(sessionCookie(TEST_OWNER, secure = true)).statusCode())
  }

  // As Go's r.Cookie: the first cookie of the name, read as it is written.
  @Test fun cookieReading() = serve {
    val good = sessionCookie(TEST_OWNER)
    assertEquals(200, get("a=b; $good; typle-session=x").statusCode())
    expectError(get("typle-session=x; $good"), 401, "loginRequired")
    assertEquals(200, get(good.replace("typle-session=", "typle-session=\"") + "\"").statusCode())
  }

  @Test fun method() = serve {
    for (method in listOf("POST", "DELETE", "PATCH")) {
      val response = request(method, "/api/workspace", headers = mapOf("Cookie" to sessionCookie(TEST_OWNER)))
      expectError(response, 405, "methodNotAllowed", method)
      assertEquals("GET, HEAD, PUT", response.headers().firstValue("Allow").orElse(null))
    }
  }

  @Test fun getEmpty() = serve {
    val response = get()
    assertEquals(200 to """{"lists":[],"devices":[],"revision":0,"accountId":"$TEST_OWNER"}""" + "\n", response.statusCode() to response.body())
    expectJsonHeaders("get", response)
  }

  // The stored jsonb text is answered compact, with HTML characters escaped, as Go's encoder writes a RawMessage.
  @Test fun getSaved() {
    store.saved = Saved("""[{"id": "a", "name": "<b>"}]""", """[{"id": "d", "name": "&"}]""", 4)
    serve {
      val response = get()
      assertEquals(
        """{"lists":[{"id":"a","name":"\u003cb\u003e"}],"devices":[{"id":"d","name":"\u0026"}],"revision":4,"accountId":"$TEST_OWNER"}""" + "\n",
        response.body())
    }
  }

  @Test fun head() = serve {
    val response = request("HEAD", "/api/workspace", headers = mapOf("Cookie" to sessionCookie(TEST_OWNER)))
    assertEquals(200 to "", response.statusCode() to response.body())
    assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null))
    assertEquals(401, request("HEAD", "/api/workspace").statusCode())
  }

  @Test fun getStoreFails() {
    store.failure = IllegalStateException("down")
    serve { expectError(get(), 503, "databaseUnavailable") }
  }

  @Test fun putForbidden() = serve {
    val body = payload(TEST_OWNER, """"revision":0,"lists":[]""")
    val json = "Content-Type" to "application/json"
    val cases = mapOf(
      "other origin" to mapOf("Origin" to "https://evil.example", json),
      "no origin" to mapOf(json),
      "origin path" to mapOf("Origin" to "$TEST_BASE/", json),
      "other port" to mapOf("Origin" to "http://localhost:3001", json),
      "text/plain" to mapOf("Origin" to TEST_BASE, "Content-Type" to "text/plain"),
      "no content type" to mapOf("Origin" to TEST_BASE),
      "other case" to mapOf("Origin" to TEST_BASE, "Content-Type" to "Application/JSON"),
      "json suffix" to mapOf("Origin" to TEST_BASE, "Content-Type" to "application/jsonp"),
    )
    for ((name, headers) in cases) expectError(put(body, headers = headers), 403, "forbiddenRequest", name)
    // The origin and type are checked before the login.
    expectError(put(body, cookie = null, headers = cases.getValue("other origin")), 403, "forbiddenRequest")
    assertEquals(0, store.writes)
    for (type in listOf("application/json; charset=utf-8", "application/json;charset=utf-8", "application/json ; x=y")) {
      assertEquals(200, put(body, headers = mapOf("Origin" to TEST_BASE, "Content-Type" to type)).statusCode(), type)
    }
  }

  @Test fun sameOriginNormalizes() {
    val cases = mapOf(
      "https://Typle.Example" to "https://typle.example",
      "https://typle.example:443/" to "https://typle.example",
      "http://localhost:80" to "http://localhost",
      "http://localhost:3000/path" to "http://localhost:3000",
      "http://[::1]:3000" to "http://[::1]:3000",
    )
    for ((base, origin) in cases) {
      val config = testConfig(store, base = base)
      assertTrue(config.sameOrigin(origin), base)
      assertFalse(config.sameOrigin("https://other.example"), base)
      assertFalse(config.sameOrigin(null), base)
    }
  }

  @Test fun putLoginRequired() = serve {
    expectError(put(payload(TEST_OWNER, """"revision":0"""), cookie = null), 401, "loginRequired")
    assertEquals(0, store.writes)
  }

  @Test fun putTooLarge() = serve {
    val prefix = """{"accountId":"$TEST_OWNER","revision":0,"lists":["""
    val suffix = "]}"
    fun sized(n: Int) = prefix + " ".repeat(n - prefix.length - suffix.length) + suffix
    assertEquals(200, put(sized(1 shl 20)).statusCode())
    expectError(put(sized((1 shl 20) + 1)), 413, "workspaceTooLarge")
  }

  // The login is checked before the size, and the size before the content.
  @Test fun putCheckOrder() = serve {
    val big = payload(TEST_OWNER, """"revision":0,"lists":[""") + " ".repeat(1 shl 20) + "]}"
    expectError(put(big, cookie = null), 401, "loginRequired")
    expectError(put("x" + " ".repeat(1 shl 20)), 413, "workspaceTooLarge")
    expectError(put(big.replace(TEST_OWNER, "github:7")), 413, "workspaceTooLarge")
    assertEquals(0, store.writes)
  }

  @Test fun putEmpty() = serve { expectError(put(null), 400, "emptyRequest") }

  @Test fun putAccountChanged() = serve {
    val bodies = mapOf(
      "other account" to payload("github:7", """"revision":0,"lists":[]"""),
      "no account" to """{"revision":0,"lists":[]}""",
      "null account" to """{"accountId":null,"revision":0}""",
      "number" to """{"accountId":4242,"revision":0}""",
      "invalid rest" to payload("github:7", """"revision":-1,"lists":null"""),
      "legacy" to payload("github:7", """"revision":0,"profileId":"zh""""),
    )
    for ((name, body) in bodies) expectError(put(body), 401, "loginAccountChanged", name)
    assertEquals(0, store.writes)
  }

  @Test fun putInvalid() = serve {
    val bodies = mapOf(
      "negative" to payload(TEST_OWNER, """"revision":-1,"lists":[]"""),
      "fraction" to payload(TEST_OWNER, """"revision":1.5,"lists":[]"""),
      "string" to payload(TEST_OWNER, """"revision":"0","lists":[]"""),
      "missing" to payload(TEST_OWNER, """"lists":[]"""),
      "null" to payload(TEST_OWNER, """"revision":null,"lists":[]"""),
      "overflow" to payload(TEST_OWNER, """"revision":1e400,"lists":[]"""),
      "not safe" to payload(TEST_OWNER, """"revision":9007199254740992,"lists":[]"""),
      "null lists" to payload(TEST_OWNER, """"revision":0,"lists":null"""),
      "null devices" to payload(TEST_OWNER, """"revision":0,"devices":null"""),
      "lists object" to payload(TEST_OWNER, """"revision":0,"lists":{}"""),
      "unreadable list" to payload(TEST_OWNER, """"revision":0,"lists":[{"id":""}]"""),
      "bad device" to payload(TEST_OWNER, """"revision":0,"devices":[{"id":"default","name":"x","layout":""}]"""),
      "array" to "[]",
      "number" to "5",
      "null body" to "null",
      "not JSON" to "lists",
      "truncated" to """{"accountId":"$TEST_OWNER",""",
      "two objects" to payload(TEST_OWNER, """"revision":0""") + payload(TEST_OWNER, """"revision":0"""),
      "trailing" to payload(TEST_OWNER, """"revision":0""") + " x",
      "revision first" to payload(TEST_OWNER, """"revision":-1,"profileId":"zh""""),
      "NUL" to payload(TEST_OWNER, """"revision":0,"lists":[${ONE_LIST.replace("\"One\"", "\"a\\u0000b\"")}]"""),
      "lone surrogate" to payload(TEST_OWNER, """"revision":0,"lists":[${ONE_LIST.replace("\"One\"", "\"a\\ud800\"")}]"""),
    )
    for ((name, body) in bodies) expectError(put(body), 400, "invalidWorkspace", name)
    assertEquals(0 to 0, store.writes to store.reads)
  }

  @Test fun putLegacy() = serve {
    val fields = listOf(
      """"revision":1,"profileId":"p2","lists":[]""",
      """"revision":1,"profiles":[]""",
      """"revision":1,"profiles":null""",
      """"revision":1,"profileId":null""",
      """"revision":1,"profileId":1""",
      """"revision":1,"profileId":"default","profiles":[{"id":"default","name":"メイン"}]""",
      """"revision":1,"profileId":"p2","lists":null""",
    )
    for (f in fields) expectError(put(payload(TEST_OWNER, f)), 409, "conflict", f)
    assertEquals(0, store.writes)
    assertEquals(200, put(payload(TEST_OWNER, """"revision":1,"profileId":"default","lists":[$ONE_LIST]""")).statusCode())
  }

  @Test fun putAnswersAndPassesOn() = serve {
    val device = """{"id":"mac","name":"MacBook","layout":"JIS"}"""
    val list = ONE_LIST.replace(""""records":[]""", """"records":[],"shared":true,"shareId":"x","keep":1""")
    val response = put(payload(TEST_OWNER, """"revision":3,"lists":[$list],"devices":[$device]"""))
    assertEquals(
      """{"lists":[{"id":"one","name":"One","createdAt":"2026-09-06","words":[{"display":"a","input":"a","annotation":""}],"records":[],"keep":1}],""" +
        """"devices":[$device],"revision":4,"accountId":"$TEST_OWNER"}""" + "\n",
      response.body())
    expectJsonHeaders("put", response)
    assertEquals(3L, store.revision)
    assertTrue(store.change!!.hasLists && store.change!!.hasDevices)
    // A part left out stays as saved.
    store.saved = Saved("[]", "[]", 4)
    assertEquals("""{"lists":[],"devices":[],"revision":5,"accountId":"$TEST_OWNER"}""" + "\n",
      put(payload(TEST_OWNER, """"revision":4,"devices":[]""")).body())
    assertFalse(store.change!!.hasLists)
  }

  @Test fun putStoreErrors() {
    val cases = listOf(
      ConflictException() to (409 to "conflict"),
      WorkspaceError("listLimit") to (400 to "listLimit"),
      WorkspaceError("invalidWorkspace") to (400 to "invalidWorkspace"),
      WorkspaceError("workspaceTooLarge") to (503 to "workspaceSaveFailed"),
      IllegalStateException("down") to (503 to "workspaceSaveFailed"),
    )
    for ((failure, want) in cases) {
      store.failure = failure
      serve { expectError(put(payload(TEST_OWNER, """"revision":0,"lists":[]""")), want.first, want.second, failure.toString()) }
    }
  }

  // An app's (iPad, Mac, Android or Windows) token is judged alone: no Origin is needed, and a cookie never stands in for it.
  @Test fun appToken() = serve {
    val body = payload(TEST_OWNER, """"revision":0,"lists":[]""")
    val token = appToken(TEST_OWNER)
    assertEquals(200, put(body, cookie = null, headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")).statusCode())
    assertEquals(200, get(null, headers = mapOf("Authorization" to "Bearer $token")).statusCode())
    val cookieValue = sessionCookie(TEST_OWNER).substringAfter('=')
    for (authorization in listOf("Bearer $cookieValue", "Basic x", "Bearer a b", "Bearer ", "bearer $token")) {
      expectError(get(sessionCookie(TEST_OWNER), headers = mapOf("Authorization" to authorization)), 401, "loginRequired", authorization)
    }
  }
}
