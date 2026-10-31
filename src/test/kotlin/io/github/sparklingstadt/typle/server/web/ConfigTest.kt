package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.FFPF
import io.github.sparklingstadt.typle.server.auth.goQueryEscape
import io.github.sparklingstadt.typle.server.session.newSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun validEnv() = mapOf(
  "AUTH_SECRET" to "secret", "AUTH_URL" to "https://typle.example", "AUTH_GITHUB_ID" to "id",
  "AUTH_GITHUB_SECRET" to "client-secret",
)

// The cases of server/internal/web/config_test.go.
class ConfigTest {
  private val logged = mutableListOf<String>()

  private fun fromEnv(values: Map<String, String>, local: Boolean = false): Config = fromEnv(values::get, local) { logged.add(it) }

  @Test fun buildsConfig() {
    val config = fromEnv(validEnv())
    assertEquals("https://typle.example", config.baseUrl.origin)
    assertNotNull(config.oauth)
    assertNotNull(config.gitHub)
    assertNull(config.store)
    assertEquals(false, config.localLogin)
    assertTrue(config.keys.seal(newSession("github:1", "n", "", 0)).isNotEmpty())
    assertEquals(emptyList(), logged)
  }

  @Test fun missingSettingsAreAllTold() {
    for (name in listOf("AUTH_SECRET", "AUTH_URL")) {
      val error = assertFailsWith<IllegalStateException> { fromEnv(validEnv() - name) }
      assertTrue(name in error.message.orEmpty(), error.message)
    }
    val error = assertFailsWith<IllegalStateException> { fromEnv(emptyMap()) }
    assertEquals("the login settings are incomplete: AUTH_SECRET is not set; AUTH_URL is not set", error.message)
  }

  @Test fun gitHubIsOptional() {
    for (missing in listOf(listOf("AUTH_GITHUB_ID"), listOf("AUTH_GITHUB_SECRET"), listOf("AUTH_GITHUB_ID", "AUTH_GITHUB_SECRET"))) {
      logged.clear()
      val config = fromEnv(validEnv() - missing.toSet(), local = true)
      assertNull(config.oauth, missing.toString())
      assertNull(config.gitHub, missing.toString())
      assertEquals(1, logged.count { "AUTH_GITHUB_ID and AUTH_GITHUB_SECRET" in it }, missing.toString())
    }
  }

  @Test fun invalidUrl() {
    for (value in listOf("ftp://x", "typle.example", "https://", "http:///path", "://x", "javascript:alert(1)")) {
      val error = assertFailsWith<IllegalStateException>(value) { fromEnv(validEnv() + ("AUTH_URL" to value)) }
      assertTrue("AUTH_URL" in error.message.orEmpty())
    }
    for (value in listOf("http://localhost:3000", "https://typle.example/app/")) fromEnv(validEnv() + ("AUTH_URL" to value))
  }

  @Test fun keepsOnlyTheOrigin() {
    val cases = mapOf(
      "https://typle.example" to "https://typle.example",
      "https://typle.example/" to "https://typle.example",
      "https://typle.example/app/" to "https://typle.example",
      "https://typle.example/api/auth" to "https://typle.example",
      "https://typle.example:8443/a?b=c#d" to "https://typle.example:8443",
      "http://127.0.0.1:3000/api/auth/?x=1#frag" to "http://127.0.0.1:3000",
      "HTTP://Typle.Example" to "http://Typle.Example",
      "http://[::1]:3000" to "http://[::1]:3000",
    )
    for ((value, want) in cases) assertEquals(want, fromEnv(validEnv() + ("AUTH_URL" to value)).baseUrl.origin, value)
  }

  @Test fun localLogin() {
    val on = "LOCAL_DEVELOPMENT_LOGIN" to "1"
    fun url(value: String) = "AUTH_URL" to value
    fun lan(value: String) = "LOCAL_DEVELOPMENT_LAN_HOST" to value
    val cases = listOf(
      Triple("loopback ip", true, mapOf(on, url("http://127.0.0.1:3000"))) to true,
      Triple("localhost", true, mapOf(on, url("http://localhost:3000"))) to true,
      Triple("ipv6 loopback", true, mapOf(on, url("http://[::1]:3000"))) to true,
      Triple("uppercase localhost", true, mapOf(on, url("http://LOCALHOST:3000"))) to true,
      Triple("not enabled", true, mapOf(url("http://127.0.0.1:3000"))) to false,
      Triple("enabled with 0", true, mapOf("LOCAL_DEVELOPMENT_LOGIN" to "0", url("http://127.0.0.1:3000"))) to false,
      Triple("https", true, mapOf(on, url("https://127.0.0.1:3000"))) to false,
      Triple("public host", true, mapOf(on, url("http://typle.example"))) to false,
      Triple("lan with host", true, mapOf(on, url("http://192.168.1.2:3000"), lan("192.168.1.2"))) to true,
      Triple("lan 10/8", true, mapOf(on, url("http://10.1.2.3:3000"), lan("10.1.2.3"))) to true,
      Triple("lan 172.16/12", true, mapOf(on, url("http://172.31.0.9:3000"), lan("172.31.0.9"))) to true,
      Triple("lan without host", true, mapOf(on, url("http://192.168.1.2:3000"))) to false,
      Triple("lan with another host", true, mapOf(on, url("http://192.168.1.2:3000"), lan("192.168.1.3"))) to false,
      Triple("172.32 is public", true, mapOf(on, url("http://172.32.0.9:3000"), lan("172.32.0.9"))) to false,
      Triple("public ip with host", true, mapOf(on, url("http://8.8.8.8:3000"), lan("8.8.8.8"))) to false,
      Triple("leading zero", true, mapOf(on, url("http://010.1.2.3:3000"), lan("010.1.2.3"))) to false,
      Triple("not local", false, mapOf(on, url("http://127.0.0.1:3000"))) to false,
    )
    for ((case, want) in cases) {
      val (name, local, set) = case
      assertEquals(want, fromEnv(validEnv() + set, local).localLogin, name)
    }
  }

  // FFPF ID is on with all three of its settings, and the issuer's trailing slash is dropped. With one or two of them
  // it is off, with one warning and no error; with none it is off without a word, as it is simply not used.
  @Test fun ffpf() {
    val values = validEnv() + mapOf(
      "FFPF_ID_ISSUER" to "https://portal.example/", "FFPF_ID_CLIENT_ID" to "typle", "FFPF_ID_CLIENT_SECRET" to "ffpf-secret",
    )
    val ffpf = fromEnv(values).ffpf as FFPF
    assertEquals(listOf("https://portal.example", "typle", "ffpf-secret"), listOf(ffpf.issuer, ffpf.clientId, ffpf.clientSecret))
    assertEquals(emptyList(), logged)
    // The redirect_uri FFPF ID is given is the callback at AUTH_URL's origin.
    val location = TestServer { typleWeb(fromEnv(values)) }.use { it.request("GET", "/api/auth/signin?provider=ffpf") }.location()
    assertTrue(location.startsWith("https://portal.example/oauth/authorize?"), location)
    assertTrue("redirect_uri=" + goQueryEscape("https://typle.example/api/auth/callback/ffpf") in location, location)

    for (missing in listOf(listOf("FFPF_ID_ISSUER"), listOf("FFPF_ID_CLIENT_ID"), listOf("FFPF_ID_CLIENT_SECRET"),
      listOf("FFPF_ID_ISSUER", "FFPF_ID_CLIENT_SECRET"), listOf("FFPF_ID_ISSUER", "FFPF_ID_CLIENT_ID", "FFPF_ID_CLIENT_SECRET"))) {
      logged.clear()
      assertNull(fromEnv(values - missing.toSet()).ffpf, missing.toString())
      assertEquals(if (missing.size == 3) 0 else 1, logged.count { "FFPF_ID_ISSUER, FFPF_ID_CLIENT_ID and FFPF_ID_CLIENT_SECRET" in it }, missing.toString())
    }
  }
}
