package io.github.sparklingstadt.typle.server.web

import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpResponse

fun HttpResponse<String>.location(): String = headers().firstValue("Location").orElse("")

fun HttpResponse<String>.cookies(): List<String> = headers().allValues("Set-Cookie")

/** The Set-Cookie of name, as it was written. */
fun HttpResponse<String>.cookie(name: String): String? = cookies().firstOrNull { it.startsWith("$name=") }

/** The value of the Set-Cookie of name. */
fun HttpResponse<String>.cookieValue(name: String): String? = cookie(name)?.substringAfter('=')?.substringBefore(';')

/** The query of url, decoded. */
fun query(url: String): Map<String, String> = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
  .associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
