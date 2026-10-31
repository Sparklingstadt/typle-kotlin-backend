// Package session signs and checks the cookies and tokens of the backend, exactly as the Go backend's
// server/internal/session does: the same keys from AUTH_SECRET and the same bytes, so that either backend opens what
// the other sealed.
package io.github.sparklingstadt.typle.server.session

object SessionTimes {
  /**
   * How long a session is valid after its login, in seconds. It is not renewed, however active the user is, so a cookie
   * or an app token that is stolen stops working at most this long after the login it came from (typle-r v0.107.0).
   */
  const val MAX_AGE = 30L * 24 * 3600

  /** How long a login may take between leaving for GitHub and coming back, in seconds. */
  const val OAUTH_MAX_AGE = 600L

  /** How long an app has to trade the code of its login for a token, in seconds. */
  const val APP_CODE_MAX_AGE = 120L
}

/** The name of the OAuth state cookie. */
const val OAUTH_COOKIE = "typle-oauth"

/** A signed-in user. Times are in whole seconds since the epoch. */
data class Session(val sub: String, val name: String, val image: String, val issuedAt: Long, val expires: Long)

/** One login in progress; challenge is an app's PKCE challenge, and empty for the web app's login. */
data class OAuthState(val state: String, val verifier: String, val returnTo: String, val challenge: String, val expires: Long)

/** The end of a login an app started: who signed in, for the app that holds the verifier of challenge. */
data class AppCode(val sub: String, val name: String, val image: String, val challenge: String, val expires: Long)

/** Starts a session at nowMillis, truncated to the second. */
fun newSession(sub: String, name: String, image: String, nowMillis: Long): Session {
  val now = Math.floorDiv(nowMillis, 1000L)
  return Session(sub, name, image, now, now + SessionTimes.MAX_AGE)
}

/**
 * Whether the account's sessions revoked at revokedAt (Store.revokeSessions) include this one, as Go's RevokedBy: those
 * of a login no later than revokedAt. issuedAt is in whole seconds, so a login in the very second of the revocation
 * counts as revoked too; that errs on the side of signing someone out.
 */
fun Session.revokedBy(revokedAt: java.time.Instant?): Boolean =
  revokedAt != null && !java.time.Instant.ofEpochSecond(issuedAt).isAfter(revokedAt)

/**
 * __Host- over HTTPS, which makes the browser insist on Secure, Path=/ and no Domain; plain HTTP (local development)
 * cannot set such a cookie.
 */
fun cookieName(secure: Boolean): String = if (secure) "__Host-typle-session" else "typle-session"
