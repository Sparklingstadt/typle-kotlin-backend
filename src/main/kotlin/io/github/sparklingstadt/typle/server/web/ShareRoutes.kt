package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.json.GoJson
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.store.Share
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A pack is shared by link, as Go's server/internal/web/shares.go: /api/shares is the owner's (list, share or copy
// again, stop sharing) and /api/shared is for anyone with the link (the pack as a file, without the owner). The page
// that shows a link builds its URL from the site's origin and the id, so no answer carries one.

// The most a PUT /api/shares body needs: a pack id, and room to spare.
private const val MAX_SHARE_BODY_BYTES = 4 shl 10

// updatedAt as JavaScript's toISOString writes it.
private val isoTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

// One link as /api/shares answers it.
private fun shareJson(share: Share) = "{\"id\":" + GoJson.string(share.id, escapeHtml = true) +
  ",\"packId\":" + GoJson.string(share.packId, escapeHtml = true) +
  ",\"name\":" + GoJson.string(share.name, escapeHtml = true) +
  ",\"updatedAt\":" + GoJson.string(isoTime.format(share.updatedAt), escapeHtml = true) + "}"

/** GET, HEAD, PUT and DELETE /api/shares; HEAD is served as GET. */
suspend fun ApplicationCall.shares(config: Config) {
  if (!methods(HttpMethod.Get, HttpMethod.Head, HttpMethod.Put, HttpMethod.Delete)) return
  when (request.httpMethod) {
    HttpMethod.Put -> putShare(config)
    HttpMethod.Delete -> deleteShare(config)
    else -> listShares(config)
  }
}

private suspend fun ApplicationCall.listShares(config: Config) {
  val user = signedIn(config) ?: return
  val shares = try {
    withContext(Dispatchers.IO) { config.store!!.listShares(user.sub) }
  } catch (e: Exception) {
    report(e, "shares.read")
    return refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
  }
  reply(HttpStatusCode.OK, shares.joinToString(",", "{\"shares\":[", "]}", transform = ::shareJson))
}

// Shares a pack, or copies it again into its link. It is checked as PUT /api/workspace is: the Origin of a cookie's
// request, a JSON body, then the login. The body is {"packId"} and nothing else.
private suspend fun ApplicationCall.putShare(config: Config) {
  val type = request.headers["Content-Type"].orEmpty().substringBefore(';').trim()
  if (!(fromApp(this) || config.sameOrigin(request.headers["Origin"])) || type != "application/json") {
    return refuse(HttpStatusCode.Forbidden, "forbiddenRequest")
  }
  val user = signedIn(config) ?: return
  val packId = readPackId() ?: return refuse(HttpStatusCode.BadRequest, "invalidWorkspace")
  val share = try {
    withContext(Dispatchers.IO) { config.store!!.putShare(user.sub, packId) }
  } catch (e: WorkspaceError) {
    when (e.key) {
      "packNotFound" -> return refuse(HttpStatusCode.NotFound, e.key)
      "invalidWorkspace" -> return refuse(HttpStatusCode.BadRequest, e.key)
    }
    report(e, "shares.write")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  } catch (e: Exception) {
    report(e, "shares.write")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  }
  reply(HttpStatusCode.OK, shareJson(share))
}

// Stops sharing the pack ?pack=. A request without a body has no Content-Type to check; the Origin is checked as for
// PUT. A pack that is not shared is answered the same.
private suspend fun ApplicationCall.deleteShare(config: Config) {
  if (!(fromApp(this) || config.sameOrigin(request.headers["Origin"]))) {
    return refuse(HttpStatusCode.Forbidden, "forbiddenRequest")
  }
  val user = signedIn(config) ?: return
  val packId = request.queryParameters["pack"]
  if (packId.isNullOrEmpty()) return refuse(HttpStatusCode.BadRequest, "invalidWorkspace")
  try {
    withContext(Dispatchers.IO) { config.store!!.deleteShare(user.sub, packId) }
  } catch (e: Exception) {
    report(e, "shares.write")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  }
  response.headers.append("Cache-Control", "private, no-store")
  respond(HttpStatusCode.NoContent)
}

// The pack id of a body that is {"packId": "<a pack id>"}, with no other member, or null for any other body.
private suspend fun ApplicationCall.readPackId(): String? {
  // Go's server gives a request without a body (no length, or a length of 0, and no chunks) no body at all.
  val length = request.headers["Content-Length"]
  if (length == "0" || (length == null && request.headers["Transfer-Encoding"] == null)) return null
  return try {
    val body = withContext(Dispatchers.IO) { receiveStream().use { readLimited(it, MAX_SHARE_BODY_BYTES) } }
    val fields = parseObject(String(body, Charsets.UTF_8)).members
    // null is not a string, and is refused as an empty id is.
    (fields["packId"] as? JsonString)?.value?.takeIf { fields.size == 1 && it.isNotEmpty() }
  } catch (_: Exception) {
    null
  }
}

/**
 * GET and HEAD /api/shared?id=: the pack a link shares, as the file it was copied into. Anyone with the link may read
 * it, so it is the same for every reader and needs no cookie, but it is not kept, so that a link that is stopped is
 * gone at once.
 */
suspend fun ApplicationCall.shared(config: Config) {
  // A shared pack is for people with the link, not for search engines; every answer says so, errors included.
  response.headers.append("X-Robots-Tag", "noindex")
  if (!methods(HttpMethod.Get, HttpMethod.Head)) return
  val data = try {
    withContext(Dispatchers.IO) { config.store!!.sharedPack(request.queryParameters["id"].orEmpty()) }
  } catch (e: Exception) {
    report(e, "shared.read")
    return refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
  }
  if (data == null) return refuse(HttpStatusCode.NotFound, "sharedPackNotFound")
  response.headers.append("Cache-Control", "no-store")
  if (request.httpMethod == HttpMethod.Head) {
    respond(object : OutgoingContent.NoContent() {
      override val status = HttpStatusCode.OK
      override val contentType = jsonType
    })
  } else {
    respondText(data, jsonType, HttpStatusCode.OK)
  }
}
