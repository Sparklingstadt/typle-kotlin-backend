package io.github.sparklingstadt.typle.server.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConnStringTest {
  // The cases of TestConnString in server/internal/store/store_test.go.
  @Test fun keepsCertificatesVerifiedAsGo() {
    val cases = mapOf(
      "postgres://u:p@h/db" to "postgres://u:p@h/db",
      "postgresql://u:p@h/db?sslmode=require" to "postgresql://u:p@h/db?sslmode=verify-full",
      "postgresql://u:p@h/db?sslmode=prefer" to "postgresql://u:p@h/db?sslmode=verify-full",
      "postgresql://u:p@h/db?sslmode=verify-ca" to "postgresql://u:p@h/db?sslmode=verify-full",
      "postgresql://u:p@h/db?sslmode=disable" to "postgresql://u:p@h/db?sslmode=disable",
      "postgresql://u:p@h/db?sslmode=verify-full" to "postgresql://u:p@h/db?sslmode=verify-full",
      "postgresql://u:p@h/db?channel_binding=require&sslmode=require" to
        "postgresql://u:p@h/db?channel_binding=require&sslmode=verify-full",
      "postgresql://u:p@h/db?sslmode=require&uselibpqcompat=true" to "postgresql://u:p@h/db?sslmode=require",
      "postgresql://user:p%40ss@h/db?sslmode=require&channel_binding=require" to
        "postgresql://user:p%40ss@h/db?channel_binding=require&sslmode=verify-full",
    )
    for ((input, want) in cases) assertEquals(want, connString(input), input)
    for (input in listOf("", "mysql://h/db", "://")) {
      assertFailsWith<IllegalArgumentException>(input) { connString(input) }
    }
  }

  @Test fun jdbcTarget() {
    assertEquals(
      JdbcTarget("jdbc:postgresql://db.example:6543/typle?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory",
        "typle", "p@ss"),
      jdbcTarget("postgresql://typle:p%40ss@db.example:6543/typle?sslmode=require"))
    assertEquals(JdbcTarget("jdbc:postgresql://127.0.0.1:5432/typle", "typle", "ci-only-password"),
      jdbcTarget("postgresql://typle:ci-only-password@127.0.0.1:5432/typle"))
    assertEquals(JdbcTarget("jdbc:postgresql://h/db?sslmode=disable", null, null), jdbcTarget("postgres://h/db?sslmode=disable"))
    assertEquals(
      JdbcTarget("jdbc:postgresql://h/db?channelBinding=require&sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory",
        "u", "p"),
      jdbcTarget("postgresql://u:p@h/db?sslmode=require&channel_binding=require"))
    assertEquals(JdbcTarget("jdbc:postgresql://[::1]:5432/db", "u", null), jdbcTarget("postgresql://u@[::1]:5432/db"))
    assertFailsWith<IllegalArgumentException> { jdbcTarget("mysql://h/db") }
  }
}
