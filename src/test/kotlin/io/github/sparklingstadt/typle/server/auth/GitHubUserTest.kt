package io.github.sparklingstadt.typle.server.auth

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The User cases of server/internal/auth/github_test.go.
class GitHubUserTest {
  @Test fun userFields() {
    FakeServer { exchange ->
      assertEquals("GET", exchange.requestMethod)
      assertEquals("/user", exchange.requestURI.path)
      assertEquals("Bearer gho_abc", exchange.requestHeaders.getFirst("Authorization"))
      assertEquals("application/vnd.github+json", exchange.requestHeaders.getFirst("Accept"))
      assertEquals("2022-11-28", exchange.requestHeaders.getFirst("X-GitHub-Api-Version"))
      exchange.reply(200, """{"id":1,"login":"octo","name":"","avatar_url":"https://a"}""")
    }.use { server ->
      val user = GitHubUsers(server.url).user("gho_abc")
      assertEquals(GitHubAccount(1, "octo", "", "https://a"), user)
      assertEquals("octo", user.displayName)
      assertEquals("github:1", user.account)
    }
  }

  @Test fun nullFields() {
    FakeServer { it.reply(200, """{"id":2,"login":"octo","name":null,"avatar_url":null}""") }.use { server ->
      assertEquals(GitHubAccount(2, "octo", "", ""), GitHubUsers(server.url).user("gho_abc"))
    }
  }

  @Test fun displayName() {
    assertEquals("Octo Cat", GitHubAccount(1, "octo", "Octo Cat", "").displayName)
    assertEquals("octo", GitHubAccount(1, "octo", "", "").displayName)
    assertEquals("octo", GitHubAccount(1, "octo", " \t\n", "").displayName)
  }

  @Test fun anyTokenIsAsked() {
    FakeServer { exchange ->
      if (exchange.requestHeaders.getFirst("Authorization") == "Bearer gho_good") {
        exchange.reply(200, """{"id":7,"login":"octo"}""")
      } else {
        exchange.reply(401)
      }
    }.use { server ->
      val users = GitHubUsers(server.url)
      repeat(3) { assertEquals("github:7", users.user("gho_good").account) }
      assertEquals(3, server.calls.get())
      assertFailsWith<IOException> { users.user("gho_bad") }
    }
  }

  @Test fun unavailable() {
    val answers = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> Unit>(
      "rate limited" to { it.reply(403) },
      "server error" to { it.reply(502) },
      "no id" to { it.reply(200, """{"login":"x"}""") },
      "id zero" to { it.reply(200, """{"id":0,"login":"x"}""") },
      "id a string" to { it.reply(200, """{"id":"1","login":"x"}""") },
      "not JSON" to { it.reply(200, "<html>") },
    )
    for ((name, answer) in answers) {
      FakeServer(answer).use { server -> assertFailsWith<IOException>(name) { GitHubUsers(server.url).user("gho_good") } }
    }
    assertFailsWith<IOException> { GitHubUsers("http://127.0.0.1:1").user("gho_good") }
  }
}
