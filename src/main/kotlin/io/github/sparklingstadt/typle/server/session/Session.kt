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

  /** How long a login may take between leaving for GitHub or FFPF ID and coming back, in seconds. */
  const val OAUTH_MAX_AGE = 600L

  /** How long an app has to trade the code of its login for a token, in seconds. */
  const val APP_CODE_MAX_AGE = 120L

  /**
   * How long an administrator's session of /admin is valid, in seconds. It is not renewed: the administrator signs in
   * again with FFPF ID.
   */
  const val ADMIN_MAX_AGE = 12L * 3600
}

/** The name of the OAuth state cookie. */
const val OAUTH_COOKIE = "typle-oauth"

/** A signed-in user. Times are in whole seconds since the epoch. */
data class Session(val sub: String, val name: String, val image: String, val issuedAt: Long, val expires: Long)

/**
 * One login in progress; challenge is an app's PKCE challenge, and empty for the web app's login. admin is a login to
 * /admin, which ends with an Admin session instead of a Session. provider is "ffpf" for a login through FFPF ID and
 * empty for GitHub, so a callback can refuse a cookie that another provider's login started. nonce is the OpenID
 * Connect nonce that FFPF ID's ID token must echo, and empty for GitHub.
 */
data class OAuthState(
  val state: String, val verifier: String, val returnTo: String, val challenge: String, val expires: Long,
  val admin: Boolean = false, val provider: String = "", val nonce: String = "",
)

/**
 * The administrator signed in to /admin: the FFPF ID account's verified email and its sub (without the "ffpf:"
 * prefix). Times are in whole seconds since the epoch.
 */
data class Admin(val email: String, val sub: String, val issuedAt: Long, val expires: Long)

/** Starts an administrator's session at nowMillis, truncated to the second. */
fun newAdmin(email: String, sub: String, nowMillis: Long): Admin {
  val now = Math.floorDiv(nowMillis, 1000L)
  return Admin(email, sub, now, now + SessionTimes.ADMIN_MAX_AGE)
}

/** The administrator's cookie, __Host- over HTTPS as cookieName. */
fun adminCookieName(secure: Boolean): String = if (secure) "__Host-typle-admin" else "typle-admin"

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
