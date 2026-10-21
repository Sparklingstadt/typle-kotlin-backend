package io.github.sparklingstadt.typle.server.auth

import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** A GitHub account, as GET /user describes it. */
data class GitHubAccount(val id: Long, val login: String, val name: String, val avatarUrl: String) {
  /** The id the app's accounts go by, github:<numeric user id>. */
  val account: String get() = "github:$id"

  /** The name to show: the profile name if it says anything, else the login. */
  val displayName: String get() = if (goTrimSpace(name).isEmpty()) login else name
}

// Go's unicode.IsSpace: White_Space, which is JavaScript's trim set without U+FEFF and with U+0085.
private fun isGoSpace(c: Char) = when (c) {
  '\t', '\n', '\u000b', '\u000c', '\r', ' ', '\u0085', '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f',
  '\u3000' -> true
  else -> c in '\u2000'..'\u200a'
}

/** Go's strings.TrimSpace. */
fun goTrimSpace(value: String): String = value.trim(::isGoSpace)

/** Tells who an OAuth access token belongs to (GitHub.User in Go). */
interface UserSource {
  /** Throws IOException when GitHub refuses the token or cannot be asked. */
  fun user(token: String): GitHubAccount
}

/** Reads who a token belongs to once, right after the login's exchange; the token is not kept. */
class GitHubUsers(private val baseUrl: String = "https://api.github.com") : UserSource {
  override fun user(token: String): GitHubAccount {
    val request = HttpRequest.newBuilder(URI("$baseUrl/user")).timeout(Duration.ofSeconds(10))
      .header("Authorization", "Bearer $token")
      .header("Accept", "application/vnd.github+json")
      .header("X-GitHub-Api-Version", "2022-11-28")
      .GET()
      .build()
    val response = send(request, "GitHub user request")
    when (response.statusCode()) {
      200 -> {}
      401 -> throw IOException("GitHub does not accept the token")
      // 403 and 429 are rate limits; 5xx is GitHub's own trouble.
      else -> throw IOException("GitHub answered ${response.statusCode()}")
    }
    // name and avatar_url may be null, which reads as empty.
    val body = readObject(response)
    val id = when (val value = body?.members?.get("id")) {
      is JsonNumber -> value.literal.toLongOrNull()
      null, JsonNull -> 0L
      else -> null
    }
    val login = body?.let { stringField(it, "login") }
    val name = body?.let { stringField(it, "name") }
    val avatar = body?.let { stringField(it, "avatar_url") }
    if (id == null || id <= 0 || login == null || name == null || avatar == null) {
      throw IOException("GitHub's answer has no user id")
    }
    return GitHubAccount(id, login, name, avatar)
  }
}
