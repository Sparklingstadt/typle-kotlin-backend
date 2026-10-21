// Package auth is the backend's side of GitHub sign-in, as the browser login of the Go backend's server/internal/auth
// (OAuth and GitHub.User): the GitHub OAuth App the login goes through, and who the token it gives belongs to.
package io.github.sparklingstadt.typle.server.auth

import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue
import java.io.IOException
import java.net.ProxySelector
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val MAX_BODY = 1 shl 20
private val TIMEOUT: Duration = Duration.ofSeconds(10)

// A redirect is never followed: the token request carries the client secret, and must not be replayed to the target of
// a 3xx. The 3xx is then an answer that is not 2xx, and an error.
internal val client: HttpClient = HttpClient.newBuilder()
  .connectTimeout(TIMEOUT)
  .followRedirects(HttpClient.Redirect.NEVER)
  .proxy(ProxySelector.getDefault())
  .build()

/** The GitHub OAuth App the login goes through (auth.OAuth in Go). */
interface OAuthClient {
  /** Where to send the browser to sign in; challenge is the S256 code challenge of the verifier later given to exchange. */
  fun authorizeUrl(state: String, challenge: String, redirectUri: String): String

  /** Trades the code GitHub sent back to the callback for the user's access token. Throws IOException otherwise. */
  fun exchange(code: String, verifier: String, redirectUri: String): String
}

/**
 * The exchange uses PKCE (RFC 7636), and the client secret is sent only to tokenUrl, never in a URL the browser sees or
 * an error message. No scope is asked for: the app only needs to know who the user is.
 */
class GitHubOAuth(
  private val clientId: String,
  private val clientSecret: String,
  private val authorizeEndpoint: String = "https://github.com/login/oauth/authorize",
  private val tokenUrl: String = "https://github.com/login/oauth/access_token",
) : OAuthClient {
  override fun authorizeUrl(state: String, challenge: String, redirectUri: String): String =
    authorizeEndpoint + "?" + form(sortedMapOf(
      "client_id" to clientId, "redirect_uri" to redirectUri, "state" to state, "code_challenge" to challenge,
      "code_challenge_method" to "S256",
    ))

  // Errors never include the client secret, the code or the verifier.
  override fun exchange(code: String, verifier: String, redirectUri: String): String {
    val body = form(sortedMapOf(
      "client_id" to clientId, "client_secret" to clientSecret, "code" to code, "code_verifier" to verifier,
      "redirect_uri" to redirectUri,
    ))
    val request = HttpRequest.newBuilder(java.net.URI(tokenUrl)).timeout(TIMEOUT)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = send(request, "GitHub token request")
    if (response.statusCode() !in 200..299) throw IOException("GitHub token request answered ${response.statusCode()}")
    // GitHub answers 200 even when it refuses the code, with the reason in error.
    val answer = readObject(response) ?: throw IOException("GitHub token answer is not JSON")
    val error = stringField(answer, "error") ?: throw IOException("GitHub token answer is not JSON")
    val token = stringField(answer, "access_token") ?: throw IOException("GitHub token answer is not JSON")
    if (error.isNotEmpty()) throw IOException("GitHub refused the code: $error")
    if (token.isEmpty()) throw IOException("GitHub token answer has no access token")
    return token
  }
}

private fun form(values: Map<String, String>): String =
  values.entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }

// Only the cause's kind and message are kept, which name the address at most, never the request's body or headers.
internal fun send(request: HttpRequest, what: String): HttpResponse<java.io.InputStream> = try {
  client.send(request, HttpResponse.BodyHandlers.ofInputStream())
} catch (e: IOException) {
  throw IOException("$what failed: ${e.javaClass.simpleName}")
} catch (e: InterruptedException) {
  Thread.currentThread().interrupt()
  throw IOException("$what was interrupted")
}

/** The answer as a JSON object, reading at most 1 MiB of it, or null. */
internal fun readObject(response: HttpResponse<java.io.InputStream>): JsonObject? = response.body().use { body ->
  try {
    JsonValue.parse(String(body.readNBytes(MAX_BODY), Charsets.UTF_8)) as? JsonObject
  } catch (_: JsonSyntaxException) {
    null
  }
}

// Go's json.Unmarshal into a string field: a string, or "" when it is left out or null; null for any other type.
internal fun stringField(fields: JsonObject, name: String): String? = when (val value = fields.members[name]) {
  null, JsonNull -> ""
  is JsonString -> value.value
  else -> null
}
