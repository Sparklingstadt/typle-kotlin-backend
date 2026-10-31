// Package web is the app's backend as one Ktor module, as the Go backend's server/internal/web: /api/health,
// /api/workspace and /api/auth/*.
package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.GitHubOAuth
import io.github.sparklingstadt.typle.server.auth.GitHubUsers
import io.github.sparklingstadt.typle.server.auth.OAuthClient
import io.github.sparklingstadt.typle.server.auth.UserSource
import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.store.SessionSource
import io.github.sparklingstadt.typle.server.store.Store
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where the app is served (AUTH_URL), reduced to its origin: its scheme picks the cookie's name and its origin is the
 * only one a state-changing request may come from. host is Go's URL.Host: the name or bracketed address, with the
 * port when one is given.
 */
data class BaseUrl(val scheme: String, val host: String) {
  val origin: String get() = "$scheme://$host"

  /** The host without the port or the brackets of an IPv6 address, as Go's URL.Hostname. */
  val hostname: String get() = if (host.startsWith("[")) host.substring(1, host.indexOf(']')) else host.substringBefore(':')

  /** The port when one is given, as Go's URL.Port. */
  val port: String get() = if (host.startsWith("[")) host.substringAfter("]:", "") else host.substringAfter(':', "")
}

/** What the handlers depend on. */
data class Config(
  /** The database; null until it is open (see Backend). */
  val store: Store?,
  /** Opens the session cookie and the apps' tokens. */
  val keys: Keys,
  val baseUrl: BaseUrl,
  /** The clock, in milliseconds. */
  val now: () -> Long = System::currentTimeMillis,
  /** The login: without OAuth and GitHub, signin and callback answer 503. */
  val oauth: OAuthClient?,
  val gitHub: UserSource?,
  /** Turns on POST /api/auth/local, which signs in as a made-up local account for development. Off, the path does not exist. */
  val localLogin: Boolean,
  /**
   * Asked whether a session has been revoked (signed out of every device), and told when one is; when null, store is
   * (Go's Config.Sessions). It lets the /api/auth part, which has no store so that the login never waits for the
   * database, open the database for this one question only (see Backend).
   */
  val sessions: SessionSource? = null,
)

private val logTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

/** Writes a line to standard error, as Go's log package does. */
fun logLine(message: String) {
  System.err.println(LocalDateTime.now().format(logTime) + " " + message)
}

/**
 * Reads the login settings from the environment, as Go's web.FromEnv: AUTH_SECRET and AUTH_URL (an http or https URL,
 * of which only the origin is used) are required, and AUTH_GITHUB_ID and AUTH_GITHUB_SECRET optional. When a required
 * one is missing or unusable the error names each of them. Without both GitHub ones, oauth and gitHub are null, one
 * warning is logged, and only signin and callback answer 503. The store is left to the caller.
 *
 * local is for the typle-web command: only then may localLogin be turned on, under the conditions of
 * localDevelopmentEnabled (src/lib/localDevelopment.ts) except NODE_ENV.
 */
fun fromEnv(getenv: (String) -> String?, local: Boolean, log: (String) -> Unit = ::logLine): Config {
  val problems = mutableListOf<String>()
  val secret = getenv("AUTH_SECRET").orEmpty()
  val url = getenv("AUTH_URL").orEmpty()
  for ((name, value) in listOf("AUTH_SECRET" to secret, "AUTH_URL" to url)) {
    if (value.isEmpty()) problems.add("$name is not set")
  }
  val base = parseBaseUrl(url)
  if (base == null && url.isNotEmpty()) problems.add("AUTH_URL is not an http or https URL")
  if (problems.isNotEmpty() || base == null) throw IllegalStateException("the login settings are incomplete: " + problems.joinToString("; "))
  val id = getenv("AUTH_GITHUB_ID").orEmpty()
  val clientSecret = getenv("AUTH_GITHUB_SECRET").orEmpty()
  val github = id.isNotEmpty() && clientSecret.isNotEmpty()
  if (!github) log("AUTH_GITHUB_ID and AUTH_GITHUB_SECRET are not both set: signing in with GitHub is unavailable")
  return Config(
    store = null, keys = Keys.create(secret), baseUrl = base,
    oauth = if (github) GitHubOAuth(id, clientSecret) else null, gitHub = if (github) GitHubUsers() else null,
    localLogin = local && localLoginAllowed(getenv, base),
  )
}

/** The origin of an http or https URL, or null. */
fun parseBaseUrl(value: String): BaseUrl? {
  val uri = try {
    URI(value)
  } catch (_: URISyntaxException) {
    return null
  }
  val scheme = uri.scheme?.lowercase()
  val host = uri.host
  if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) return null
  return BaseUrl(scheme, if (uri.port >= 0) "$host:${uri.port}" else host)
}

private val ipv4 = Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")

/**
 * localDevelopmentEnabled without the NODE_ENV test: LOCAL_DEVELOPMENT_LOGIN is 1 and AUTH_URL is plain http to this
 * machine: a loopback name or address, or a private IPv4 address that LOCAL_DEVELOPMENT_LAN_HOST names exactly.
 */
private fun localLoginAllowed(getenv: (String) -> String?, base: BaseUrl): Boolean {
  if (getenv("LOCAL_DEVELOPMENT_LOGIN") != "1" || base.scheme != "http") return false
  val host = base.hostname.lowercase()
  if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
  val octets = ipv4.matchEntire(host)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
  if (octets.any { it > 255 }) return false
  val private = octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)
  return private && getenv("LOCAL_DEVELOPMENT_LAN_HOST") == host
}
