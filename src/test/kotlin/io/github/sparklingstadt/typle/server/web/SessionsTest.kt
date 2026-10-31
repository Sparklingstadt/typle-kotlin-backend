package io.github.sparklingstadt.typle.server.web

import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Signing out of every device (typle-r v0.107.0), as Go's revocations_test.go.
class SessionsTest {
  private val store = FakeStore(saved = io.github.sparklingstadt.typle.server.store.Saved("[]", "[]", null, 1))

  private fun <T> serve(block: TestServer.() -> T): T = TestServer { typleWeb(testConfig(store)) }.use { it.block() }

  private fun HttpResponse<String>.removesCookie() =
    headers().allValues("Set-Cookie").any { it.startsWith("typle-session=;") && "Max-Age=0" in it }

  @Test fun signingOutEverywhereRevokesEarlierSessions() = serve {
    val cookie = sessionCookie(TEST_OWNER, nowMillis = System.currentTimeMillis() - 5000)
    val token = appToken(TEST_OWNER, System.currentTimeMillis() - 5000)
    assertEquals(200, request("GET", "/api/workspace", headers = mapOf("Cookie" to cookie)).statusCode())

    val out = request("POST", "/api/auth/signout?everywhere=1", headers = mapOf("Origin" to TEST_BASE, "Cookie" to cookie))
    assertEquals(204, out.statusCode(), out.body())
    assertTrue(out.removesCookie())
    assertNotNull(store.revokedAt)

    // The cookie and the token of the earlier login reach no data, and the session says no one is signed in.
    val refused = request("GET", "/api/workspace", headers = mapOf("Cookie" to cookie))
    expectError(refused, 401, "loginRequired")
    assertTrue(refused.removesCookie())
    expectError(request("GET", "/api/workspace", headers = mapOf("Authorization" to "Bearer $token")), 401, "loginRequired")
    expectError(request("GET", "/api/shares", headers = mapOf("Cookie" to cookie)), 401, "loginRequired")
    val session = request("GET", "/api/auth/session", headers = mapOf("Cookie" to cookie))
    assertEquals("{\"user\":null,\"providers\":[\"github\"]}\n", session.body())
    assertTrue(session.removesCookie())
    // A revoked session cannot sign out everywhere again, so the logins made since stay.
    val revokedAt = store.revokedAt
    expectError(request("POST", "/api/auth/signout?everywhere=1", headers = mapOf("Origin" to TEST_BASE, "Cookie" to cookie)), 401, "loginRequired")
    assertEquals(revokedAt, store.revokedAt)
    // A login after the revocation is let in.
    val later = sessionCookie(TEST_OWNER, nowMillis = System.currentTimeMillis() + 2000)
    assertEquals(200, request("GET", "/api/workspace", headers = mapOf("Cookie" to later)).statusCode())
  }

  // An app may sign out everywhere with its token and no Origin; it gets no cookie to remove.
  @Test fun appsSignOutEverywhere() = serve {
    val token = appToken(TEST_OWNER)
    expectError(request("POST", "/api/auth/signout", headers = mapOf("Authorization" to "Bearer $token")), 403, "forbiddenRequest")
    val out = request("POST", "/api/auth/signout?everywhere=1", headers = mapOf("Authorization" to "Bearer $token"))
    assertEquals(204, out.statusCode())
    assertTrue(out.headers().allValues("Set-Cookie").isEmpty())
    assertTrue(store.revokedAt != null)
  }

  @Test fun signingOutEverywhereNeedsTheDatabase() = serve {
    expectError(request("POST", "/api/auth/signout?everywhere=1", headers = mapOf("Origin" to TEST_BASE)), 401, "loginRequired")
    store.revocationFailure = IllegalStateException("down")
    val cookie = sessionCookie(TEST_OWNER)
    val failed = request("POST", "/api/auth/signout?everywhere=1", headers = mapOf("Origin" to TEST_BASE, "Cookie" to cookie))
    expectError(failed, 503, "databaseUnavailable")
    assertTrue(failed.headers().allValues("Set-Cookie").isEmpty(), "the cookie is kept to try again")
    // The data is out of reach while the revocations cannot be read; the session's answer goes on unchecked.
    expectError(request("GET", "/api/workspace", headers = mapOf("Cookie" to cookie)), 503, "databaseUnavailable")
    assertTrue("\"id\":\"$TEST_OWNER\"" in request("GET", "/api/auth/session", headers = mapOf("Cookie" to cookie)).body())
    assertNull(store.revokedAt)
  }

  // A login in the very second of the revocation counts as revoked.
  @Test fun theSecondOfTheRevocationIsRevoked() = serve {
    val now = System.currentTimeMillis()
    store.revokedAt = Instant.ofEpochMilli(now)
    expectError(request("GET", "/api/workspace", headers = mapOf("Cookie" to sessionCookie(TEST_OWNER, nowMillis = now))), 401, "loginRequired")
  }
}
