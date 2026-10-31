package io.github.sparklingstadt.typle.server.auth

import com.sun.net.httpserver.HttpExchange
import io.github.sparklingstadt.typle.server.json.GoJson
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CLIENT_ID = "typle-r"
private const val CLIENT_SECRET = "the-client-secret"
private const val NONCE = "the-nonce"
private const val REDIRECT_URI = "https://app.example/api/auth/callback/ffpf"

private class TestKey {
  private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  val public = pair.public as ECPublicKey
  val private = pair.private as ECPrivateKey
}

private fun b64(data: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

// The 32 bytes of a coordinate, as a JWK writes it.
private fun coordinate(value: java.math.BigInteger): String {
  val bytes = value.toByteArray().takeLast(32).toByteArray()
  return b64(ByteArray(32 - bytes.size) + bytes)
}

// JSON of maps, lists, strings, numbers and booleans, as the test's tokens and answers are written.
private fun json(value: Any?): String = when (value) {
  null -> "null"
  is String -> GoJson.string(value, escapeHtml = false)
  is Number, is Boolean -> value.toString()
  is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key) + ":" + json(it.value) }
  is List<*> -> value.joinToString(",", "[", "]") { json(it) }
  else -> error("no JSON for $value")
}

// Signs header.claims with key as ES256 does, the signature being r‖s of 32 bytes each.
private fun signJwt(key: TestKey, header: Map<String, Any?>, claims: Map<String, Any?>): String {
  val input = b64(json(header).toByteArray()) + "." + b64(json(claims).toByteArray())
  val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
    initSign(key.private)
    update(input.toByteArray())
    sign()
  }
  return input + "." + b64(signature)
}

private fun signIdToken(key: TestKey, kid: String, claims: Map<String, Any?>) =
  signJwt(key, mapOf("alg" to "ES256", "typ" to "JWT", "kid" to kid), claims)

private fun jwk(key: TestKey, kid: String): MutableMap<String, Any?> = mutableMapOf(
  "kty" to "EC", "crv" to "P-256", "alg" to "ES256", "use" to "sig", "kid" to kid,
  "x" to coordinate(key.public.w.affineX), "y" to coordinate(key.public.w.affineY),
)

private fun form(raw: String): Map<String, String> = raw.split('&').associate {
  URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
}

/**
 * An FFPF ID portal with /oauth/token and /oauth/jwks. The token endpoint answers with idToken; token, when set,
 * answers instead.
 */
private class FakePortal : AutoCloseable {
  @Volatile var idToken = ""
  @Volatile var token: ((HttpExchange) -> Unit)? = null
  @Volatile var jwks: List<Map<String, Any?>> = emptyList()
  @Volatile var jwksFails = false
  val jwksCalls = AtomicInteger()
  val tokenCalls = AtomicInteger()
  var now = System.currentTimeMillis()

  private val server = FakeServer { exchange ->
    when (exchange.requestURI.path) {
      "/oauth/token" -> {
        tokenCalls.incrementAndGet()
        val answer = token
        if (answer != null) {
          answer(exchange)
        } else {
          exchange.responseHeaders.add("Content-Type", "application/json")
          exchange.reply(200, json(mapOf("access_token" to "the-access-token", "token_type" to "Bearer", "expires_in" to 300,
            "id_token" to idToken, "scope" to "openid email profile")))
        }
      }
      "/oauth/jwks" -> {
        jwksCalls.incrementAndGet()
        if (jwksFails) exchange.reply(500) else exchange.reply(200, json(mapOf("keys" to jwks)))
      }
      else -> exchange.reply(404)
    }
  }

  val url = server.url

  // The issuer is given with a trailing slash, which FFPF removes.
  val ffpf = FFPF("$url/", CLIENT_ID, CLIENT_SECRET) { now }

  fun claims(): MutableMap<String, Any?> = mutableMapOf(
    "iss" to url, "aud" to CLIENT_ID, "sub" to "cabc", "exp" to now / 1000 + 300, "iat" to now / 1000,
    "nonce" to NONCE, "email" to "a@example.com", "email_verified" to true, "name" to "ふわ",
  )

  fun exchange() = ffpf.exchange("the-code", "the-verifier", REDIRECT_URI, NONCE)

  override fun close() = server.close()
}

private fun assertNoSecrets(error: Exception) {
  for (secret in listOf(CLIENT_SECRET, "the-code", "the-verifier")) assertFalse(secret in error.message.orEmpty(), error.message)
}

// The cases of server/internal/auth/ffpf_test.go.
class FFPFTest {
  @Test fun authorizeUrl() {
    val raw = FFPF("https://portal.example/", "client-id", "client-secret").authorizeUrl("the state", "the challenge", "the nonce", REDIRECT_URI)
    assertTrue(raw.startsWith("https://portal.example/oauth/authorize?"), raw)
    assertEquals(mapOf(
      "client_id" to "client-id", "redirect_uri" to REDIRECT_URI, "response_type" to "code", "scope" to "openid email profile",
      "state" to "the state", "nonce" to "the nonce", "code_challenge" to "the challenge", "code_challenge_method" to "S256",
    ), form(URI(raw).rawQuery))
    assertFalse("client-secret" in raw)
  }

  @Test fun exchangeSuccess() = FakePortal().use { p ->
    val key = TestKey()
    p.jwks = listOf(jwk(key, "k1"))
    p.idToken = signIdToken(key, "k1", p.claims())
    p.token = { exchange ->
      assertEquals("POST", exchange.requestMethod)
      assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
      assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/x-www-form-urlencoded"))
      val basic = String(Base64.getDecoder().decode(exchange.requestHeaders.getFirst("Authorization").removePrefix("Basic ")))
      assertEquals("$CLIENT_ID:$CLIENT_SECRET", basic)
      assertEquals(mapOf(
        "grant_type" to "authorization_code", "code" to "the-code", "code_verifier" to "the-verifier", "redirect_uri" to REDIRECT_URI,
      ), form(String(exchange.requestBody.readAllBytes())))
      exchange.reply(200, json(mapOf("id_token" to p.idToken, "token_type" to "Bearer")))
    }
    val user = p.exchange()
    assertEquals(FFPFUser("cabc", "a@example.com", "ふわ"), user)
    assertEquals("ffpf:cabc", user.account)
    assertEquals(1 to 1, p.tokenCalls.get() to p.jwksCalls.get())
  }

  // RFC 6749 §2.3.1: the client id and secret are form-encoded before they are put in the Basic credentials.
  @Test fun basicCredentialsAreFormEncoded() = FakePortal().use { p ->
    var credentials = ""
    p.token = { exchange ->
      credentials = String(Base64.getDecoder().decode(exchange.requestHeaders.getFirst("Authorization").removePrefix("Basic ")))
      exchange.reply(400)
    }
    assertFailsWith<FFPFRejectedException> { FFPF(p.url, "a b:c", "x*y~z/").exchange("c", "v", REDIRECT_URI, NONCE) }
    assertEquals("a+b%3Ac:x%2Ay~z%2F", credentials)
  }

  // Accepted variations of a valid ID token.
  @Test fun exchangeAccepts() {
    val user = FFPFUser("cabc", "a@example.com", "ふわ")
    val atSign = FFPFUser("cabc", "a@example.com", "a")
    val cases = listOf<Triple<String, (MutableMap<String, Any?>, Long) -> Unit, FFPFUser>>(
      Triple("exp 59 seconds ago", { c, now -> c["exp"] = now / 1000 - 59 }, user),
      Triple("aud as an array", { c, _ -> c["aud"] = listOf("other", CLIENT_ID) }, user),
      Triple("no name", { c, _ -> c.remove("name") }, atSign),
      Triple("blank name", { c, _ -> c["name"] = "  " }, atSign),
      Triple("typ null", { _, _ -> }, user),
    )
    for ((name, change, want) in cases) {
      FakePortal().use { p ->
        val key = TestKey()
        // The clock is pinned to a whole second, so that "59 seconds ago" is 59 seconds ago however slowly the test runs.
        p.now = p.now / 1000 * 1000
        val claims = p.claims()
        change(claims, p.now)
        p.jwks = listOf(jwk(key, "k1"))
        p.idToken = if (name == "typ null") signJwt(key, mapOf("alg" to "ES256", "typ" to null, "kid" to "k1"), claims) else signIdToken(key, "k1", claims)
        assertEquals(want, p.exchange(), name)
      }
    }
  }

  @Test fun exchangeRejects() {
    val other = TestKey()
    val tokens = mapOf<String, (FakePortal, TestKey) -> String>(
      "other iss" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("iss", p.url + "/other") }) },
      "iss with a trailing slash" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("iss", p.url + "/") }) },
      "other aud" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("aud", "other") }) },
      "aud array without the client" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("aud", listOf("other")) }) },
      "aud array with a number" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("aud", listOf(CLIENT_ID, 1)) }) },
      "exp 61 seconds ago" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("exp", p.now / 1000 - 61) }) },
      "no exp" to { p, key -> signIdToken(key, "k1", p.claims().apply { remove("exp") }) },
      "exp as a string" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("exp", "9999999999") }) },
      "exp past 2^53" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("exp", 10_000_000_000_000_000) }) },
      "other nonce" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("nonce", "other") }) },
      "no nonce" to { p, key -> signIdToken(key, "k1", p.claims().apply { remove("nonce") }) },
      "email_verified false" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("email_verified", false) }) },
      "email_verified as a string" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("email_verified", "true") }) },
      "no email" to { p, key -> signIdToken(key, "k1", p.claims().apply { remove("email") }) },
      "name as a number" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("name", 1) }) },
      "signed with another key" to { p, _ -> signIdToken(other, "k1", p.claims()) },
      "alg none" to { p, key -> signJwt(key, mapOf("alg" to "none", "kid" to "k1"), p.claims()).let { it.substring(0, it.lastIndexOf('.') + 1) } },
      "alg none with a signature" to { p, key -> signJwt(key, mapOf("alg" to "none", "kid" to "k1"), p.claims()) },
      "alg HS256" to { p, key -> signJwt(key, mapOf("alg" to "HS256", "kid" to "k1"), p.claims()) },
      "typ other" to { p, key -> signJwt(key, mapOf("alg" to "ES256", "typ" to "at+jwt", "kid" to "k1"), p.claims()) },
      "no kid" to { p, key -> signJwt(key, mapOf("alg" to "ES256", "typ" to "JWT"), p.claims()) },
      "sub A/B" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("sub", "A/B") }) },
      "sub too long" to { p, key -> signIdToken(key, "k1", p.claims().apply { put("sub", "a".repeat(65)) }) },
      "no sub" to { p, key -> signIdToken(key, "k1", p.claims().apply { remove("sub") }) },
      "four segments" to { p, key -> signIdToken(key, "k1", p.claims()) + ".x" },
      "padded signature" to { p, key -> signIdToken(key, "k1", p.claims()) + "=" },
      "short signature" to { p, key -> signIdToken(key, "k1", p.claims()).let { it.substring(0, it.lastIndexOf('.') + 1) + b64(ByteArray(63)) } },
      "not a JWT" to { _, _ -> "not a jwt" },
      "empty" to { _, _ -> "" },
    )
    for ((name, makeToken) in tokens) {
      FakePortal().use { p ->
        val key = TestKey()
        p.jwks = listOf(jwk(key, "k1"))
        p.idToken = makeToken(p, key)
        assertFailsWith<FFPFRejectedException>(name) { p.exchange() }
      }
    }
    val answers = mapOf<String, (HttpExchange) -> Unit>(
      "token 400" to { it.reply(400, """{"error":"invalid_grant"}""") },
      "token 401" to { it.reply(401, """{"error":"invalid_client"}""") },
      "no id_token" to { it.reply(200, """{"access_token":"a","token_type":"Bearer"}""") },
      "id_token not a string" to { it.reply(200, """{"id_token":1}""") },
      "not JSON" to { it.reply(200, "<html>") },
    )
    for ((name, answer) in answers) {
      FakePortal().use { p ->
        p.token = answer
        assertFailsWith<FFPFRejectedException>(name) { p.exchange() }
      }
    }
  }

  // Only the RFC 6749 error code of a refusal is kept, and only if it looks like one.
  @Test fun rejectionNamesTheErrorCode() {
    for ((body, want) in listOf("""{"error":"invalid_grant"}""" to "answered 400 invalid_grant", """{"error":"Not A Code"}""" to "answered 400")) {
      FakePortal().use { p ->
        p.token = { it.reply(400, body) }
        val error = assertFailsWith<FFPFRejectedException> { p.exchange() }
        assertTrue(error.message.orEmpty().endsWith(want), error.message)
      }
    }
  }

  @Test fun exchangeUnavailable() {
    val setups = mapOf<String, (FakePortal) -> Unit>(
      "token 502" to { p -> p.token = { it.reply(502) } },
      "token 3xx" to { p ->
        p.token = { exchange ->
          exchange.responseHeaders.add("Location", p.url + "/elsewhere")
          exchange.reply(307)
        }
      },
      "jwks 500" to { p -> p.jwksFails = true },
      "jwks not JSON with keys" to { p -> p.jwks = listOf(mapOf("kty" to 1)) },
    )
    for ((name, setup) in setups) {
      FakePortal().use { p ->
        val key = TestKey()
        p.jwks = listOf(jwk(key, "k1"))
        p.idToken = signIdToken(key, "k1", p.claims())
        setup(p)
        assertNoSecrets(assertFailsWith<FFPFUnavailableException>(name) { p.exchange() })
      }
    }
    val p = FakePortal()
    p.close()
    assertNoSecrets(assertFailsWith<FFPFUnavailableException>("server down") { p.exchange() })
  }

  @Test fun refetchesJwksForUnknownKid() = FakePortal().use { p ->
    val first = TestKey()
    val second = TestKey()
    val unknown = TestKey()
    p.jwks = listOf(jwk(first, "k1"))
    p.idToken = signIdToken(first, "k1", p.claims())
    p.exchange()
    // The portal's key changes; the cached JWKS is still fresh, but does not have the new kid.
    p.jwks = listOf(jwk(second, "k2"))
    p.idToken = signIdToken(second, "k2", p.claims())
    assertEquals("cabc", p.exchange().sub)
    assertEquals(2, p.jwksCalls.get())
    // A kid the portal does not have is fetched for once more, and then refused.
    p.idToken = signIdToken(unknown, "k3", p.claims())
    assertFailsWith<FFPFRejectedException> { p.exchange() }
    assertEquals(3, p.jwksCalls.get())
  }

  // The JWKS is cached for 10 minutes and fetched again after.
  @Test fun cachesJwks() = FakePortal().use { p ->
    val key = TestKey()
    p.jwks = listOf(jwk(key, "k1"))
    p.idToken = signIdToken(key, "k1", p.claims())
    repeat(3) { p.exchange() }
    assertEquals(1, p.jwksCalls.get())
    p.now += 10 * 60_000 + 1000
    p.idToken = signIdToken(key, "k1", p.claims())
    p.exchange()
    assertEquals(2, p.jwksCalls.get())
  }

  // Keys that are not P-256 EC keys, or whose point is not on the curve, are ignored.
  @Test fun ignoresUnusableJwks() = FakePortal().use { p ->
    val key = TestKey()
    val offCurve = jwk(key, "k1").apply { put("y", b64(ByteArray(32))) }
    p.jwks = listOf(
      mapOf("kty" to "RSA", "kid" to "k1", "n" to "AQAB", "e" to "AQAB"),
      mapOf("kty" to "EC", "crv" to "P-384", "kid" to "k1", "x" to b64(ByteArray(48)), "y" to b64(ByteArray(48))),
      jwk(key, "k1").apply { put("use", "enc") },
      offCurve,
    )
    p.idToken = signIdToken(key, "k1", p.claims())
    assertFailsWith<FFPFRejectedException> { p.exchange() }
    p.jwks = p.jwks + jwk(key, "k1")
    p.exchange()
  }
}
