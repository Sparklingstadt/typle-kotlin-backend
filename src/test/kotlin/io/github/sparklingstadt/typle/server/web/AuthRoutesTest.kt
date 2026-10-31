package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.session.newSession
import java.net.URLEncoder
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1700000000_000L
private const val VERIFIER = "the-app-verifier-of-at-least-forty-three-characters"


// The cases of server/internal/web/auth_test.go.
class AuthRoutesTest {
  private val oauth = FakeOAuth()
  private val gitHub = FakeGitHub()
  private var now = NOW

  private fun config(base: String = TEST_BASE, localLogin: Boolean = false) =
    testConfig(oauth = oauth, gitHub = gitHub, base = base, localLogin = localLogin, now = { now })

  private fun <T> serve(config: Config = config(), block: TestServer.() -> T): T = TestServer { typleWeb(config) }.use { it.block() }

  // Starts a login and comes back from GitHub with code, as a browser would.
  private fun TestServer.login(signin: String = "/api/auth/signin?returnTo=%2Frecords", code: String = "good"): HttpResponse<String> {
    val started = request("GET", signin)
    val oauthCookie = started.cookie("typle-oauth")!!.substringBefore(';')
    val state = query(started.location())["state"]!!
    return request("GET", "/api/auth/callback/github?code=$code&state=$state", headers = mapOf("Cookie" to oauthCookie))
  }

  @Test fun returnToIsSafe() {
    assertEquals("/records?x=1", safeReturnTo("/records?x=1"))
    assertEquals("/", safeReturnTo("/"))
    for (value in listOf(null, "", "records", "//evil", "/\\evil", "https://evil", "/" + "a".repeat(1024), "/a\u0007", "/a%zz", "/a\u007f")) {
      assertEquals("/home", safeReturnTo(value), value.toString())
    }
    assertEquals("/" + "a".repeat(1023), safeReturnTo("/" + "a".repeat(1023)))
  }

  @Test fun signin() = serve {
    val response = request("GET", "/api/auth/signin?returnTo=%2Frecords")
    assertEquals(302, response.statusCode())
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
    expectSecurityHeaders("signin", response)
    assertTrue(response.location().startsWith("https://github.example/authorize?"), response.location())
    assertEquals("$TEST_BASE/api/auth/callback/github", query(response.location())["redirect_uri"])
    assertEquals(43, query(response.location())["code_challenge"]!!.length)
    val cookie = response.cookie("typle-oauth")!!
    assertTrue(Regex("typle-oauth=[A-Za-z0-9_.-]+; Path=/api/auth; Max-Age=600; HttpOnly; SameSite=Lax").matches(cookie), cookie)
    assertEquals(1, response.cookies().size)
  }

  @Test fun signinRefuses() {
    serve {
      // An app's login needs its own S256 challenge.
      val bad = request("GET", "/api/auth/signin?client=android&code_challenge=" + "a".repeat(42))
      assertEquals(302 to "typle-r://login?error=loginFailed", bad.statusCode() to bad.location())
      val head = request("HEAD", "/api/auth/signin")
      assertEquals(405, head.statusCode())
    }
    serve(testConfig(oauth = null, gitHub = null)) {
      expectError(request("GET", "/api/auth/signin"), 503, "authUnavailable")
      expectError(request("GET", "/api/auth/callback/github?code=c&state=s"), 503, "authUnavailable")
    }
  }

  @Test fun callbackSignsIn() = serve {
    val response = login()
    assertEquals(302 to "/records", response.statusCode() to response.location())
    assertEquals("typle-oauth=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Lax", response.cookie("typle-oauth"))
    val cookie = response.cookie("typle-session")!!
    assertTrue(Regex("typle-session=[A-Za-z0-9_.-]+; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax").matches(cookie), cookie)
    val session = Keys.create(TEST_SECRET).open(cookie.substringAfter('=').substringBefore(';'), NOW)!!
    assertEquals(newSession("github:7", "Octo Cat", "https://avatars.example/7", NOW), session)
    assertEquals("$TEST_BASE/api/auth/callback/github", oauth.redirectUri)
    assertTrue(oauth.verifier.length >= 43)
  }

  @Test fun callbackFailuresDoNotSignIn() = serve {
    val started = request("GET", "/api/auth/signin?returnTo=%2Fplay")
    val oauthCookie = started.cookie("typle-oauth")!!.substringBefore(';')
    val state = query(started.location())["state"]!!
    val cases = mapOf(
      "state differs" to ("/api/auth/callback/github?code=good&state=other" to oauthCookie),
      "cancelled" to ("/api/auth/callback/github?error=access_denied&state=$state" to oauthCookie),
      "no code" to ("/api/auth/callback/github?state=$state" to oauthCookie),
      "exchange fails" to ("/api/auth/callback/github?code=bad&state=$state" to oauthCookie),
      "user fails" to ("/api/auth/callback/github?code=other&state=$state" to oauthCookie),
    )
    for ((name, case) in cases) {
      val response = request("GET", case.first, headers = mapOf("Cookie" to case.second))
      assertEquals(302 to "/play", response.statusCode() to response.location(), name)
      assertNull(response.cookie("typle-session"), name)
      assertEquals("typle-oauth=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Lax", response.cookie("typle-oauth"), name)
    }
    val noCookie = request("GET", "/api/auth/callback/github?code=good&state=$state")
    assertEquals(302 to "/home", noCookie.statusCode() to noCookie.location())
    now += 601_000
    val expired = request("GET", "/api/auth/callback/github?code=good&state=$state", headers = mapOf("Cookie" to oauthCookie))
    assertEquals(302 to "/home", expired.statusCode() to expired.location())
  }

  @Test fun appLogin() = serve {
    val challenge = challengeOf(VERIFIER)
    val response = login("/api/auth/signin?client=android&code_challenge=$challenge&returnTo=%2Fplay")
    assertEquals(302, response.statusCode())
    assertTrue(response.location().startsWith("typle-r://login?code="), response.location())
    assertNull(response.cookie("typle-session"))
    val code = query(response.location())["code"]!!
    val token = request("POST", "/api/auth/token", """{"code":"$code","verifier":"$VERIFIER"}""")
    assertEquals(200, token.statusCode())
    expectJsonHeaders("token", token)
    val issued = Keys.create(TEST_SECRET).sealApp(newSession("github:7", "Octo Cat", "https://avatars.example/7", NOW))
    assertEquals("""{"token":"$issued","user":{"id":"github:7","name":"Octo Cat","image":"https://avatars.example/7"}}""" + "\n", token.body())
    // The iPad's, the Mac's and the Windows app's logins end the same way; a failed one goes back to the app.
    val ipad = login("/api/auth/signin?client=ipad&code_challenge=$challenge", code = "bad")
    assertEquals("typle-r://login?error=loginFailed", ipad.location())
    val windows = login("/api/auth/signin?client=windows&code_challenge=$challenge", code = "bad")
    assertEquals("typle-r://login?error=loginFailed", windows.location())
    val mac = login("/api/auth/signin?client=macos&code_challenge=$challenge", code = "bad")
    assertEquals("typle-r://login?error=loginFailed", mac.location())
  }

  @Test fun tokenRefuses() = serve {
    val challenge = challengeOf(VERIFIER)
    val code = query(login("/api/auth/signin?client=ipad&code_challenge=$challenge").location())["code"]!!
    val bodies = mapOf(
      "other verifier" to """{"code":"$code","verifier":"${"x".repeat(43)}"}""",
      "short verifier" to """{"code":"$code","verifier":"short"}""",
      "not a code" to """{"code":"x","verifier":"$VERIFIER"}""",
      "not JSON" to "code",
      "verifier a number" to """{"code":"$code","verifier":1}""",
      "too long" to """{"code":"$code","verifier":"$VERIFIER","x":"${"a".repeat(4096)}"}""",
    )
    for ((name, body) in bodies) expectError(request("POST", "/api/auth/token", body), 400, "loginFailed", name)
    now += 121_000
    expectError(request("POST", "/api/auth/token", """{"code":"$code","verifier":"$VERIFIER"}"""), 400, "loginFailed", "expired")
    val get = request("GET", "/api/auth/token")
    expectError(get, 405, "methodNotAllowed")
    assertEquals("POST", get.headers().firstValue("Allow").orElse(null))
  }

  @Test fun session() = serve {
    val signedOut = request("GET", "/api/auth/session")
    assertEquals(200 to "{\"user\":null,\"providers\":[\"github\"]}\n", signedOut.statusCode() to signedOut.body())
    expectJsonHeaders("session", signedOut)
    expectSecurityHeaders("session", signedOut)
    val cookie = sessionCookie(TEST_OWNER, nowMillis = NOW)
    val signedIn = request("GET", "/api/auth/session", headers = mapOf("Cookie" to cookie))
    assertEquals("{\"user\":{\"id\":\"$TEST_OWNER\",\"name\":\"Tester\",\"image\":null},\"providers\":[\"github\"]}\n", signedIn.body())
    assertNull(signedIn.cookie("typle-session"))
    expectError(request("PUT", "/api/auth/session"), 405, "methodNotAllowed")
    assertEquals(200 to "", request("HEAD", "/api/auth/session").let { it.statusCode() to it.body() })
  }

  // A session is not signed again however old it grows (typle-r v0.107.0): it ends MAX_AGE after its login.
  @Test fun sessionNotRenewed() = serve {
    val old = NOW - 24 * 3600_000L
    val web = request("GET", "/api/auth/session", headers = mapOf("Cookie" to sessionCookie(TEST_OWNER, nowMillis = old)))
    assertNull(web.cookie("typle-session"))
    val app = request("GET", "/api/auth/session", headers = mapOf("Authorization" to "Bearer " + appToken(TEST_OWNER, old)))
    assertEquals("{\"user\":{\"id\":\"$TEST_OWNER\",\"name\":\"Tester\",\"image\":null},\"providers\":[\"github\"]}\n", app.body())
  }

  @Test fun signout() = serve {
    expectError(request("POST", "/api/auth/signout", headers = mapOf("Origin" to "https://evil.example")), 403, "forbiddenRequest")
    expectError(request("POST", "/api/auth/signout"), 403, "forbiddenRequest")
    val response = request("POST", "/api/auth/signout", headers = mapOf("Origin" to TEST_BASE))
    assertEquals(204, response.statusCode())
    assertEquals(listOf("typle-session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"), response.cookies())
    assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null))
    expectSecurityHeaders("signout", response)
    expectError(request("GET", "/api/auth/signout"), 405, "methodNotAllowed")
  }

  @Test fun localLogin() {
    serve {
      expectError(request("POST", "/api/auth/local", headers = mapOf("Origin" to TEST_BASE)), 404, "notFound")
      expectError(request("GET", "/api/auth/local"), 404, "notFound")
    }
    serve(config(localLogin = true)) {
      expectError(request("POST", "/api/auth/local", headers = mapOf("Origin" to "https://evil.example")), 403, "forbiddenRequest")
      expectError(request("GET", "/api/auth/local"), 405, "methodNotAllowed")
      val response = request("POST", "/api/auth/local", headers = mapOf("Origin" to TEST_BASE))
      assertEquals(204, response.statusCode())
      val cookie = response.cookie("typle-session")!!
      assertEquals(newSession("github:0", "ローカル練習用", "", NOW),
        Keys.create(TEST_SECRET).open(cookie.substringAfter('=').substringBefore(';'), NOW))
    }
  }

  @Test fun secureCookies() = serve(config(base = "https://typle.example")) {
    val cookie = login().cookie("__Host-typle-session")
    assertNotNull(cookie)
    assertTrue(cookie.endsWith("; Path=/; Max-Age=2592000; HttpOnly; Secure; SameSite=Lax"), cookie)
    assertTrue(request("GET", "/api/auth/signin").cookie("typle-oauth")!!.contains("; Secure; "))
  }

  @Test fun nameIsTrimmedAndFallsBackToLogin() {
    gitHub.account = gitHub.account.copy(name = "\u3000 Octo \u00a0")
    serve {
      val cookie = login().cookie("typle-session")!!
      assertEquals("Octo", Keys.create(TEST_SECRET).open(cookie.substringAfter('=').substringBefore(';'), NOW)!!.name)
    }
  }

  @Test fun authPathsNotFound() = serve {
    for (path in listOf("/api/auth", "/api/auth/", "/api/auth/callback/gitlab")) expectError(request("GET", path), 404, "notFound", path)
  }

  @Test fun returnToIsKeptWhole() = serve {
    val target = "/records?list=" + URLEncoder.encode("a b&c", Charsets.UTF_8)
    val response = login("/api/auth/signin?returnTo=" + URLEncoder.encode(target, Charsets.UTF_8))
    assertEquals(target, response.location())
  }
}
