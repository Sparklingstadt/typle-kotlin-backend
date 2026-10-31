package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.FFPFRejectedException
import io.github.sparklingstadt.typle.server.auth.FFPFUser
import io.github.sparklingstadt.typle.server.auth.goQueryEscape
import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.session.OAUTH_COOKIE
import io.github.sparklingstadt.typle.server.session.OAuthState
import io.github.sparklingstadt.typle.server.session.SessionTimes
import io.github.sparklingstadt.typle.server.session.newAdmin
import io.github.sparklingstadt.typle.server.session.newSession
import io.github.sparklingstadt.typle.server.store.AdminAccount
import io.github.sparklingstadt.typle.server.store.AdminOverview
import io.github.sparklingstadt.typle.server.store.AdminShare
import io.github.sparklingstadt.typle.server.store.AdminUser
import io.github.sparklingstadt.typle.server.store.AdminUsers
import java.io.IOException
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1700000000_000L

// The cases of server/internal/web/admin_test.go. Go's tests also check which failures are reported; here failures
// are only logged.
class AdminRoutesTest {
  private val oauth = FakeOAuth()
  private val gitHub = FakeGitHub()
  private val ffpf = FakeFFPF(FFPFUser("cadmin", "Admin@Example.com", "Admin"))
  private val store = FakeStore()
  private val keys = Keys.create(TEST_SECRET)

  private fun config(base: String = TEST_BASE) = testConfig(store = store, base = base, oauth = oauth, gitHub = gitHub, ffpf = ffpf, now = { NOW })

  private fun <T> serve(config: Config = config(), block: TestServer.() -> T): T = TestServer { typleWeb(config) }.use { it.block() }

  // Goes through /api/auth/signin?admin=1 and returns the OAuth cookie it set.
  private fun TestServer.adminSignin(query: String = ""): String {
    val response = request("GET", "/api/auth/signin?admin=1$query")
    assertEquals(302, response.statusCode(), response.headers().toString())
    return "$OAUTH_COOKIE=" + response.cookieValue(OAUTH_COOKIE)!!
  }

  private fun TestServer.ffpfCallback(query: String, cookie: String) =
    request("GET", "/api/auth/callback/ffpf?$query", headers = mapOf("Cookie" to cookie))

  // The login to the dashboard, coming back from FFPF ID with the state it was given.
  private fun TestServer.adminLogin(): HttpResponse<String> {
    val cookie = adminSignin()
    return ffpfCallback("state=" + goQueryEscape(ffpf.state) + "&code=c", cookie)
  }

  private fun adminCookie(email: String, sub: String, secure: Boolean = false, nowMillis: Long = NOW) =
    (if (secure) "__Host-typle-admin" else "typle-admin") + "=" + keys.sealAdmin(newAdmin(email, sub, nowMillis))

  private fun TestServer.dashboard(cookie: String? = null) =
    request("GET", "/api/admin/dashboard", headers = if (cookie == null) emptyMap() else mapOf("Cookie" to cookie))

  // Fails unless the response set no cookie but the spent OAuth cookie.
  private fun onlyOAuthCookie(response: HttpResponse<String>) =
    assertEquals(listOf("typle-oauth=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Lax"), response.cookies())

  // A login to the dashboard always goes through FFPF ID, whatever provider says, and always returns to the
  // dashboard; an app's client is not taken.
  @Test fun signin() = serve {
    for (query in listOf("", "&provider=github", "&provider=ffpf")) {
      ffpf.state = ""
      val response = request("GET", "/api/auth/signin?admin=1&returnTo=%2Fplay&client=ipad&code_challenge=x$query")
      assertEquals(302 to "https://id.test/oauth/authorize?state=" + goQueryEscape(ffpf.state), response.statusCode() to response.location(), query)
      val opened = keys.openOAuth(response.cookieValue(OAUTH_COOKIE)!!, NOW)!!
      assertTrue(opened.admin && opened.provider == "ffpf" && opened.nonce.isNotEmpty() && opened.returnTo == "/admin" &&
        opened.challenge == "", "$query: $opened")
    }
    assertEquals("", oauth.redirectUri, "GitHub was asked")
    val plain = keys.openOAuth(request("GET", "/api/auth/signin?returnTo=%2Fplay").cookieValue(OAUTH_COOKIE)!!, NOW)!!
    assertFalse(plain.admin, "a login without admin=1 is a login to the dashboard")
  }

  // An administrator's account gets the administrator's cookie, and no session cookie.
  @Test fun callback() {
    for ((base, secure) in listOf(TEST_BASE to false, "https://typle.test" to true)) {
      val store = FakeStore().apply { allowed = true }
      serve(testConfig(store = store, base = base, oauth = oauth, gitHub = gitHub, ffpf = ffpf, now = { NOW })) {
        val response = adminLogin()
        assertEquals(302 to "/admin", response.statusCode() to response.location(), base)
        val session = if (secure) "__Host-typle-session" else "typle-session"
        assertNull(response.cookie(session), "$base: the dashboard's login set the session cookie")
        val name = if (secure) "__Host-typle-admin" else "typle-admin"
        val cookie = response.cookie(name)
        assertNotNull(cookie, base)
        val attributes = "; Path=/; Max-Age=${SessionTimes.ADMIN_MAX_AGE}; HttpOnly" + (if (secure) "; Secure" else "") + "; SameSite=Strict"
        assertTrue(cookie.endsWith(attributes), cookie)
        assertEquals(newAdmin("admin@example.com", "cadmin", NOW), keys.openAdmin(response.cookieValue(name)!!, NOW), base)
        assertEquals(listOf("1", "admin@example.com", "cadmin"), listOf(store.checks.toString(), store.adminEmail, store.adminSub), base)
      }
    }
  }

  // An account typle_admins does not let in goes back to the dashboard refused, signed in to nothing; when
  // typle_admins cannot be asked it goes back with adminUnavailable.
  @Test fun callbackRefuses() = serve {
    ffpf.user = FFPFUser("cother", "someone@example.com", "Admin")
    val refused = adminLogin()
    assertEquals(302 to "/admin?error=adminForbidden", refused.statusCode() to refused.location())
    onlyOAuthCookie(refused)
    assertEquals("someone@example.com" to "cother", store.adminEmail to store.adminSub)

    store.checkFailure = IOException("down")
    val down = adminLogin()
    assertEquals("/admin?error=adminUnavailable", down.location())
    onlyOAuthCookie(down)

    // A failed login returns to the dashboard too, without asking typle_admins.
    store.checks = 0
    val wrong = ffpfCallback("state=wrong&code=c", adminSignin())
    assertEquals("/admin" to 0, wrong.location() to store.checks)
    onlyOAuthCookie(wrong)
    ffpf.failure = FFPFRejectedException("code used twice")
    val rejected = adminLogin()
    assertEquals("/admin" to 0, rejected.location() to store.checks)
    onlyOAuthCookie(rejected)
  }

  // Without a database to ask, nobody becomes the administrator: the login ends with adminUnavailable.
  @Test fun callbackWithoutDatabase() {
    ffpf.user = FFPFUser("cadmin", "admin@example.com", "Admin")
    serve(testConfig(store = null, oauth = null, gitHub = null, ffpf = ffpf, now = { NOW })) {
      val response = adminLogin()
      assertEquals("/admin?error=adminUnavailable", response.location())
      onlyOAuthCookie(response)
    }
    // Config.admins is asked before store.
    val admins = FakeAdmins(allowed = true)
    serve(testConfig(store = FakeStore(), ffpf = ffpf, admins = admins, now = { NOW })) {
      val response = adminLogin()
      assertEquals("/admin" to 1, response.location() to admins.checks)
      assertNotNull(response.cookie("typle-admin"))
    }
  }

  // GitHub's callback never makes an administrator: a login to the dashboard that comes back there is refused,
  // without asking GitHub or typle_admins.
  @Test fun callbackNotThroughGitHub() = serve {
    store.allowed = true
    val cookie = adminSignin()
    val opened = keys.openOAuth(cookie.substringAfter('='), NOW)!!
    // Also one started before the dashboard's login went to FFPF ID: an admin cookie of a GitHub login.
    val old = "$OAUTH_COOKIE=" + keys.sealOAuth(OAuthState("s", opened.verifier, "/admin", "", NOW / 1000 + 60, admin = true))
    for ((c, state) in listOf(cookie to opened.state, old to "s")) {
      val response = request("GET", "/api/auth/callback/github?state=" + goQueryEscape(state) + "&code=good", headers = mapOf("Cookie" to c))
      assertEquals(302 to "/admin?error=adminForbidden", response.statusCode() to response.location())
      onlyOAuthCookie(response)
    }
    assertEquals("" to 0, oauth.verifier to store.checks)
  }

  @Test fun dashboard() = serve {
    store.allowed = true
    store.overview = AdminOverview(3, 0, 2, 0, 1, emptyList(), emptyList())
    val response = dashboard(adminCookie("admin@example.com", "cadmin"))
    assertEquals(200, response.statusCode(), response.body())
    expectJsonHeaders("dashboard", response)
    expectSecurityHeaders("dashboard", response)
    assertEquals("""{"admin":{"email":"admin@example.com","sub":"cadmin"},"overview":{"workspaces":3,"activeDay":0,""" +
      """"activeWeek":2,"activeMonth":0,"sharedPacks":1,"recentShares":[],"admins":[]}}""" + "\n", response.body())
    assertEquals("admin@example.com" to "cadmin", store.adminEmail to store.adminSub)
  }

  // The figures are written as Go's encoder writes them: times in RFC 3339 without the fraction's trailing zeros, and
  // null for a sub not yet bound and an administrator not yet seen.
  @Test fun dashboardFigures() = serve {
    store.allowed = true
    val at = Instant.parse("2026-10-20T03:04:05Z")
    store.overview = AdminOverview(12, 1, 4, 9, 3,
      listOf(AdminShare("00000000-0000-4000-8000-000000000001", "github:42", "<Words>", at.plusNanos(120_000_000))),
      listOf(AdminAccount("admin@example.com", "cadmin", at, at.plusNanos(123_456_000)), AdminAccount("new@example.com", null, at, null)))
    val body = dashboard(adminCookie("admin@example.com", "cadmin")).body()
    assertEquals("""{"admin":{"email":"admin@example.com","sub":"cadmin"},"overview":{"workspaces":12,"activeDay":1,""" +
      """"activeWeek":4,"activeMonth":9,"sharedPacks":3,"recentShares":[{"id":"00000000-0000-4000-8000-000000000001",""" +
      """"ownerId":"github:42","name":"\u003cWords\u003e","updatedAt":"2026-10-20T03:04:05.12Z"}],"admins":[""" +
      """{"email":"admin@example.com","ffpfSub":"cadmin","createdAt":"2026-10-20T03:04:05Z","lastSeenAt":"2026-10-20T03:04:05.123456Z"},""" +
      """{"email":"new@example.com","ffpfSub":null,"createdAt":"2026-10-20T03:04:05Z","lastSeenAt":null}]}}""" + "\n", body)
  }

  @Test fun dashboardRefuses() = serve {
    store.allowed = true
    store.overview = AdminOverview(0, 0, 0, 0, 0, emptyList(), emptyList())
    val forged = adminCookie("x@example.com", "c1").replaceFirst(".", "x.")
    for ((name, cookie) in mapOf(
      "no cookie" to null,
      "session cookie" to "typle-session=" + keys.seal(newSession("github:7", "x", "", NOW)),
      "expired" to adminCookie("admin@example.com", "cadmin", nowMillis = NOW - SessionTimes.ADMIN_MAX_AGE * 1000),
      "invalid sub" to adminCookie("admin@example.com", "Not-A-Sub"),
      "no email" to adminCookie("", "cadmin"),
      "forged" to forged,
    )) {
      expectError(dashboard(cookie), 401, "adminRequired", name)
      assertEquals(0, store.checks, "$name: the database was asked")
    }

    // An account typle_admins does not let in loses its cookie.
    store.allowed = false
    val forbidden = dashboard(adminCookie("admin@example.com", "cother"))
    expectError(forbidden, 403, "adminForbidden")
    assertEquals(listOf("typle-admin=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"), forbidden.cookies())

    store.checkFailure = IOException("down")
    expectError(dashboard(adminCookie("admin@example.com", "cadmin")), 503, "databaseUnavailable")
    store.allowed = true
    store.checkFailure = null
    store.failure = IOException("down")
    expectError(dashboard(adminCookie("admin@example.com", "cadmin")), 503, "databaseUnavailable")
    expectError(request("POST", "/api/admin/dashboard"), 405, "methodNotAllowed")
  }

  @Test fun signout() = serve {
    expectError(request("POST", "/api/auth/admin/signout"), 403, "forbiddenRequest")
    expectError(request("POST", "/api/auth/admin/signout", headers = mapOf("Origin" to "https://evil.example")), 403, "forbiddenRequest")
    expectError(request("GET", "/api/auth/admin/signout"), 405, "methodNotAllowed")
    val response = request("POST", "/api/auth/admin/signout", headers = mapOf("Origin" to TEST_BASE))
    assertEquals(204, response.statusCode())
    assertEquals(listOf("typle-admin=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"), response.cookies())
  }

  @Test fun users() = serve(testConfig(store = store, now = { NOW })) {
    store.allowed = true
    store.users = AdminUsers(51, listOf(AdminUser("github:42", 3, 0, 0, 1, 2, Instant.ofEpochSecond(1700000000))))
    val admin = mapOf("Cookie" to adminCookie("admin@example.com", "cadmin"))

    val response = request("GET", "/api/admin/users?page=1", headers = admin)
    assertEquals(200, response.statusCode(), response.body())
    assertEquals("""{"page":1,"pageSize":50,"total":51,"users":[{"ownerId":"github:42","lists":3,"packs":0,"devices":0,"shares":1,""" +
      """"revision":2,"updatedAt":"2023-11-14T22:13:20Z"}]}""" + "\n", response.body())
    assertEquals(1, store.usersPage)
    request("GET", "/api/admin/users", headers = admin)
    assertEquals(0, store.usersPage, "no page")
    for (page in listOf("-1", "x", "1.5", "10001", "99999999999999999999")) {
      store.usersPage = -1
      expectError(request("GET", "/api/admin/users?page=$page", headers = admin), 400, "invalidPage", page)
      assertEquals(-1, store.usersPage, "page $page reached the database")
    }
    // The list is the administrator's alone.
    expectError(request("GET", "/api/admin/users"), 401, "adminRequired")
    store.allowed = false
    expectError(request("GET", "/api/admin/users", headers = admin), 403, "adminForbidden")
    store.allowed = true
    store.failure = IOException("down")
    expectError(request("GET", "/api/admin/users", headers = admin), 503, "databaseUnavailable")
  }
}
