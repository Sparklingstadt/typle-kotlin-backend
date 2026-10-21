package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.store.ConflictException
import kotlin.test.Test
import kotlin.test.assertEquals

// The cases of server/internal/web/headers_test.go and health_test.go.
class HeadersTest {
  @Test fun securityHeadersOnEveryResponse() {
    TestServer { typleWeb(testConfig(localLogin = true)) }.use { server ->
      val cases = listOf(
        Triple("health", "GET", "/api/health") to 200,
        Triple("workspace without login", "GET", "/api/workspace") to 401,
        Triple("method not allowed", "DELETE", "/api/workspace") to 405,
        Triple("not found", "GET", "/nowhere") to 404,
      )
      for ((request, status) in cases) {
        val (name, method, path) = request
        val response = server.request(method, path)
        assertEquals(status, response.statusCode(), name)
        expectSecurityHeaders(name, response)
      }
    }
    TestServer { unavailable("databaseUnavailable") }.use { server ->
      val response = server.request("GET", "/api/workspace")
      expectError(response, 503, "databaseUnavailable")
      expectSecurityHeaders("unavailable", response)
    }
  }

  // Every JSON answer, error or not, is not kept by any cache, and varies with the cookie.
  @Test fun jsonHeadersUniform() {
    TestServer { typleWeb(testConfig(localLogin = true)) }.use { server ->
      for ((method, path) in listOf("GET" to "/api/health", "GET" to "/nowhere", "DELETE" to "/api/workspace")) {
        expectJsonHeaders("$method $path", server.request(method, path))
      }
    }
  }

  @Test fun notFound() {
    TestServer { typleWeb(testConfig()) }.use { server ->
      for (path in listOf("/nowhere", "/api/health/", "/api/workspaces")) {
        expectError(server.request("GET", path), 404, "notFound", path)
      }
    }
  }

  @Test fun healthOk() {
    TestServer { typleWeb(testConfig()) }.use { server ->
      val response = server.request("GET", "/api/health")
      assertEquals(200 to "{\"status\":\"ok\"}\n", response.statusCode() to response.body())
      expectJsonHeaders("health", response)
      val head = server.request("HEAD", "/api/health")
      assertEquals(200 to "", head.statusCode() to head.body())
      expectJsonHeaders("head", head)
      val post = server.request("POST", "/api/health")
      expectError(post, 405, "methodNotAllowed")
      assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(null))
    }
  }

  @Test fun healthDatabaseDown() {
    TestServer { typleWeb(testConfig(store = FakeStore(pingFailure = IllegalStateException("connection refused")))) }.use { server ->
      expectError(server.request("GET", "/api/health"), 503, "databaseUnavailable")
    }
    TestServer { typleWeb(testConfig(store = FakeStore(failure = ConflictException()))) }.use { server ->
      assertEquals(200, server.request("GET", "/api/health").statusCode())
    }
  }
}
