package io.github.sparklingstadt.typle.server.store

import java.net.URI
import java.net.URLDecoder

/**
 * The database URL with the server certificate kept verified, as Go's store.ConnString makes it: sslmode=prefer,
 * require and verify-ca become verify-full, and uselibpqcompat=true (libpq's meaning of those modes) keeps the mode
 * and is only dropped. The query is written as Go's url.Values.Encode writes it, sorted by name.
 */
fun connString(connection: String): String {
  val scheme = connection.substringBefore("://", "")
  require(connection.contains("://") && (scheme == "postgres" || scheme == "postgresql")) {
    "database URL must be a postgres:// or postgresql:// URL"
  }
  val base = connection.substringBefore('?')
  val query = parseQuery(connection.substringAfter('?', ""))
  val libpq = query["uselibpqcompat"]?.firstOrNull() == "true"
  query.remove("uselibpqcompat")
  if (!libpq && query["sslmode"]?.firstOrNull() in setOf("prefer", "require", "verify-ca")) {
    query["sslmode"] = mutableListOf("verify-full")
  }
  val encoded = query.toSortedMap().flatMap { (name, values) -> values.map { escape(name) + "=" + escape(it) } }
  return if (encoded.isEmpty()) base else base + "?" + encoded.joinToString("&")
}

/** Where the JDBC driver connects, and as whom. */
data class JdbcTarget(val url: String, val user: String?, val password: String?)

// The libpq parameters the JDBC driver knows under other names. Others are not passed on.
private val jdbcNames = mapOf(
  "sslmode" to "sslmode", "channel_binding" to "channelBinding", "connect_timeout" to "connectTimeout",
  "application_name" to "ApplicationName",
)

/**
 * The JDBC URL of connString(connection). A verified mode checks the certificate against the JVM's trusted roots, as
 * pgx does against the system's: the driver's default factory would look for ~/.postgresql/root.crt instead.
 */
fun jdbcTarget(connection: String): JdbcTarget {
  val uri = URI(connString(connection))
  val host = uri.host ?: throw IllegalArgumentException("database URL has no host")
  val port = if (uri.port >= 0) ":${uri.port}" else ""
  val parameters = parseQuery(uri.rawQuery.orEmpty()).toSortedMap().flatMap { (name, values) ->
    val jdbcName = jdbcNames[name] ?: return@flatMap emptyList()
    values.flatMap { value ->
      val pair = listOf("$jdbcName=${escape(value)}")
      if (name == "sslmode" && value.startsWith("verify-")) pair + "sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory" else pair
    }
  }
  val query = if (parameters.isEmpty()) "" else "?" + parameters.joinToString("&")
  val userInfo = uri.rawUserInfo
  val user = userInfo?.substringBefore(':')?.let(::percentDecode)
  val password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::percentDecode)
  return JdbcTarget("jdbc:postgresql://$host$port${uri.rawPath.orEmpty()}$query", user, password)
}

private fun parseQuery(query: String): MutableMap<String, MutableList<String>> {
  val values = LinkedHashMap<String, MutableList<String>>()
  for (part in query.split('&')) {
    if (part.isEmpty()) continue
    val name = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
    val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
    values.getOrPut(name) { mutableListOf() }.add(value)
  }
  return values
}

// Go's url.QueryEscape: unreserved characters as they are, a space as +, every other byte as %XX.
private fun escape(value: String): String = buildString {
  for (byte in value.toByteArray()) {
    val c = byte.toInt() and 0xff
    when {
      c.toChar() in 'a'..'z' || c.toChar() in 'A'..'Z' || c.toChar() in '0'..'9' || c.toChar() in "-_.~" -> append(c.toChar())
      c == ' '.code -> append('+')
      else -> append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
    }
  }
}

// Percent-decoding of a user or password, where + is itself.
private fun percentDecode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
