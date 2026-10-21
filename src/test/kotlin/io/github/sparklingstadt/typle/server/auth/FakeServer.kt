package io.github.sparklingstadt.typle.server.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** A local HTTP server that stands in for GitHub; each request is counted and answered by answer. */
class FakeServer(answer: (HttpExchange) -> Unit) : AutoCloseable {
  val calls = AtomicInteger()
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { exchange ->
      calls.incrementAndGet()
      exchange.use { answer(it) }
    }
    start()
  }

  val url = "http://127.0.0.1:${server.address.port}"

  override fun close() = server.stop(0)
}

fun HttpExchange.reply(status: Int, body: String = "") {
  val bytes = body.toByteArray()
  sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
  if (bytes.isNotEmpty()) responseBody.write(bytes)
}
