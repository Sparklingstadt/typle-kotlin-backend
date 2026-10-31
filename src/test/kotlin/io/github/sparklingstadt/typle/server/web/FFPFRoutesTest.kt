package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.FFPFRejectedException
import io.github.sparklingstadt.typle.server.auth.FFPFUnavailableException
import io.github.sparklingstadt.typle.server.auth.goQueryEscape
import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.session.OAUTH_COOKIE
import io.github.sparklingstadt.typle.server.session.OAuthState
import io.github.sparklingstadt.typle.server.session.newSession
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1700000000_000L
private const val APP_VERIFIER = "the-app-verifier-of-at-least-forty-three-characters"

// The cases of server/internal/web/ffpf_test.go. Go's tests also check which failures are reported; here failures are
// only logged.
class FFPFRoutesTest {
  private val oauth = FakeOAuth()
  private val ffpf = FakeFFPF()
  private val keys = Keys.create(TEST_SECRET)

  private fun config() = testConfig(oauth = oauth, ffpf = ffpf, now = { NOW })

  private fun <T> serve(config: Config = config(), block: TestServer.() -> T): T = TestServer { typleWeb(config) }.use { it.block() }

  // Goes through /api/auth/signin?provider=ffpf and returns the OAuth cookie it set and what it holds.
  private fun TestServer.ffpfSignin(query: String = ""): Pair<String, OAuthState> {
    val response = request("GET", "/api/auth/signin?provider=ffpf$query")
    assertEquals(302, response.statusCode(), response.headers().toString())
    val value = response.cookieValue(OAUTH_COOKIE)!!
    return "$OAUTH_COOKIE=$value" to keys.openOAuth(value, NOW)!!
  }

  private fun TestServer.callback(provider: String, query: String, cookie: String?): HttpResponse<String> =
    request("GET", "/api/auth/callback/$provider?$query", headers = if (cookie == null) emptyMap() else mapOf("Cookie" to cookie))

  private fun TestServer.githubSignin(returnTo: String): Pair<String, OAuthState> {
    val value = request("GET", "/api/auth/signin?returnTo=" + goQueryEscape(returnTo)).cookieValue(OAUTH_COOKIE)!!
    return "$OAUTH_COOKIE=$value" to keys.openOAuth(value, NOW)!!
  }

  @Test fun signin() = serve {
    val response = request("GET", "/api/auth/signin?provider=ffpf&returnTo=%2Fplay")
    assertEquals(302, response.statusCode())
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
    assertEquals("https://id.test/oauth/authorize?state=" + goQueryEscape(ffpf.state), response.location())
    assertEquals("$TEST_BASE/api/auth/callback/ffpf", ffpf.redirectUri)
    assertEquals("", oauth.redirectUri)
    val cookie = response.cookie(OAUTH_COOKIE)!!
    assertTrue(Regex("typle-oauth=[A-Za-z0-9_.-]+; Path=/api/auth; Max-Age=600; HttpOnly; SameSite=Lax").matches(cookie), cookie)
    val opened = keys.openOAuth(response.cookieValue(OAUTH_COOKIE)!!, NOW)!!
    assertEquals(listOf("ffpf", "/play", ffpf.state), listOf(opened.provider, opened.returnTo, opened.state))
    assertFalse(opened.admin)
    // The nonce is 32 random bytes, as the state and the verifier are, and is the one FFPF ID was given.
    for ((name, value) in mapOf("state" to opened.state, "verifier" to opened.verifier, "nonce" to opened.nonce)) {
      assertEquals(32, Base64.getUrlDecoder().decode(value).size, name)
      assertFalse('=' in value, name)
    }
    assertEquals(opened.nonce, ffpf.nonce)
    assertEquals(challengeOf(opened.verifier), ffpf.challenge)
    assertNotEquals(opened.nonce, ffpfSignin().second.nonce, "the nonce repeated")
  }

  // Without provider, or with any other value, the login is GitHub's, and its OAuth cookie has neither a provider nor a
  // nonce, so it is the same as before FFPF ID.
  @Test fun signinDefaultsToGitHub() = serve {
    for (query in listOf("", "?provider=github", "?provider=FFPF", "?provider=")) {
      val response = request("GET", "/api/auth/signin$query")
      assertTrue(response.location().startsWith("https://github.example/"), "$query: ${response.location()}")
      val opened = keys.openOAuth(response.cookieValue(OAUTH_COOKIE)!!, NOW)!!
      assertEquals("" to "", opened.provider to opened.nonce, query)
    }
    assertEquals("", ffpf.state, "FFPF ID was asked")
  }

  @Test fun callbackSuccess() = serve {
    val (cookie, opened) = ffpfSignin("&returnTo=%2Fplay")
    val response = callback("ffpf", "code=the-code&state=" + goQueryEscape(opened.state), cookie)
    assertEquals(302 to "/play", response.statusCode() to response.location())
    assertEquals(listOf("1", "the-code", opened.verifier, "$TEST_BASE/api/auth/callback/ffpf", opened.nonce),
      listOf(ffpf.exchanges.toString(), ffpf.code, ffpf.verifier, ffpf.exchangeUri, ffpf.exchangeNonce))
    val set = response.cookie("typle-session")!!
    assertTrue(Regex("typle-session=[A-Za-z0-9_.-]+; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax").matches(set), set)
    assertEquals(newSession("ffpf:cabc", "FFPF User", "", NOW), keys.open(response.cookieValue("typle-session")!!, NOW))
    assertEquals("typle-oauth=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Lax", response.cookie(OAUTH_COOKIE))
    assertNull(response.cookie("typle-admin"))
    assertEquals("", oauth.verifier, "GitHub was asked")
  }

  // A login that one provider started does not finish at the other's callback: nobody is signed in, and neither
  // provider is asked for the code.
  @Test fun callbackOfAnotherProvider() = serve {
    val (github, githubState) = githubSignin("/lists")
    val atFFPF = callback("ffpf", "code=good&state=" + goQueryEscape(githubState.state), github)
    assertEquals(302 to "/lists", atFFPF.statusCode() to atFFPF.location())
    assertNull(atFFPF.cookie("typle-session"))
    val (ffpfCookie, ffpfState) = ffpfSignin("&returnTo=%2Fplay")
    val atGitHub = callback("github", "code=good&state=" + goQueryEscape(ffpfState.state), ffpfCookie)
    assertEquals(302 to "/play", atGitHub.statusCode() to atGitHub.location())
    assertNull(atGitHub.cookie("typle-session"))
    assertEquals("" to 0, oauth.verifier to ffpf.exchanges)
    // A cookie that names a provider nobody knows is refused at both.
    val unknown = "$OAUTH_COOKIE=" + keys.sealOAuth(OAuthState("s", "v".repeat(43), "/home", "", NOW / 1000 + 60, provider = "other"))
    for (provider in listOf("github", "ffpf")) {
      val response = callback(provider, "code=good&state=s", unknown)
      assertEquals("/home", response.location(), provider)
      assertNull(response.cookie("typle-session"), provider)
    }
  }

  @Test fun callbackRefuses() = serve {
    for ((name, query) in mapOf<String, (String) -> String>(
      "wrong state" to { "code=c&state=other" },
      "no code" to { "state=$it" },
      "cancelled" to { "error=access_denied&state=$it" },
    )) {
      val (cookie, opened) = ffpfSignin("&returnTo=%2Fplay")
      val response = callback("ffpf", query(goQueryEscape(opened.state)), cookie)
      assertEquals("/play", response.location(), name)
      assertNull(response.cookie("typle-session"), name)
    }
    assertEquals("/home", callback("ffpf", "code=c&state=s", null).location())
    assertEquals(0, ffpf.exchanges)
  }

  // When FFPF ID cannot be reached, or refuses, the browser goes back where it was, not to a 500.
  @Test fun callbackFails() {
    for (failure in listOf(FFPFUnavailableException("down"), FFPFRejectedException("code used twice"))) {
      ffpf.failure = failure
      serve {
        val (cookie, opened) = ffpfSignin("&returnTo=%2Fplay")
        val response = callback("ffpf", "code=c&state=" + goQueryEscape(opened.state), cookie)
        assertEquals(302 to "/play", response.statusCode() to response.location(), failure.toString())
        assertNull(response.cookie("typle-session"), failure.toString())
      }
    }
  }

  // An app's login through FFPF ID ends at typle-r://login with a code that becomes a token for the ffpf: account.
  @Test fun appLogin() = serve {
    val challenge = challengeOf(APP_VERIFIER)
    val (cookie, opened) = ffpfSignin("&client=ipad&code_challenge=$challenge")
    assertEquals(listOf(challenge, "ffpf", challengeOf(opened.verifier)), listOf(opened.challenge, opened.provider, ffpf.challenge))
    val response = callback("ffpf", "code=c&state=" + goQueryEscape(opened.state), cookie)
    assertNull(response.cookie("typle-session"))
    val location = query(response.location())
    assertEquals(setOf("code"), location.keys, response.location())
    val traded = request("POST", "/api/auth/token", """{"code":"${location["code"]}","verifier":"$APP_VERIFIER"}""")
    assertEquals(200, traded.statusCode(), traded.body())
    assertTrue(traded.body().contains(""","user":{"id":"ffpf:cabc","name":"FFPF User","image":null}}"""), traded.body())
    // A failed login goes back to the app too.
    ffpf.failure = FFPFUnavailableException("down")
    val (again, state) = ffpfSignin("&client=android&code_challenge=$challenge")
    assertEquals("typle-r://login?error=loginFailed", callback("ffpf", "code=c&state=" + goQueryEscape(state.state), again).location())
  }

  // Without FFPF ID only its login is unavailable: GitHub's still starts, and the session lists GitHub alone.
  @Test fun unavailable() {
    serve(testConfig(oauth = oauth, now = { NOW })) {
      for (path in listOf("/api/auth/signin?provider=ffpf", "/api/auth/signin?admin=1", "/api/auth/callback/ffpf?code=c&state=s")) {
        val response = request("GET", path)
        expectError(response, 503, "authUnavailable", path)
        assertEquals(emptyList(), response.cookies(), path)
      }
      assertEquals(302, request("GET", "/api/auth/signin").statusCode())
      assertEquals("{\"user\":null,\"providers\":[\"github\"]}\n", request("GET", "/api/auth/session").body())
    }
    // FFPF ID alone: the GitHub login is unavailable, and the session lists FFPF ID alone.
    serve(testConfig(oauth = null, gitHub = null, ffpf = ffpf, now = { NOW })) {
      expectError(request("GET", "/api/auth/signin"), 503, "authUnavailable")
      assertEquals("{\"user\":null,\"providers\":[\"ffpf\"]}\n", request("GET", "/api/auth/session").body())
    }
  }

  // The session lists the providers to the signed-in user too.
  @Test fun sessionProviders() = serve {
    val cookie = "typle-session=" + keys.seal(newSession("ffpf:cabc", "F", "", NOW))
    val body = request("GET", "/api/auth/session", headers = mapOf("Cookie" to cookie)).body()
    assertEquals("{\"user\":{\"id\":\"ffpf:cabc\",\"name\":\"F\",\"image\":null},\"providers\":[\"github\",\"ffpf\"]}\n", body)
  }
}
