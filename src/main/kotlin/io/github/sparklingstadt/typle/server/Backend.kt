package io.github.sparklingstadt.typle.server

import io.github.sparklingstadt.typle.server.store.AdminSource
import io.github.sparklingstadt.typle.server.store.SessionSource
import io.github.sparklingstadt.typle.server.store.Store
import io.github.sparklingstadt.typle.server.web.Config
import io.github.sparklingstadt.typle.server.web.logLine
import io.github.sparklingstadt.typle.server.web.refuse
import io.github.sparklingstadt.typle.server.web.secure
import io.github.sparklingstadt.typle.server.web.serve
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's backend with the database opened late, as the Go backend's server/vercel: the login paths (/api/auth) need the login
 * settings only, so the session keeps being answered while the database is down, and every other path needs the
 * database too. The database is opened by the first request that needs it; the requests that come while that attempt
 * is in progress wait for its answer, and a failure is remembered for retryAfterMillis, during which requests get the
 * 503 at once.
 */
class Backend(
  private val config: Config,
  private val open: (String) -> Store,
  private val connection: String?,
  private val now: () -> Long = System::currentTimeMillis,
  private val retryAfterMillis: Long = 1000,
  private val log: (String) -> Unit = ::logLine,
) : AutoCloseable {
  @Volatile private var source: Store? = null
  private val lock = Any()
  private var failedAt: Long? = null
  private var flight: CompletableFuture<Store?>? = null

  /**
   * The configuration of the login's paths, without the database, so that they never wait for it, but for the
   * revocations (signing out of every device), which open it for that one question (Go's Config.Sessions). While it
   * does not open, they fail: the session's answer then goes on unchecked, and signing out everywhere answers
   * databaseUnavailable.
   */
  internal val login: Config by lazy {
    fun opened() = store() ?: throw IllegalStateException("the database is not open")
    config.copy(
      sessions = object : SessionSource {
        override fun revokeSessions(owner: String) = opened().revokeSessions(owner)
        override fun sessionsRevokedAt(owner: String) = opened().sessionsRevokedAt(owner)
      },
      // A login to the administrator's dashboard is the other /api/auth request that opens the database (Go's
      // lazyAdmins).
      admins = object : AdminSource {
        override fun admin(email: String, sub: String) = opened().admin(email, sub)
      },
    )
  }

  /** The database, opening it if that has not been done; null while it does not open (the reason is logged). Blocks. */
  fun store(): Store? {
    source?.let { return it }
    if (connection.isNullOrEmpty()) {
      log("start the backend: set DATABASE_URL (or POSTGRES_URL)")
      return null
    }
    // The lock is never held while the database is being opened or waited for: only to read or write the fields.
    val mine = CompletableFuture<Store?>()
    val running = synchronized(lock) {
      source?.let { return it }
      val failed = failedAt
      if (failed != null && now() - failed < retryAfterMillis) return null
      flight ?: run {
        flight = mine
        null
      }
    }
    if (running != null) return running.join()
    // Whatever happens, the waiters are released, with null unless the database opened.
    var opened: Store? = null
    try {
      opened = open(connection)
    } catch (e: Exception) {
      log("start the backend: connect to the database: $e")
    } finally {
      synchronized(lock) {
        flight = null
        failedAt = if (opened == null) now() else null
        if (opened != null) source = opened
      }
      mine.complete(opened)
    }
    return opened
  }

  /** Tries to open the database if that has not been done, and reports whether it is open. */
  fun ready(): Boolean = store() != null

  /** Closes the database, if it was opened. */
  override fun close() {
    (source as? AutoCloseable)?.close()
  }
}

/** Serves the login paths (/api/auth) from the login settings and any other path once the database is open. */
fun Application.backend(backend: Backend) {
  intercept(ApplicationCallPipeline.Call) {
    if (call.request.path().startsWith("/api/auth/")) {
      call.serve(backend.login)
    } else {
      val store = withContext(Dispatchers.IO) { backend.store() }
      if (store == null) {
        secure(call)
        // As Go's vercel.go: every answer of /api/shared says noindex, this one too.
        if (call.request.path() == "/api/shared") call.response.headers.append("X-Robots-Tag", "noindex")
        call.refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
      } else {
        call.serve(backend.login.copy(store = store))
      }
    }
    finish()
  }
}
