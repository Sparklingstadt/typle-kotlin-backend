package io.github.sparklingstadt.typle.server

import io.github.sparklingstadt.typle.server.web.FakeStore
import io.github.sparklingstadt.typle.server.web.TestServer
import io.github.sparklingstadt.typle.server.web.expectError
import io.github.sparklingstadt.typle.server.web.sessionCookie
import io.github.sparklingstadt.typle.server.web.testConfig
import io.github.sparklingstadt.typle.server.web.TEST_OWNER
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The cases of server/vercel/vercel_test.go that apply: the login works while the database is down, and the database is
// tried again, at most once a second and once for many requests at a time.
class BackendTest {
  private val opens = AtomicInteger()
  private var now = 0L
  private var failing = true
  private val logged = mutableListOf<String>()

  private fun backend(connection: String? = "postgresql://h/db", open: (String) -> FakeStore = {
    opens.incrementAndGet()
    if (failing) throw IllegalStateException("connection refused") else FakeStore()
  }) = Backend(testConfig(store = null), open, connection, now = { now }, retryAfterMillis = 1000, log = { synchronized(logged) { logged.add(it) } })

  @Test fun loginWorksWhileTheDatabaseIsDown() {
    val backend = backend()
    TestServer { backend(backend) }.use { server ->
      for (path in listOf("/api/health", "/api/workspace", "/api/nope")) expectError(server.request("GET", path), 503, "databaseUnavailable", path)
      // Only /api/shared says noindex, as Go's vercel.go.
      val shared = server.request("GET", "/api/shared?id=0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d")
      expectError(shared, 503, "databaseUnavailable")
      assertEquals("noindex", shared.headers().firstValue("X-Robots-Tag").orElse(null))
      assertFalse(server.request("GET", "/api/workspace").headers().firstValue("X-Robots-Tag").isPresent)
      val session = server.request("GET", "/api/auth/session")
      assertEquals(200 to "{\"user\":null,\"providers\":[\"github\"]}\n", session.statusCode() to session.body())
      assertTrue(logged.any { "connection refused" in it }, logged.toString())
    }
  }

  @Test fun backOff() {
    val backend = backend()
    TestServer { backend(backend) }.use { server ->
      expectError(server.request("GET", "/api/health"), 503, "databaseUnavailable")
      assertEquals(1, opens.get())
      now = 999
      expectError(server.request("GET", "/api/health"), 503, "databaseUnavailable")
      assertEquals(1, opens.get())
      now = 1000
      failing = false
      assertEquals(200, server.request("GET", "/api/health").statusCode())
      assertEquals(2, opens.get())
      assertEquals(200, server.request("GET", "/api/workspace", headers = mapOf("Cookie" to sessionCookie(TEST_OWNER))).statusCode())
      assertEquals(2, opens.get())
    }
  }

  @Test fun oneAttemptForManyRequests() {
    val release = CountDownLatch(1)
    val started = CountDownLatch(1)
    failing = false
    val backend = backend(open = {
      opens.incrementAndGet()
      started.countDown()
      release.await(10, TimeUnit.SECONDS)
      FakeStore()
    })
    TestServer { backend(backend) }.use { server ->
      val pool = Executors.newFixedThreadPool(5)
      try {
        val answers = (1..5).map { pool.submit<Int> { server.request("GET", "/api/health").statusCode() } }
        started.await(10, TimeUnit.SECONDS)
        Thread.sleep(200)
        release.countDown()
        assertEquals(List(5) { 200 }, answers.map { it.get(10, TimeUnit.SECONDS) })
        assertEquals(1, opens.get())
      } finally {
        pool.shutdown()
      }
    }
  }

  @Test fun withoutDatabaseUrl() {
    val backend = backend(connection = null)
    TestServer { backend(backend) }.use { server ->
      expectError(server.request("GET", "/api/workspace"), 503, "databaseUnavailable")
      assertEquals(0, opens.get())
      assertTrue(logged.any { "DATABASE_URL" in it }, logged.toString())
    }
  }

  @Test fun parsesTheAddress() {
    assertEquals("127.0.0.1" to 8081, parseAddress("127.0.0.1:8081"))
    assertEquals("localhost" to 9000, parseAddress("localhost:9000"))
    assertEquals("::1" to 9002, parseAddress("[::1]:9002"))
    assertEquals("0.0.0.0" to 80, parseAddress(":80"))
    for (bad in listOf("", "8081", "host:", "host:x", "host:70000", "[::1]9002")) assertEquals(null, parseAddress(bad), bad)
    assertEquals("127.0.0.1:8081", addressOf(emptyArray()))
    assertEquals("127.0.0.1:9", addressOf(arrayOf("-addr", "127.0.0.1:9")))
    assertEquals("127.0.0.1:9", addressOf(arrayOf("--addr=127.0.0.1:9")))
    assertEquals(null, addressOf(arrayOf("-port", "9")))
  }
}
