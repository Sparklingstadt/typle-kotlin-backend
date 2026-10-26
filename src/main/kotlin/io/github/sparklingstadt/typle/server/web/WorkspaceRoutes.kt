package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.json.GoJson
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.session.Session
import io.github.sparklingstadt.typle.server.session.cookieName
import io.github.sparklingstadt.typle.server.store.ConflictException
import io.github.sparklingstadt.typle.server.store.Saved
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the request is an app's (iPad, Mac, Android or Windows): it carries an Authorization header, which a browser does
 * not send to this site on its own, and is then judged by that header alone.
 */
fun fromApp(call: ApplicationCall): Boolean = !call.request.headers["Authorization"].isNullOrEmpty()

/**
 * The signed-in GitHub account: from an app's bearer token when the request has an Authorization header, and
 * otherwise from the session cookie. A request with both is judged by the token, so a cookie never stands in for a
 * token that is refused.
 */
fun Config.owner(call: ApplicationCall): Session? {
  if (fromApp(call)) {
    val token = call.request.headers["Authorization"]!!.removePrefix("Bearer ")
    if (token == call.request.headers["Authorization"] || token.isEmpty() || ' ' in token || '\t' in token) return null
    return keys.openApp(token, now())
  }
  val value = cookie(call, cookieName(secure)) ?: return null
  return keys.open(value, now())
}

val Config.secure: Boolean get() = baseUrl.scheme == "https"

/**
 * The first cookie of the name, as Go's Request.Cookie reads it: the value as written, without enclosing quotes, and
 * only when it has the characters a cookie value may have.
 */
fun cookie(call: ApplicationCall, name: String): String? {
  for (line in call.request.headers.getAll("Cookie").orEmpty()) {
    for (part in line.split(';')) {
      val pair = part.trim()
      if (pair.isEmpty() || !pair.contains('=')) continue
      if (pair.substringBefore('=') != name) continue
      var value = pair.substringAfter('=')
      if (value.length > 1 && value.startsWith('"') && value.endsWith('"')) value = value.substring(1, value.length - 1)
      if (value.all { it in ' '..'~' && it != '"' && it != ';' && it != '\\' }) return value
    }
  }
  return null
}

/**
 * Whether origin is the app's own: that of the base URL, written as a browser writes it (lower case, without the
 * default port). State-changing requests from a browser must pass this.
 */
fun Config.sameOrigin(origin: String?): Boolean {
  val scheme = baseUrl.scheme.lowercase()
  var host = baseUrl.hostname.lowercase()
  if (':' in host) host = "[$host]"
  val port = baseUrl.port
  if (port.isNotEmpty() && !(scheme == "http" && port == "80") && !(scheme == "https" && port == "443")) host += ":$port"
  return !origin.isNullOrEmpty() && origin == "$scheme://$host"
}

/** GET, HEAD and PUT /api/workspace; HEAD is served as GET. */
suspend fun ApplicationCall.workspace(config: Config) {
  if (!methods(HttpMethod.Get, HttpMethod.Head, HttpMethod.Put)) return
  if (request.httpMethod == HttpMethod.Put) writeWorkspace(config) else readWorkspace(config)
}

// The answer of GET and PUT, in the order of Go's saved: the lists, packs and devices as the database stores them, with
// the fields Typle does not know. Packs are null for lists saved before packs existed, and for an account that has
// saved nothing.
private suspend fun ApplicationCall.answer(saved: Saved, owner: String) = reply(HttpStatusCode.OK,
  "{\"lists\":" + GoJson.compact(saved.lists, escapeHtml = true) +
    ",\"packs\":" + (saved.packs?.let { GoJson.compact(it, escapeHtml = true) } ?: "null") +
    ",\"devices\":" + GoJson.compact(saved.devices, escapeHtml = true) +
    ",\"revision\":" + saved.revision + ",\"accountId\":" + GoJson.string(owner, escapeHtml = true) + "}")

private suspend fun ApplicationCall.readWorkspace(config: Config) {
  val user = config.owner(this) ?: return refuse(HttpStatusCode.Unauthorized, "loginRequired")
  val result = try {
    withContext(Dispatchers.IO) { config.store!!.readRevision(user.sub) }
  } catch (e: Exception) {
    report(e, "workspace.read")
    return refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
  }
  // An account that has saved nothing has no lists, not the first lists that /api/v1 starts with.
  answer(result ?: Saved("[]", "[]", null, 0), user.sub)
}

private suspend fun ApplicationCall.writeWorkspace(config: Config) {
  // The Origin keeps other sites from saving with the browser's cookie. An app's token is not sent by a browser on its
  // own, so its requests, which have no Origin, need none.
  val type = request.headers["Content-Type"].orEmpty().substringBefore(';').trim()
  if (!(fromApp(this) || config.sameOrigin(request.headers["Origin"])) || type != "application/json") {
    return refuse(HttpStatusCode.Forbidden, "forbiddenRequest")
  }
  val user = config.owner(this) ?: return refuse(HttpStatusCode.Unauthorized, "loginRequired")
  // Go's server gives a request without a body (no length, or a length of 0, and no chunks) no body at all.
  val length = request.headers["Content-Length"]
  if (length == "0" || (length == null && request.headers["Transfer-Encoding"] == null)) {
    return refuse(HttpStatusCode.BadRequest, "emptyRequest")
  }
  val body = try {
    withContext(Dispatchers.IO) { receiveStream().use { readLimited(it, MAX_BODY_BYTES) } }
  } catch (_: TooLargeException) {
    return refuse(HttpStatusCode.PayloadTooLarge, "workspaceTooLarge")
  } catch (e: Exception) {
    report(e, "workspace.write")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  }
  val (revision, change) = try {
    val fields = parseObject(String(body, Charsets.UTF_8))
    // The account is checked before the rest, so a screen that signed in as someone else is told so first.
    val account = fields.members["accountId"]
    if (account !is JsonString || account.value != user.sub) return refuse(HttpStatusCode.Unauthorized, "loginAccountChanged")
    decodeFields(fields)
  } catch (_: LegacyRequestException) {
    return refuse(HttpStatusCode.Conflict, "conflict")
  } catch (_: LevelsConflictException) {
    return refuse(HttpStatusCode.BadRequest, "levelsConflict")
  } catch (_: InvalidRequestException) {
    return refuse(HttpStatusCode.BadRequest, "invalidWorkspace")
  }
  val result = try {
    withContext(Dispatchers.IO) { config.store!!.writeRevision(user.sub, revision, change) }
  } catch (_: ConflictException) {
    return refuse(HttpStatusCode.Conflict, "conflict")
  } catch (e: WorkspaceError) {
    if (e.key == "listLimit" || e.key == "invalidWorkspace") return refuse(HttpStatusCode.BadRequest, e.key)
    report(e, "workspace.write")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  } catch (e: Exception) {
    report(e, "workspace.write")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  }
  answer(result, user.sub)
}
