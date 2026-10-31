package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.FFPFRejectedException
import io.github.sparklingstadt.typle.server.auth.goTrimSpace
import io.github.sparklingstadt.typle.server.json.GoJson
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue
import io.github.sparklingstadt.typle.server.session.AppCode
import io.github.sparklingstadt.typle.server.session.OAUTH_COOKIE
import io.github.sparklingstadt.typle.server.session.OAuthState
import io.github.sparklingstadt.typle.server.session.Session
import io.github.sparklingstadt.typle.server.session.SessionTimes
import io.github.sparklingstadt.typle.server.session.cookieName
import io.github.sparklingstadt.typle.server.session.newSession
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GITHUB_CALLBACK = "/api/auth/callback/github"
private const val FFPF_CALLBACK = "/api/auth/callback/ffpf"

// The providers, as signin's provider= and /api/auth/session's providers name them. A GitHub login keeps an empty
// provider in its OAuth cookie, so that the cookie is the same as before FFPF ID was added.
private const val PROVIDER_GITHUB = "github"
private const val PROVIDER_FFPF = "ffpf"

// Where the OAuth cookie lives: only the login endpoints are sent it.
private const val AUTH_PATH = "/api/auth"
private const val DEFAULT_TO = "/home"
private const val LOCAL_SUB = "github:0"
private const val LOCAL_NAME = "ローカル練習用"

// Keeps the OAuth cookie, which holds returnTo, well under the 4096 bytes a browser accepts.
private const val MAX_RETURN_TO = 1024

// Where a login an app (the iPad, the Mac, the Android or the Windows app) started ends: the apps wait for this scheme.
private const val APP_RETURN = "typle-r://login"

// Far more than a code and a verifier take.
private const val MAX_TOKEN_REQUEST = 4096

// The signin's clients for a login an app starts. Every app logs in the same way.
private val appClients = setOf("ipad", "macos", "android", "windows")

// What RFC 7636 allows as a code verifier; an S256 challenge is 43 of these characters.
private val pkceValue = Regex("[A-Za-z0-9._~-]{43,128}")
private val pkceChallenge = Regex("[A-Za-z0-9_-]{43}")

private val random = SecureRandom()
private val base64Url = Base64.getUrlEncoder().withoutPadding()

/** The S256 code challenge of verifier. */
fun challengeOf(verifier: String): String =
  base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

/**
 * Where to go back to after the login: a path of this site. Anything else, such as another site, a protocol-relative
 * URL (//host) or one a browser reads as such (/\host), one with a control character or a broken escape, or one over
 * MAX_RETURN_TO bytes, is /home.
 */
fun safeReturnTo(value: String?): String {
  if (value.isNullOrEmpty() || value.toByteArray().size > MAX_RETURN_TO || value[0] != '/' ||
    (value.length > 1 && (value[1] == '/' || value[1] == '\\'))) return DEFAULT_TO
  // What Go's url.Parse refuses: control characters anywhere, and broken escapes in the path or the fragment.
  if (value.any { it < ' ' || it == '\u007f' }) return DEFAULT_TO
  val path = value.substringBefore('?').substringBefore('#')
  val fragment = value.substringAfter('#', "")
  if (brokenEscape(path) || brokenEscape(fragment)) return DEFAULT_TO
  return value
}

private fun brokenEscape(text: String): Boolean {
  var i = text.indexOf('%')
  while (i >= 0) {
    if (i + 2 >= text.length || !text[i + 1].isHex() || !text[i + 2].isHex()) return true
    i = text.indexOf('%', i + 3)
  }
  return false
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun randomValue(): String = ByteArray(32).also(random::nextBytes).let(base64Url::encodeToString)

/** Routes the login paths under /api/auth, and tells whether path was one of them. */
internal suspend fun ApplicationCall.auth(config: Config, path: String): Boolean {
  when (path) {
    "/api/auth/signin" -> signin(config)
    GITHUB_CALLBACK -> callback(config, PROVIDER_GITHUB)
    FFPF_CALLBACK -> callback(config, PROVIDER_FFPF)
    "/api/auth/session" -> session(config)
    "/api/auth/token" -> token(config)
    "/api/auth/signout" -> signout(config)
    "/api/auth/local" -> localLogin(config)
    "/api/auth/admin/signout" -> adminSignout(config)
    else -> return false
  }
  return true
}

/**
 * Where the provider sends the browser back to: the callback URL of the GitHub OAuth App, or the redirect URI
 * registered for the FFPF ID client.
 */
private fun Config.redirectUri(path: String) = baseUrl.origin + path

/** Sets a cookie, written as Go's http.SetCookie writes it. A negative maxAge deletes it. */
fun ApplicationCall.setCookie(name: String, value: String, path: String, maxAge: Int, secure: Boolean, sameSite: String = "Lax") {
  val age = if (maxAge < 0) 0 else maxAge
  response.headers.append("Set-Cookie",
    "$name=$value; Path=$path; Max-Age=$age; HttpOnly" + (if (secure) "; Secure" else "") + "; SameSite=$sameSite")
}

internal fun ApplicationCall.sessionCookie(config: Config, value: String, maxAge: Int) =
  setCookie(cookieName(config.secure), value, "/", maxAge, config.secure)

private fun ApplicationCall.oauthCookie(config: Config, value: String, maxAge: Int) =
  setCookie(OAUTH_COOKIE, value, AUTH_PATH, maxAge, config.secure)

// Signs the session in. The cookie holds only what the screen shows, not GitHub's token.
private fun ApplicationCall.issue(config: Config, sub: String, name: String, image: String) =
  sessionCookie(config, config.keys.seal(newSession(sub, name, image, config.now())), SessionTimes.MAX_AGE.toInt())

/** Redirects the browser; a redirect is not kept by a cache, as it may set a cookie. */
internal suspend fun ApplicationCall.goTo(location: String) {
  response.headers.append("Location", location)
  response.headers.append("Cache-Control", "no-store")
  respond(HttpStatusCode.Found)
}

// Ends a login an app started, with the code or the error.
private suspend fun ApplicationCall.toApp(key: String, value: String) =
  goTo("$APP_RETURN?$key=" + URLEncoder.encode(value, Charsets.UTF_8))

/** Whether the provider's login is configured. */
private fun Config.ready(provider: String) =
  if (provider == PROVIDER_FFPF) ffpf != null else oauth != null && gitHub != null

/**
 * Whether the provider's login can run; otherwise answers 503 and logs why (a missing setting is not reported as an
 * error).
 */
private suspend fun ApplicationCall.loginReady(config: Config, provider: String): Boolean {
  if (config.ready(provider)) return true
  logLine("${request.httpMethod.value} ${request.path()}: the $provider login is not configured")
  refuse(HttpStatusCode.ServiceUnavailable, "authUnavailable")
  return false
}

/**
 * Sends the browser to GitHub, or with provider=ffpf to FFPF ID, with a state and a PKCE challenge (and for FFPF ID a
 * nonce), and keeps them, the verifier and where to return to in the signed OAuth cookie. With client=ipad,
 * client=macos, client=android or client=windows and the app's own S256 code_challenge, the login is that app's: it
 * ends at APP_RETURN with a code that only the holder of the challenge's verifier can trade for a token. With admin=1
 * the login is to the administrator's dashboard: it always goes through FFPF ID, whatever provider says, and always
 * returns to the dashboard. Only GET: it sets cookies, which a HEAD must not do.
 */
private suspend fun ApplicationCall.signin(config: Config) {
  if (!methods(HttpMethod.Get)) return
  val query = request.queryParameters
  val admin = query["admin"] == "1"
  val provider = if (admin || query["provider"] == PROVIDER_FFPF) PROVIDER_FFPF else PROVIDER_GITHUB
  if (!loginReady(config, provider)) return
  val returnTo = if (admin) ADMIN_PAGE else safeReturnTo(query["returnTo"])
  var appChallenge = ""
  if (!admin && query["client"] in appClients) {
    appChallenge = query["code_challenge"].orEmpty()
    if (!pkceChallenge.matches(appChallenge)) return toApp("error", "loginFailed")
  }
  val state = randomValue()
  val verifier = randomValue()
  val expires = Math.floorDiv(config.now(), 1000L) + SessionTimes.OAUTH_MAX_AGE
  var pending = OAuthState(state, verifier, returnTo, appChallenge, expires, admin)
  val location = if (provider == PROVIDER_FFPF) {
    val nonce = randomValue()
    pending = pending.copy(provider = PROVIDER_FFPF, nonce = nonce)
    config.ffpf!!.authorizeUrl(state, challengeOf(verifier), nonce, config.redirectUri(FFPF_CALLBACK))
  } else {
    config.oauth!!.authorizeUrl(state, challengeOf(verifier), config.redirectUri(GITHUB_CALLBACK))
  }
  oauthCookie(config, config.keys.sealOAuth(pending), SessionTimes.OAUTH_MAX_AGE.toInt())
  goTo(location)
}

/**
 * Where the provider sends the browser back to. Whatever happens, the OAuth cookie is spent and the browser goes back
 * to the page it left, signed in only if everything checked out: the cookie was started for this provider, its state
 * matches, and the provider vouches for the account. A login an app started goes back to the app instead, with a code
 * for it to trade for a token, or loginFailed; no cookie is set. A login to the administrator's dashboard ends in
 * finishAdmin; it can only be one through FFPF ID, and one that comes back to GitHub's callback goes back to the
 * dashboard with adminForbidden.
 */
private suspend fun ApplicationCall.callback(config: Config, provider: String) {
  if (!methods(HttpMethod.Get) || !loginReady(config, provider)) return
  oauthCookie(config, "", -1)
  val pending = cookie(this, OAUTH_COOKIE)?.let { config.keys.openOAuth(it, config.now()) }
  if (pending != null && pending.admin && provider != PROVIDER_FFPF) return goTo(ADMIN_REFUSED)
  val returnTo = pending?.let { safeReturnTo(it.returnTo) } ?: DEFAULT_TO
  val app = pending != null && pending.challenge.isNotEmpty()
  suspend fun fail() = if (app) toApp("error", "loginFailed") else goTo(returnTo)
  // The cookie of a GitHub login has no provider, and that of an FFPF ID login "ffpf".
  val started = pending?.provider?.ifEmpty { PROVIDER_GITHUB }
  val query = request.queryParameters
  val state = query["state"].orEmpty()
  val code = query["code"].orEmpty()
  if (pending == null || started != provider || query["error"].orEmpty().isNotEmpty() || state.isEmpty() || code.isEmpty() ||
    !MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray())) return fail()
  val sub: String
  val name: String
  var image = ""
  if (provider == PROVIDER_FFPF) {
    val user = try {
      withContext(Dispatchers.IO) {
        config.ffpf!!.exchange(code, pending.verifier, config.redirectUri(FFPF_CALLBACK), pending.nonce)
      }
    } catch (e: Exception) {
      // A refusal comes of what the user did, such as a code used twice, and is not the app's failure.
      if (e !is FFPFRejectedException) report(e, "auth.callback")
      return fail()
    }
    if (pending.admin) return finishAdmin(config, user)
    sub = user.account
    name = goTrimSpace(user.name)
  } else {
    val user = try {
      withContext(Dispatchers.IO) {
        val token = config.oauth!!.exchange(code, pending.verifier, config.redirectUri(GITHUB_CALLBACK))
        config.gitHub!!.user(token)
      }
    } catch (e: Exception) {
      report(e, "auth.callback")
      return fail()
    }
    sub = user.account
    name = goTrimSpace(user.displayName)
    image = user.avatarUrl
  }
  if (app) {
    val expires = Math.floorDiv(config.now(), 1000L) + SessionTimes.APP_CODE_MAX_AGE
    return toApp("code", config.keys.sealCode(AppCode(sub, name, image, pending.challenge, expires)))
  }
  issue(config, sub, name, image)
  goTo(returnTo)
}

// The signed-in user as the screen sees them. The image is null when there is none.
private fun userJson(s: Session) =
  "{\"id\":${GoJson.string(s.sub, true)},\"name\":${GoJson.string(s.name, true)},\"image\":" +
    (if (s.image.isEmpty()) "null" else GoJson.string(s.image, true)) + "}"

/**
 * Trades the code a login of an app ended with, and the verifier of its challenge, for the app's token. It takes no
 * cookie and sets none, so it needs no Origin: only the app that started the login knows the verifier. Any mistake is
 * loginFailed, and the app starts the login again.
 */
private suspend fun ApplicationCall.token(config: Config) {
  if (!methods(HttpMethod.Post)) return
  val fields = try {
    val body = withContext(Dispatchers.IO) { receiveStream().use { readLimited(it, MAX_TOKEN_REQUEST) } }
    JsonValue.parse(String(body, Charsets.UTF_8)) as? JsonObject
  } catch (_: TooLargeException) {
    null
  } catch (_: JsonSyntaxException) {
    null
  }
  // Go's json.Unmarshal into string fields: a string, or "" when left out or null.
  fun text(name: String): String? = when (val value = fields?.members?.get(name)) {
    null, JsonNull -> ""
    is JsonString -> value.value
    else -> null
  }
  val codeValue = text("code")
  val verifier = text("verifier")
  if (fields == null || codeValue == null || verifier == null || !pkceValue.matches(verifier)) {
    return refuse(HttpStatusCode.BadRequest, "loginFailed")
  }
  val code = config.keys.openCode(codeValue, config.now())
  if (code == null || !MessageDigest.isEqual(challengeOf(verifier).toByteArray(), code.challenge.toByteArray())) {
    return refuse(HttpStatusCode.BadRequest, "loginFailed")
  }
  val signedIn = newSession(code.sub, code.name, code.image, config.now())
  reply(HttpStatusCode.OK, "{\"token\":" + GoJson.string(config.keys.sealApp(signedIn), true) + ",\"user\":" + userJson(signedIn) + "}")
}

// The answer of /api/auth/session: who is signed in, and the logins that are configured, which the screen may offer
// (in the order it lists them: "github" before "ffpf").
private fun sessionAnswer(config: Config, user: Session?) =
  "{\"user\":" + (user?.let(::userJson) ?: "null") + ",\"providers\":" +
    listOf(PROVIDER_GITHUB, PROVIDER_FFPF).filter(config::ready).joinToString(",", "[", "]") { "\"$it\"" } + "}"

/**
 * Who is signed in. It is not sealed again however active the user is: a session ends MAX_AGE after its login (typle-r
 * v0.107.0). A revoked one (signed out of every device) is no one, and its cookie is removed, when the revocations can
 * be read; otherwise the answer goes on without them, and the account's data, which is read with the revocations
 * (signedIn), stays out of reach all the same.
 */
private suspend fun ApplicationCall.session(config: Config) {
  if (!methods(HttpMethod.Get, HttpMethod.Head)) return
  val user = config.owner(this) ?: return reply(HttpStatusCode.OK, sessionAnswer(config, null))
  val revoked = try {
    config.revoked(user)
  } catch (_: NoSessionsException) {
    false
  } catch (e: Exception) {
    report(e, "session.check")
    false
  }
  if (revoked) {
    if (!fromApp(this)) sessionCookie(config, "", -1)
    return reply(HttpStatusCode.OK, sessionAnswer(config, null))
  }
  reply(HttpStatusCode.OK, sessionAnswer(config, user))
}

internal suspend fun ApplicationCall.noContent() {
  response.headers.append("Cache-Control", "private, no-store")
  response.headers.append("Vary", "Cookie")
  respond(HttpStatusCode.NoContent)
}

/**
 * Forgets the session in the browser. With ?everywhere=1 it also signs the account out of every device, as Go's
 * signout (typle-r v0.107.0): every session cookie and app token of a login until now is revoked
 * (Store.revokeSessions). An app may ask for that too, with its token and without an Origin. Signing out everywhere needs
 * a session that is itself not revoked; it answers loginRequired, and databaseUnavailable when the revocations cannot be
 * read or written, and then the cookie is kept so that the user can try again.
 */
private suspend fun ApplicationCall.signout(config: Config) {
  if (!methods(HttpMethod.Post)) return
  val everywhere = request.queryParameters["everywhere"] == "1"
  if (!(config.sameOrigin(request.headers["Origin"]) || everywhere && fromApp(this))) {
    return refuse(HttpStatusCode.Forbidden, "forbiddenRequest")
  }
  if (everywhere) {
    val user = config.owner(this) ?: return refuse(HttpStatusCode.Unauthorized, "loginRequired")
    val revoked = try {
      config.revoked(user).also { if (!it) withContext(Dispatchers.IO) { config.sessionSource!!.revokeSessions(user.sub) } }
    } catch (e: Exception) {
      report(e, "session.revoke")
      return refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
    }
    if (revoked) {
      if (!fromApp(this)) sessionCookie(config, "", -1)
      return refuse(HttpStatusCode.Unauthorized, "loginRequired")
    }
  }
  if (!fromApp(this)) sessionCookie(config, "", -1)
  noContent()
}

/** Signs in as a made-up account, for practice on a development machine. Off unless Config.localLogin. */
private suspend fun ApplicationCall.localLogin(config: Config) {
  if (!config.localLogin) return refuse(HttpStatusCode.NotFound, "notFound")
  if (!methods(HttpMethod.Post)) return
  if (!config.sameOrigin(request.headers["Origin"])) return refuse(HttpStatusCode.Forbidden, "forbiddenRequest")
  issue(config, LOCAL_SUB, LOCAL_NAME, "")
  noContent()
}
