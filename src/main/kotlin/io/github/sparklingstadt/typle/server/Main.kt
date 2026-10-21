// Command typle-web serves the app's backend (/api/health, /api/workspace and /api/auth/*) locally, as the Go command
// server/cmd/typle-web does, with the same answers. It reads the environment only: npm run dev passes the values of
// .env.local and .env. The local login (POST /api/auth/local) is available under the same conditions as the Go one's.
package io.github.sparklingstadt.typle.server

import io.github.sparklingstadt.typle.server.store.PgStore
import io.github.sparklingstadt.typle.server.web.fromEnv
import io.github.sparklingstadt.typle.server.web.logLine
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val DEFAULT_ADDRESS = "127.0.0.1:8081"

/** The -addr flag as Go's flag package reads it (-addr x, --addr x, -addr=x), DEFAULT_ADDRESS without it, or null. */
fun addressOf(args: Array<String>): String? {
  if (args.isEmpty()) return DEFAULT_ADDRESS
  val flag = args[0]
  return when {
    (flag == "-addr" || flag == "--addr") && args.size == 2 -> args[1]
    (flag.startsWith("-addr=") || flag.startsWith("--addr=")) && args.size == 1 -> flag.substringAfter('=')
    else -> null
  }
}

/** host:port as Go's net.Listen reads it: an IPv6 host in brackets, and no host for every address. */
fun parseAddress(address: String): Pair<String, Int>? {
  val colon = address.lastIndexOf(':')
  if (colon < 0) return null
  var host = address.substring(0, colon)
  if (host.startsWith("[")) {
    if (!host.endsWith("]")) return null
    host = host.substring(1, host.length - 1)
  } else if (':' in host) {
    return null
  }
  val port = address.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
  return (host.ifEmpty { "0.0.0.0" }) to port
}

fun main(args: Array<String>) {
  val address = addressOf(args)
  val (bindHost, bindPort) = address?.let(::parseAddress) ?: run {
    System.err.println("usage: typle-web [-addr host:port]  (default $DEFAULT_ADDRESS)")
    exitProcess(2)
  }
  // The login settings must be there to start; the database need not: until it opens, /api/health and /api/workspace
  // answer 503 databaseUnavailable and try again on later requests, while /api/auth/* works.
  val config = try {
    fromEnv(System::getenv, local = true)
  } catch (e: IllegalStateException) {
    logLine(e.message.orEmpty())
    exitProcess(1)
  }
  // The same variables as the Go backend.
  val connection = System.getenv("DATABASE_URL").takeUnless { it.isNullOrEmpty() } ?: System.getenv("POSTGRES_URL")
  val backend = Backend(config, PgStore::open, connection)
  val server = embeddedServer(CIO, configure = {
    connector {
      host = bindHost
      port = bindPort
    }
    // A client that keeps an idle connection open does not hold it for ever.
    connectionIdleTimeoutSeconds = 120
  }) { backend(backend) }
  // On SIGINT or SIGTERM, requests in flight get 5 seconds to finish before the database is released.
  Runtime.getRuntime().addShutdownHook(Thread {
    server.stop(5000, 5000)
    backend.close()
  })
  server.start(wait = false)
  logLine("serving the app's backend on http://$address")
  // Open the database in the background, so that the server is up at once.
  thread(isDaemon = true) {
    if (!backend.ready()) {
      logLine("WARNING: the database is not available (set DATABASE_URL or POSTGRES_URL to a running database): " +
        "/api/health and /api/workspace answer 503 until it opens, and are tried again on later requests; /api/auth/* works")
    }
  }
  Thread.currentThread().join()
}
