package io.github.sparklingstadt.typle.server.auth

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun query(raw: String): Map<String, String> = raw.split('&').associate {
  URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
}

// The cases of server/internal/auth/oauth_test.go.
class OAuthTest {
  @Test fun authorizeUrl() {
    val raw = GitHubOAuth("client-id", "client-secret")
      .authorizeUrl("the state", "the challenge", "https://app.example/api/auth/callback/github")
    assertTrue(raw.startsWith("https://github.com/login/oauth/authorize?"), raw)
    assertEquals(mapOf(
      "client_id" to "client-id",
      "redirect_uri" to "https://app.example/api/auth/callback/github",
      "state" to "the state",
      "code_challenge" to "the challenge",
      "code_challenge_method" to "S256",
    ), query(URI(raw).rawQuery))
    assertFalse("client-secret" in raw)
  }

  @Test fun exchange() {
    FakeServer { exchange ->
      assertEquals("POST", exchange.requestMethod)
      assertEquals("/login/oauth/access_token", exchange.requestURI.path)
      assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
      assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/x-www-form-urlencoded"))
      assertEquals(mapOf(
        "client_id" to "client-id", "client_secret" to "client-secret", "code" to "the code",
        "code_verifier" to "the verifier", "redirect_uri" to "https://app.example/cb",
      ), query(String(exchange.requestBody.readAllBytes())))
      exchange.reply(200, """{"access_token":"gho_token","token_type":"bearer","scope":""}""")
    }.use { server ->
      val oauth = GitHubOAuth("client-id", "client-secret", tokenUrl = server.url + "/login/oauth/access_token")
      assertEquals("gho_token", oauth.exchange("the code", "the verifier", "https://app.example/cb"))
      assertEquals(1, server.calls.get())
    }
  }

  @Test fun exchangeFailures() {
    val answers = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> Unit>(
      "error with 200" to { it.reply(200, """{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}""") },
      "error and token" to { it.reply(200, """{"error":"x","access_token":"gho_token"}""") },
      "no token" to { it.reply(200, """{"token_type":"bearer"}""") },
      "empty token" to { it.reply(200, """{"access_token":""}""") },
      "token a number" to { it.reply(200, """{"access_token":1}""") },
      "server error" to { it.reply(500) },
      "error with 400" to { it.reply(400, """{"error":"bad_verification_code"}""") },
      "not JSON" to { it.reply(200, "<html>") },
      "body past the limit" to { it.reply(200, " ".repeat((1 shl 20) + 1) + """{"access_token":"gho_token"}""") },
    )
    for ((name, answer) in answers) {
      FakeServer(answer).use { server ->
        val oauth = GitHubOAuth("client-id", "client-secret", tokenUrl = server.url + "/t")
        val error = assertFailsWith<IOException>(name) { oauth.exchange("secret-code", "secret-verifier", "https://app.example/cb") }
        for (secret in listOf("client-secret", "secret-code", "secret-verifier")) {
          assertFalse(secret in error.message.orEmpty(), "$name: ${error.message}")
        }
      }
    }
  }

  @Test fun exchangeErrorNamesGitHubsError() {
    FakeServer { it.reply(200, """{"error":"bad_verification_code"}""") }.use { server ->
      val error = assertFailsWith<IOException> { GitHubOAuth("i", "s", tokenUrl = server.url).exchange("c", "v", "r") }
      assertTrue("bad_verification_code" in error.message.orEmpty())
    }
  }

  @Test fun exchangeUnreachable() {
    val error = assertFailsWith<IOException> {
      GitHubOAuth("client-id", "client-secret", tokenUrl = "http://127.0.0.1:1/t").exchange("secret-code", "v", "r")
    }
    assertFalse("client-secret" in error.message.orEmpty() || "secret-code" in error.message.orEmpty())
  }

  // The POST carries the client secret, and must not be replayed to the target of a redirect.
  @Test fun exchangeDoesNotFollowRedirects() {
    for (status in listOf(301, 302, 307, 308)) {
      FakeServer { it.reply(200, """{"access_token":"gho_stolen"}""") }.use { other ->
        FakeServer { exchange ->
          exchange.responseHeaders.add("Location", other.url + "/steal")
          exchange.reply(status)
        }.use { server ->
          val error = assertFailsWith<IOException> { GitHubOAuth("i", "client-secret", tokenUrl = server.url).exchange("c", "v", "r") }
          assertTrue("answered" in error.message.orEmpty(), error.message)
          assertEquals(0, other.calls.get())
          assertEquals(1, server.calls.get())
        }
      }
    }
  }
}
