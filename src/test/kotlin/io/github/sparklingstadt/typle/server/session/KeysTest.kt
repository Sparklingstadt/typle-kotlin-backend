package io.github.sparklingstadt.typle.server.session

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_SECRET = "typle-e2e-only-not-a-production-secret-32-bytes"

// The Go backend's values for the same input (server/internal/session): what one seals the other opens.
private const val VECTOR =
  "eyJzdWIiOiJnaXRodWI6MSIsIm5hbWUiOiJFMkUgVXNlciIsImltYWdlIjoiIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDI1OTIwMDB9.PnYQedkDC_8_wH2YdnWBCStw6T-q-WuuzlkmghaPRro"
private const val ESCAPED_VECTOR =
  "eyJzdWIiOiJnaXRodWI6MiIsIm5hbWUiOiJBICYgQiA8eD5cdTIwMjgiLCJpbWFnZSI6IiIsImlhdCI6MTcwMDAwMDAwMCwiZXhwIjoxNzAyNTkyMDAwfQ.kC3xQIvDOm0YGs1xA85LEKb4SB8PCoSntK11CucuLmk"
private const val OAUTH_VECTOR =
  "eyJzdGF0ZSI6InMiLCJ2ZXJpZmllciI6InYiLCJyZXR1cm5UbyI6Ii9ob21lIiwiZXhwIjoxNzAwMDAwNjAwfQ.6ju87OdkDBOifagAW_q10v6flKnDD39AR08b33htprg"
private const val OAUTH_CHALLENGE_VECTOR =
  "eyJzdGF0ZSI6InMiLCJ2ZXJpZmllciI6InYiLCJyZXR1cm5UbyI6Ii9ob21lIiwiZXhwIjoxNzAwMDAwNjAwLCJjaGFsbGVuZ2UiOiJjIn0.zsmyQgwXC0Q_AfS2diOB5P531kwToEf21FR_YLmt3vA"
private const val APP_VECTOR =
  "eyJzdWIiOiJnaXRodWI6MSIsIm5hbWUiOiJFMkUgVXNlciIsImltYWdlIjoiIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDI1OTIwMDB9.KWAItw-d-babzudTjSqV_IfxtRU4LxoYTzdeJWZI5bo"
private const val CODE_VECTOR =
  "eyJzdWIiOiJnaXRodWI6MSIsIm5hbWUiOiJFMkUgVXNlciIsImltYWdlIjoiIiwiY2hhbGxlbmdlIjoiYyIsImV4cCI6MTcwMDAwMDEyMH0.S4rpIFLsgPstmeF53Xta9sCxQRoXAeRWHXhDzWkp3gA"

private const val NOW = 1700000000_000L
private val E2E_USER = Session("github:1", "E2E User", "", 1700000000, 1702592000)

class KeysTest {
  private val keys = Keys.create(TEST_SECRET)

  @Test fun emptySecretIsRefused() {
    assertFailsWith<IllegalArgumentException> { Keys.create("") }
  }

  @Test fun sealOpen() {
    val want = newSession("github:42", "Ada", "https://example.com/a.png", NOW + 999)
    assertEquals(1700000000, want.issuedAt)
    assertEquals(1700000000 + SessionTimes.MAX_AGE, want.expires)
    assertEquals(want, keys.open(keys.seal(want), NOW))
  }

  @Test fun sealsAsGo() {
    assertEquals(VECTOR, keys.seal(E2E_USER))
    assertEquals(E2E_USER, keys.open(VECTOR, NOW))
    assertEquals(APP_VECTOR, keys.sealApp(E2E_USER))
    assertEquals(E2E_USER, keys.openApp(APP_VECTOR, NOW))
    val oauth = OAuthState("s", "v", "/home", "", 1700000600)
    assertEquals(OAUTH_VECTOR, keys.sealOAuth(oauth))
    assertEquals(oauth, keys.openOAuth(OAUTH_VECTOR, NOW))
    val withChallenge = oauth.copy(challenge = "c")
    assertEquals(OAUTH_CHALLENGE_VECTOR, keys.sealOAuth(withChallenge))
    assertEquals(withChallenge, keys.openOAuth(OAUTH_CHALLENGE_VECTOR, NOW))
    val code = AppCode("github:1", "E2E User", "", "c", 1700000120)
    assertEquals(CODE_VECTOR, keys.sealCode(code))
    assertEquals(code, keys.openCode(CODE_VECTOR, NOW))
  }

  @Test fun escapedVector() {
    val s = Session("github:2", "A & B <x> ", "", 1700000000, 1702592000)
    assertEquals(ESCAPED_VECTOR, keys.seal(s))
    assertEquals(s, keys.open(ESCAPED_VECTOR, NOW))
  }

  // A cookie whose JSON spells & < > as & < > still opens: the signature is over the payload as received.
  @Test fun opensHtmlEscapedCookie() {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
      """{"sub":"github:2","name":"A \u0026 B \u003cx\u003e","image":"","iat":1700000000,"exp":1702592000}""".toByteArray())
    val cookie = payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(keys.signSession(payload))
    assertEquals("A & B <x>", keys.open(cookie, NOW)?.name)
  }

  @Test fun openRefuses() {
    val good = keys.seal(newSession("github:42", "Ada", "", NOW))
    val other = keys.seal(newSession("github:43", "Eve", "", NOW))
    val (payload, signature) = good.split(".")
    val at = good.indexOf('.') + 5
    val flipped = good.substring(0, at) + (if (good[at] == 'A') 'B' else 'A') + good.substring(at + 1)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val nonCanonical = signature.dropLast(1) + alphabet[alphabet.indexOf(signature.last()) xor 1]
    val now = NOW / 1000
    val cases = mapOf(
      "padded signature" to "$good=",
      "truncated by one" to good.dropLast(1),
      "truncated by four" to good.dropLast(4),
      "non-canonical tail" to "$payload.$nonCanonical",
      "extra dot" to "$good.c",
      "signature changed" to flipped,
      "payload swapped" to other.split(".")[0] + "." + signature,
      "expired" to keys.seal(Session("github:42", "Ada", "", now - 7200, now - 3600)),
      "expires now" to keys.seal(Session("github:42", "Ada", "", now - 3600, now)),
      "sub not numeric" to keys.seal(Session("github:abc", "Ada", "", now, now + 3600)),
      "sub other prefix" to keys.seal(Session("gitlab:42", "Ada", "", now, now + 3600)),
      "sub with newline" to keys.seal(Session("github:42\n", "Ada", "", now, now + 3600)),
      "sub with full-width digit" to keys.seal(Session("github:４２", "Ada", "", now, now + 3600)),
      "no dot" to good.replace(".", ""),
      "empty" to "",
      "only dot" to ".",
    )
    for ((name, value) in cases) assertNull(keys.open(value, NOW), name)
    assertNull(Keys.create("another-secret").open(good, NOW), "another secret")
    assertNotNull(keys.open(good, NOW), "the untouched cookie must still open")
  }

  @Test fun oauthKeyIsSeparate() {
    val o = OAuthState("s", "v", "/en/", "", NOW / 1000 + SessionTimes.OAUTH_MAX_AGE)
    val value = keys.sealOAuth(o)
    assertNull(keys.open(value, NOW))
    assertNull(keys.openOAuth(keys.seal(newSession("github:1", "A", "", NOW)), NOW))
    assertEquals(o, keys.openOAuth(value, NOW))
    assertNull(keys.openOAuth(value, o.expires * 1000), "expired")
    assertNull(keys.openOAuth("", NOW))
  }

  @Test fun webOAuthCookieHasNoChallenge() {
    val payload = keys.sealOAuth(OAuthState("s", "v", "/", "", 1700000600)).substringBefore('.')
    assertEquals("""{"state":"s","verifier":"v","returnTo":"/","exp":1700000600}""",
      String(Base64.getUrlDecoder().decode(payload)))
  }

  @Test fun appKeysAreSeparate() {
    val s = newSession("github:42", "Ada", "https://example.com/a.png", NOW)
    val c = AppCode("github:42", "Ada", "", "challenge", NOW / 1000 + SessionTimes.APP_CODE_MAX_AGE)
    val token = keys.sealApp(s)
    val code = keys.sealCode(c)
    val cookie = keys.seal(s)
    val oauth = keys.sealOAuth(OAuthState("s", "v", "/", "", NOW / 1000 + SessionTimes.OAUTH_MAX_AGE))
    assertEquals(s, keys.openApp(token, NOW))
    assertEquals(c, keys.openCode(code, NOW))
    for (value in listOf(code, cookie, oauth)) assertNull(keys.openApp(value, NOW))
    for (value in listOf(token, cookie, oauth)) assertNull(keys.openCode(value, NOW))
    for (value in listOf(token, code)) {
      assertNull(keys.open(value, NOW))
      assertNull(keys.openOAuth(value, NOW))
    }
  }

  @Test fun openCodeRefuses() {
    val good = AppCode("github:42", "", "", "challenge", NOW / 1000 + SessionTimes.APP_CODE_MAX_AGE)
    assertNull(keys.openCode(keys.sealCode(good), good.expires * 1000), "expired")
    assertNull(keys.openCode(keys.sealCode(good.copy(challenge = "")), NOW), "no challenge")
    assertNull(keys.openCode(keys.sealCode(good.copy(sub = "local")), NOW), "no GitHub account")
    val expired = newSession("github:42", "Ada", "", NOW - SessionTimes.MAX_AGE * 1000)
    assertNull(keys.openApp(keys.sealApp(expired), NOW), "expired token")
  }

  @Test fun wrongTypesDoNotOpen() {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
      """{"sub":"github:1","name":1,"image":"","iat":1700000000,"exp":1702592000}""".toByteArray())
    val cookie = payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(keys.signSession(payload))
    assertNull(keys.open(cookie, NOW))
  }

  // Go's TestRevokedBy: the sessions of a login no later than the revocation, the very second included.
  @Test fun revokedBy() {
    val issued = java.time.Instant.ofEpochSecond(1700000000)
    val s = newSession("github:1", "A", "", issued.toEpochMilli() + 400)
    assertFalse(s.revokedBy(null), "never revoked")
    assertFalse(s.revokedBy(issued.minusSeconds(1)), "revoked before the login")
    assertTrue(s.revokedBy(issued.plusMillis(900)), "revoked in the second of the login")
    assertTrue(s.revokedBy(issued.plusSeconds(3600)), "revoked after the login")
  }

  // Go's TestSessionAbsoluteLifetime: a session is valid MAX_AGE after its login and no longer, as nothing extends it.
  @Test fun absoluteLifetime() {
    val issued = 1700000000_000L
    val value = keys.seal(newSession("github:1", "A", "", issued))
    assertNotNull(keys.open(value, issued + (SessionTimes.MAX_AGE - 1) * 1000))
    assertNull(keys.open(value, issued + SessionTimes.MAX_AGE * 1000))
  }

  @Test fun cookieNames() {
    assertEquals("__Host-typle-session", cookieName(true))
    assertEquals("typle-session", cookieName(false))
    assertEquals("typle-oauth", OAUTH_COOKIE)
  }
}
