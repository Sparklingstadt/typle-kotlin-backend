package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.json.GoJson
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Serves the app's /api paths, as Go's web.New; any other path is a 404. One interceptor routes them by exact path, as
 * Go's ServeMux does, and every response carries the security headers.
 */
fun Application.typleWeb(config: Config) {
  intercept(ApplicationCallPipeline.Call) {
    call.serve(config)
    finish()
  }
}

/** Answers one request with config: the paths are matched exactly, and every response carries the security headers. */
suspend fun ApplicationCall.serve(config: Config) {
  secure(this)
  when (val path = request.path()) {
    "/api/health" -> health(config)
    "/api/workspace" -> workspace(config)
    else -> if (!auth(config, path)) refuse(HttpStatusCode.NotFound, "notFound")
  }
}

/**
 * Answers every request with a 503 and the error key, in the same form as the handlers' own failures. The backend uses
 * it for the parts it could not set up.
 */
fun Application.unavailable(key: String) {
  intercept(ApplicationCallPipeline.Call) {
    secure(call)
    call.refuse(HttpStatusCode.ServiceUnavailable, key)
    finish()
  }
}

// The headers next.config.mjs sends with every Next.js response: no other site may frame it, and responses are not
// sniffed into another type or sent with the full URL as the referrer.
private val securityHeaders = listOf(
  "Content-Security-Policy" to "frame-ancestors 'none'; base-uri 'self'; object-src 'none'",
  "X-Frame-Options" to "DENY",
  "X-Content-Type-Options" to "nosniff",
  "Referrer-Policy" to "strict-origin-when-cross-origin",
)

internal fun secure(call: ApplicationCall) {
  for ((name, value) in securityHeaders) call.response.headers.append(name, value)
}

private val jsonType = ContentType.parse("application/json; charset=utf-8")

/**
 * Writes every JSON answer, as Go's reply: not kept by a cache, varying with the cookie, and ending with a newline as
 * Go's encoder writes it. A HEAD gets the headers alone.
 */
suspend fun ApplicationCall.reply(status: HttpStatusCode, json: String) {
  response.headers.append("Cache-Control", "private, no-store")
  response.headers.append("Vary", "Cookie")
  if (request.httpMethod == HttpMethod.Head) {
    respond(object : OutgoingContent.NoContent() {
      override val status = status
      override val contentType = jsonType
    })
  } else {
    respondText(json + "\n", jsonType, status)
  }
}

/** Errors are keys of the app's `errors` messages; the screen shows them in the reader's language. */
suspend fun ApplicationCall.refuse(status: HttpStatusCode, key: String) =
  reply(status, "{\"error\":" + GoJson.string(key, escapeHtml = true) + "}")

/** Lets only the listed methods through; any other is a 405 that names them. */
suspend fun ApplicationCall.methods(vararg allow: HttpMethod): Boolean {
  if (request.httpMethod in allow) return true
  response.headers.append("Allow", allow.joinToString(", ") { it.value })
  refuse(HttpStatusCode.MethodNotAllowed, "methodNotAllowed")
  return false
}

/** Logs a failure that is answered with a 503, with the operation that failed. */
fun ApplicationCall.report(error: Throwable, operation: String) =
  logLine("${request.httpMethod.value} ${request.path()}: $operation: $error")

/** Answers 200 once the database answers, which is what a deployment check needs to know. HEAD is served as GET. */
private suspend fun ApplicationCall.health(config: Config) {
  if (!methods(HttpMethod.Get, HttpMethod.Head)) return
  val store = config.store
  val failure = if (store == null) IllegalStateException("the database is not open") else try {
    withContext(Dispatchers.IO) { store.ping() }
    null
  } catch (e: Exception) {
    e
  }
  if (failure != null) {
    logLine("${request.httpMethod.value} ${request.path()}: $failure")
    refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
    return
  }
  reply(HttpStatusCode.OK, "{\"status\":\"ok\"}")
}
