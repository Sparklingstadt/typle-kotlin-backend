package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.session.newSession
import io.github.sparklingstadt.typle.server.store.ConflictException
import io.github.sparklingstadt.typle.server.store.PgStore
import io.github.sparklingstadt.typle.server.store.Saved
import io.github.sparklingstadt.typle.server.workspace.DEFAULT_INTERESTS
import io.github.sparklingstadt.typle.server.workspace.MAX_GROUP
import io.github.sparklingstadt.typle.server.workspace.Origin
import io.github.sparklingstadt.typle.server.workspace.catalog
import io.github.sparklingstadt.typle.server.workspace.MAX_INTERESTS
import io.github.sparklingstadt.typle.server.workspace.MAX_INTEREST_LENGTH
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.encodeInterests
import io.github.sparklingstadt.typle.server.workspace.levelsOf
import java.net.http.HttpResponse
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

private const val ONE_LIST =
  """{"id":"one","name":"One","createdAt":"2026-09-06","words":[{"display":"a","input":"a","annotation":""}],"records":[]}"""

private fun payload(owner: String, fields: String) = """{"accountId":"$owner",$fields}"""

private const val A_PACK = """{"id":"p","name":"P","category":"C","createdAt":"2026-09-06"}"""

private const val PACKED_ONE =
  """{"id":"one","name":"One","packId":"p","createdAt":"2026-09-06","words":[{"display":"a","input":"a","annotation":""}],"records":[]}"""

// The interests answered for an account that has saved none: every option.
private val DEFAULTS = encodeInterests(DEFAULT_INTERESTS)

private fun aList(id: String, name: String) = ONE_LIST.replace("\"one\"", "\"$id\"").replace("\"One\"", "\"$name\"")

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
    assertEquals(200 to """{"lists":[],"packs":null,"devices":[],"interests":$DEFAULTS,"revision":0,"accountId":"$TEST_OWNER"}""" + "\n", response.statusCode() to response.body())
    expectJsonHeaders("get", response)
  }

  // The stored jsonb text is answered compact, with HTML characters escaped, as Go's encoder writes a RawMessage.
  @Test fun getSaved() {
    store.saved = Saved("""[{"id": "a", "name": "<b>"}]""", """[{"id": "d", "name": "&"}]""", """[{"id": "p", "name": "<P>"}]""", 4)
    serve {
      val response = get()
      assertEquals(
        """{"lists":[{"id":"a","name":"\u003cb\u003e"}],"packs":[{"id":"p","name":"\u003cP\u003e"}],""" +
          """"devices":[{"id":"d","name":"\u0026"}],"interests":$DEFAULTS,"revision":4,"accountId":"$TEST_OWNER"}""" + "\n",
        response.body())
    }
    // Lists saved before packs existed have none: null.
    store.saved = Saved("[]", "[]", null, 5)
    serve { assertEquals("""{"lists":[],"packs":null,"devices":[],"interests":$DEFAULTS,"revision":5,"accountId":"$TEST_OWNER"}""" + "\n", get().body()) }
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
        """"packs":null,"devices":[$device],"interests":$DEFAULTS,"revision":4,"accountId":"$TEST_OWNER"}""" + "\n",
      response.body())
    expectJsonHeaders("put", response)
    assertEquals(3L, store.revision)
    assertTrue(store.change!!.hasLists && store.change!!.hasDevices)
    // A part left out stays as saved.
    store.saved = Saved("[]", "[]", null, 4)
    assertEquals("""{"lists":[],"packs":null,"devices":[],"interests":$DEFAULTS,"revision":5,"accountId":"$TEST_OWNER"}""" + "\n",
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

  // packs are taken only with the lists they hold, and checked as sent, as validateWorkspaceRequest and validateLibrary
  // do: a list of a save with packs has no labels of its own, not even empty ones.
  @Test fun putPacks() = serve {
    val response = put(payload(TEST_OWNER, """"revision":1,"lists":[$PACKED_ONE],"packs":[$A_PACK]"""))
    assertEquals("""{"lists":[$PACKED_ONE],"packs":[$A_PACK],"devices":[],"interests":$DEFAULTS,"revision":2,"accountId":"$TEST_OWNER"}""" + "\n",
      response.body())
    val change = store.change!!
    assertTrue(change.hasLists && change.hasPacks && change.packs.size == 1 && change.packs[0].category == "C", change.toString())
    // A save from an app that does not know packs keeps the labels it sent on its lists, and the saved packs.
    val labeled = ONE_LIST.replace(""""records":[]""", """"records":[],"group":"G","category":"C","tags":["t"]""")
    val again = put(payload(TEST_OWNER, """"revision":2,"lists":[$labeled]"""))
    assertEquals(200, again.statusCode(), again.body())
    assertTrue(""""group":"G","category":"C","tags":["t"]""" in again.body() && """"packs":[$A_PACK]""" in again.body(), again.body())
    val list = store.change!!.lists[0]
    assertTrue(!store.change!!.hasPacks && list.group == "G" && list.category == "C" && list.packId == "", store.change.toString())
    val writes = store.writes
    fun with(fields: String) = PACKED_ONE.replace(""""records":[]""", """"records":[],$fields""")
    val cases = mapOf(
      "null packs" to """"lists":[$PACKED_ONE],"packs":null""",
      "packs alone" to """"packs":[$A_PACK]""",
      "packs with devices" to """"devices":[],"packs":[$A_PACK]""",
      "packs object" to """"lists":[$PACKED_ONE],"packs":{}""",
      "missing pack" to """"lists":[${PACKED_ONE.replace("\"p\"", "\"q\"")}],"packs":[$A_PACK]""",
      "empty category" to """"lists":[${with(""""category":""""")}],"packs":[$A_PACK]""",
      "empty tags" to """"lists":[${with(""""tags":[]""")}],"packs":[$A_PACK]""",
      "group" to """"lists":[${with(""""group":"G"""")}],"packs":[$A_PACK]""",
      "null description" to """"lists":[$PACKED_ONE],"packs":[${A_PACK.replace(""""category":"C"""", """"description":null""")}]""",
      "pack without lists" to """"lists":[$PACKED_ONE],"packs":[$A_PACK,{"id":"q","name":"Q","createdAt":"2026-09-06"}]""",
      "unstorable pack" to """"lists":[$PACKED_ONE],"packs":[${A_PACK.replace("\"P\"", "\"a\\u0000b\"")}]""",
    )
    for ((name, fields) in cases) expectError(put(payload(TEST_OWNER, """"revision":1,$fields""")), 400, "invalidWorkspace", name)
    assertEquals(writes, store.writes)
  }

  // Lists of a pack whose levels overlap are refused with levelsConflict when sent with their packs, and nothing is saved.
  @Test fun putLevelsConflict() = serve {
    fun leveled(id: String, levels: String) = PACKED_ONE.replace("\"id\":\"one\",\"name\":\"One\"", "\"id\":\"$id\",\"name\":\"$id\",\"levels\":$levels")
    fun body(second: String) = payload(TEST_OWNER, """"revision":1,"lists":[${leveled("a", """["初級"]""")},${leveled("b", second)}],"packs":[$A_PACK]""")
    val writes = store.writes
    expectError(put(body("""["初級"]""")), 400, "levelsConflict")
    assertEquals(writes, store.writes)
    // Data separated on reading is saved as it is.
    val ok = put(body("""["初級（2）"]"""))
    assertEquals(200, ok.statusCode(), ok.body())
  }

  // A list sent with its pack by an app before v0.97.0 may have several levels (up to 5): the save is taken and the lists
  // are stored, and answered, with them joined into one and told apart; more is invalidWorkspace.
  @Test fun putSeveralLevels() = serve {
    fun leveled(id: String, levels: String) = PACKED_ONE.replace("\"id\":\"one\",\"name\":\"One\"", "\"id\":\"$id\",\"name\":\"$id\",\"levels\":$levels")
    fun body(vararg lists: String) = payload(TEST_OWNER, """"revision":1,"lists":[${lists.joinToString(",")}],"packs":[$A_PACK]""")
    val ok = put(body(leveled("a", """["基本","初級"]"""), leveled("b", """["基本","初級",""]""")))
    assertEquals(200, ok.statusCode(), ok.body())
    assertTrue("""["基本・初級"]""" in ok.body() && """["基本・初級（2）"]""" in ok.body(), ok.body())
    assertEquals(listOf("基本・初級", "基本・初級（2）"), store.change!!.lists.map { levelsOf(it).single() })
    val writes = store.writes
    expectError(put(body(leveled("a", """["1","2","3","4","5","6"]"""))), 400, "invalidWorkspace")
    assertEquals(writes, store.writes)
  }

  // A save with packs is stored as the app's repair would leave a library validateLibrary accepts: names trimmed.
  @Test fun putPacksTrimsNames() = serve {
    val list = PACKED_ONE.replace("\"One\"", "\" One \"")
    val pack = A_PACK.replace("\"P\"", "\"\u3000P \"")
    val response = put(payload(TEST_OWNER, """"revision":1,"lists":[$list],"packs":[$pack]"""))
    assertEquals("""{"lists":[$PACKED_ONE],"packs":[$A_PACK],"devices":[],"interests":$DEFAULTS,"revision":2,"accountId":"$TEST_OWNER"}""" + "\n",
      response.body())
  }

  private fun interestsOf(response: HttpResponse<String>): String =
    response.body().substringAfter("\"interests\":").substringBefore(",\"revision\":")

  // interests are taken as decodeInterests (src/core/interests.ts) takes them, on their own or with the rest: left out,
  // they stay as saved; null empties them, as the app reads it (Go's TestPutInterests).
  @Test fun putInterests() = serve {
    val response = put(payload(TEST_OWNER, """"revision":3,"interests":["language","language/zh"]"""))
    assertEquals(200 to """["language","language/zh"]""", response.statusCode() to interestsOf(response), response.body())
    var change = store.change!!
    assertTrue(store.writes == 1 && store.revision == 3L && change.hasInterests && !change.hasLists && !change.hasDevices &&
      change.interests == listOf("language", "language/zh"), change.toString())
    // A body of an app that does not know interests leaves them out.
    assertEquals(200, put(payload(TEST_OWNER, """"revision":4,"lists":[$ONE_LIST]""")).statusCode())
    assertFalse(store.change!!.hasInterests)
    assertEquals(200, put(payload(TEST_OWNER, """"revision":5,"interests":null""")).statusCode())
    change = store.change!!
    assertTrue(change.hasInterests && change.interests.isEmpty(), change.toString())
    // With the other parts too.
    assertEquals(200, put(payload(TEST_OWNER, """"revision":6,"lists":[$PACKED_ONE],"packs":[$A_PACK],"devices":[],"interests":["x/unknown"]""")).statusCode())
    change = store.change!!
    assertTrue(change.hasInterests && change.hasLists && change.hasPacks && change.interests == listOf("x/unknown"), change.toString())
    val writes = store.writes
    val many = (0..MAX_INTERESTS).joinToString(",", "[", "]") { "\"x/$it\"" }
    val cases = mapOf(
      "object" to "{}",
      "string" to "\"language\"",
      "number item" to """["a",1]""",
      "null item" to "[null]",
      "empty item" to """[""]""",
      "duplicate" to """["a","a"]""",
      "too long" to """["${"a".repeat(MAX_INTEREST_LENGTH + 1)}"]""",
      "too many" to many,
      "NUL" to """["a\u0000"]""",
      "lone surrogate" to """["a\ud800"]""",
    )
    for ((name, interests) in cases) {
      expectError(put(payload(TEST_OWNER, """"revision":7,"interests":$interests""")), 400, "invalidWorkspace", name)
      expectError(put(payload(TEST_OWNER, """"revision":7,"lists":[$ONE_LIST],"interests":$interests""")), 400, "invalidWorkspace", "$name with lists")
    }
    assertEquals(writes, store.writes)
    // Overlapping levels are told so before invalid interests, as validateWorkspaceRequest does.
    fun leveled(id: String) = PACKED_ONE.replace("\"id\":\"one\",\"name\":\"One\"", "\"id\":\"$id\",\"name\":\"$id\",\"levels\":[\"初級\"]")
    expectError(put(payload(TEST_OWNER, """"revision":7,"lists":[${leveled("a")},${leveled("b")}],"packs":[$A_PACK],"interests":[""]""")),
      400, "levelsConflict")
    // An answer from a store that has none to give is the default (every option), as the app reads none; saved [] is [].
    store.saved = Saved("[]", "[]", null, 1)
    assertEquals(DEFAULTS, interestsOf(get()))
    store.saved = Saved("[]", "[]", null, 1, "[]")
    assertEquals("[]", interestsOf(get()))
    // The stored jsonb text is answered compact, with HTML characters escaped, as the other parts.
    store.saved = Saved("[]", "[]", null, 1, """["a", "<&>"]""")
    assertEquals("""["a","\u003c\u0026\u003e"]""", interestsOf(get()))
  }

  // A save with packs follows the official catalog before it is stored, as Go's Decode makes it (followOfficial): copies
  // of official packs from before v0.98.0 go with their lists and records, and a followed pack is made equal to the
  // catalog, keeping its name and the records of its lists.
  private fun followBody(owner: String, revision: Int): String {
    val first = catalog[0]
    fun list(id: String, packId: String, extra: String = "") =
      """{"id":"$id","name":"$id","packId":"$packId",$extra"createdAt":"2026-09-06","words":[{"display":"a","input":"a","annotation":""}],""" +
        """"records":[{"time":1,"date":"2026-10-02T00:00:00.000Z"}]}"""
    val packs = """{"id":"copy","name":"Copy","origin":{"catalogId":"${first.catalogId}","version":1},"createdAt":"2026-09-06"},""" +
      """{"id":"${first.catalogId}","name":"Old","createdAt":"2026-09-06"},""" +
      """{"id":"f","name":"Mine","origin":{"catalogId":"${first.catalogId}","version":1,"followed":true},"createdAt":"2026-09-06"},""" +
      """{"id":"p","name":"P","createdAt":"2026-09-06"}"""
    val lists = listOf(list("c1", "copy"), list("o1", first.catalogId), list("mine", "p"),
      list("f1", "f", """"origin":{"listId":"${first.lists[0].id}"},"""))
    return payload(owner, """"revision":$revision,"lists":[${lists.joinToString(",")}],"packs":[$packs]""")
  }

  @Test fun putFollowsTheCatalog() = serve {
    val response = put(followBody(TEST_OWNER, 1))
    assertEquals(200, response.statusCode(), response.body())
    val change = store.change!!
    val first = catalog[0]
    assertEquals(listOf("f", "p"), change.packs.map { it.id })
    assertEquals(Origin(first.catalogId, 1.0, followed = true), change.packs[0].origin)
    assertEquals("Mine" to first.pack.category, change.packs[0].name to change.packs[0].category)
    // The followed pack's lists take the place of its first list.
    assertEquals(listOf("mine", "f1") + first.lists.drop(1).map { "f:" + it.id }, change.lists.map { it.id })
    val synced = change.lists[1]
    assertEquals(Triple(first.lists[0].name, first.lists[0].words, 1), Triple(synced.name, synced.words, synced.records.size))
    assertTrue(""""followed":true""" in response.body(), response.body())
    assertFalse(""""id":"c1"""" in response.body() || """"id":"copy"""" in response.body(), response.body())
  }

  // The database tests run against TYPLE_TEST_DATABASE_URL, as PgStoreTest, and are skipped without it.
  private fun <T> serveReal(block: TestServer.(owner: String) -> T): T {
    val connection = System.getenv("TYPLE_TEST_DATABASE_URL").orEmpty()
    assumeTrue(connection.isNotEmpty(), "TYPLE_TEST_DATABASE_URL is not set")
    val owner = "github:8" + Random.nextLong(100_000_000_000L, 999_999_999_999L)
    return PgStore.open(connection).use { real -> serve(testConfig(real)) { block(owner) } }
  }

  private fun packsOf(response: HttpResponse<String>): String =
    response.body().substringAfter("\"packs\":").substringBefore(",\"devices\":")

  @Test fun packsFlowRealStore() = serveReal { owner ->
    val cookie = sessionCookie(owner)
    assertEquals("null", packsOf(get(cookie)), "without a row")
    assertEquals(200, put(payload(owner, """"revision":0,"lists":[$ONE_LIST]"""), cookie).statusCode())
    assertEquals("null", packsOf(get(cookie)), "for a row saved without packs")
    val saved = put(payload(owner, """"revision":1,"lists":[$PACKED_ONE],"packs":[$A_PACK]"""), cookie)
    val read = get(cookie)
    assertEquals(200 to saved.body(), saved.statusCode() to read.body())
    assertTrue(""""category":"C"""" in packsOf(read), read.body())
    // Lists sent alone leave the saved packs as they are.
    assertEquals(200, put(payload(owner, """"revision":2,"lists":[$PACKED_ONE,${aList("two", "Two")}]"""), cookie).statusCode())
    assertEquals(packsOf(read), packsOf(get(cookie)))
  }

  // A lists-only save stores the group it sends, which becomes a pack's name when it is read, so a group longer than
  // validateLists allows is refused, as the app does: a pack name over the limit would refuse every later save.
  @Test fun groupLimitRealStore() = serveReal { owner ->
    val cookie = sessionCookie(owner)
    fun group(n: Int) = ONE_LIST.replace(""""records":[]""", """"records":[],"group":"${"g".repeat(n)}"""")
    expectError(put(payload(owner, """"revision":0,"lists":[${group(MAX_GROUP + 1)}]"""), cookie), 400, "invalidWorkspace")
    assertEquals(200, put(payload(owner, """"revision":0,"lists":[${group(MAX_GROUP)}]"""), cookie).statusCode())
  }

  // With the database: interests saved alone are answered and read back, and kept by saves that leave them out (Go's
  // TestPutInterestsFlow).
  // With the database: the copies are gone from what is stored and read back (typle-r's e2e packs-migration.spec.ts).
  @Test fun followFlowRealStore() = serveReal { owner ->
    val cookie = sessionCookie(owner)
    val saved = put(followBody(owner, 0), cookie)
    assertEquals(200, saved.statusCode(), saved.body())
    val read = get(cookie)
    assertEquals(saved.body(), read.body())
    assertTrue(""""id":"f",""" in packsOf(read) && """"id":"p",""" in packsOf(read) && """"id":"copy"""" !in packsOf(read), read.body())
  }

  @Test fun interestsFlowRealStore() = serveReal { owner ->
    val cookie = sessionCookie(owner)
    fun expectInterests(response: HttpResponse<String>, want: String) =
      assertEquals(200 to want, response.statusCode() to interestsOf(response), response.body())
    expectInterests(get(cookie), DEFAULTS)
    expectInterests(put(payload(owner, """"revision":0,"interests":["language"]"""), cookie), """["language"]""")
    expectInterests(get(cookie), """["language"]""")
    expectInterests(put(payload(owner, """"revision":1,"lists":[$ONE_LIST],"devices":[]"""), cookie), """["language"]""")
    expectInterests(put(payload(owner, """"revision":2,"interests":["language","language/ru"]"""), cookie), """["language","language/ru"]""")
    expectInterests(put(payload(owner, """"revision":3,"devices":[]"""), cookie), """["language","language/ru"]""")
    val read = get(cookie).body()
    assertTrue(read.startsWith("""{"lists":[{"id":"one",""") && read.endsWith(""","revision":4,"accountId":"$owner"}""" + "\n"), read)
    expectError(put(payload(owner, """"revision":4,"interests":["a","a"]"""), cookie), 400, "invalidWorkspace")
    expectInterests(put(payload(owner, """"revision":4,"interests":null"""), cookie), "[]")
    expectInterests(get(cookie), "[]")
  }
}
