package io.github.sparklingstadt.typle.server.json

/** A JSON text that Go's encoding/json would refuse. */
class JsonSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * A parsed JSON value. The parser is as strict as Go's encoding/json (RFC 8259 numbers, no trailing commas, at most
 * 10000 levels of nesting), keeps a number as its literal, and keeps the last of a repeated object name.
 */
sealed interface JsonValue {
  /** Compact JSON, with strings written as Go writes them without HTML escaping. */
  fun toJson(): String = StringBuilder().also { write(it) }.toString()

  fun write(out: StringBuilder)

  companion object {
    fun parse(text: String): JsonValue = Parser(text).document()
  }
}

data class JsonObject(val members: Map<String, JsonValue>) : JsonValue {
  override fun write(out: StringBuilder) {
    out.append('{')
    var first = true
    for ((name, value) in members) {
      if (!first) out.append(',')
      first = false
      out.append(GoJson.string(name, escapeHtml = false)).append(':')
      value.write(out)
    }
    out.append('}')
  }
}

data class JsonArray(val items: List<JsonValue>) : JsonValue {
  override fun write(out: StringBuilder) {
    out.append('[')
    items.forEachIndexed { i, item ->
      if (i > 0) out.append(',')
      item.write(out)
    }
    out.append(']')
  }
}

data class JsonString(val value: String) : JsonValue {
  override fun write(out: StringBuilder) {
    out.append(GoJson.string(value, escapeHtml = false))
  }
}

/** A number as it was written, such as 1e2. */
data class JsonNumber(val literal: String) : JsonValue {
  override fun write(out: StringBuilder) {
    out.append(literal)
  }
}

data class JsonBool(val value: Boolean) : JsonValue {
  override fun write(out: StringBuilder) {
    out.append(value)
  }
}

data object JsonNull : JsonValue {
  override fun write(out: StringBuilder) {
    out.append("null")
  }
}

// Go's maxNestingDepth.
private const val MAX_DEPTH = 10000

private val numberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

// Iterative, with containers on a stack of its own, so that deep nesting cannot overflow the thread's stack.
private class Parser(private val text: String) {
  private var at = 0

  private sealed interface Open

  private class OpenArray(val items: MutableList<JsonValue> = mutableListOf()) : Open

  private class OpenObject(val members: LinkedHashMap<String, JsonValue> = LinkedHashMap(), var name: String? = null) : Open

  fun document(): JsonValue {
    val stack = ArrayList<Open>()
    var result: JsonValue? = null
    while (true) {
      skipSpace()
      val value: JsonValue? = when (peek()) {
        '{' -> {
          at++
          if (stack.size >= MAX_DEPTH) fail("exceeded max depth")
          skipSpace()
          if (peek() == '}') {
            at++
            JsonObject(emptyMap())
          } else {
            val open = OpenObject()
            open.name = name()
            stack.add(open)
            null
          }
        }
        '[' -> {
          at++
          if (stack.size >= MAX_DEPTH) fail("exceeded max depth")
          skipSpace()
          if (peek() == ']') {
            at++
            JsonArray(emptyList())
          } else {
            stack.add(OpenArray())
            null
          }
        }
        else -> scalar()
      }
      if (value == null) continue
      // Hand the finished value to the containers, closing each that ends after it.
      var finished: JsonValue = value
      while (true) {
        val top = stack.lastOrNull()
        if (top == null) {
          result = finished
          break
        }
        when (top) {
          is OpenArray -> top.items.add(finished)
          is OpenObject -> top.members[top.name!!] = finished
        }
        skipSpace()
        val c = peek()
        if (c == ',') {
          at++
          if (top is OpenObject) {
            skipSpace()
            top.name = name()
          }
          break
        }
        if ((top is OpenArray && c == ']') || (top is OpenObject && c == '}')) {
          at++
          stack.removeAt(stack.size - 1)
          finished = if (top is OpenArray) JsonArray(top.items) else JsonObject((top as OpenObject).members)
          continue
        }
        fail("unexpected character")
      }
      if (result != null) break
    }
    skipSpace()
    if (at != text.length) fail("data after the value")
    return result!!
  }

  private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end")

  private fun skipSpace() {
    while (at < text.length && (text[at] == ' ' || text[at] == '\t' || text[at] == '\n' || text[at] == '\r')) at++
  }

  // An object member's name and its colon.
  private fun name(): String {
    if (peek() != '"') fail("expected a name")
    val name = string()
    skipSpace()
    if (peek() != ':') fail("expected a colon")
    at++
    return name
  }

  private fun scalar(): JsonValue = when (peek()) {
    '"' -> JsonString(string())
    't' -> word("true", JsonBool(true))
    'f' -> word("false", JsonBool(false))
    'n' -> word("null", JsonNull)
    else -> {
      val match = numberPattern.matchAt(text, at) ?: fail("expected a value")
      at = match.range.last + 1
      JsonNumber(match.value)
    }
  }

  private fun word(word: String, value: JsonValue): JsonValue {
    if (!text.startsWith(word, at)) fail("expected a value")
    at += word.length
    return value
  }

  private fun string(): String {
    at++ // the opening quote
    val out = StringBuilder()
    while (true) {
      val c = peek()
      at++
      when {
        c == '"' -> return out.toString()
        c < ' ' -> fail("control character in a string")
        c != '\\' -> out.append(c)
        else -> {
          val e = peek()
          at++
          when (e) {
            '"', '\\', '/' -> out.append(e)
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
              if (at + 4 > text.length) fail("short escape")
              val hex = text.substring(at, at + 4)
              if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad escape")
              out.append(hex.toInt(16).toChar())
              at += 4
            }
            else -> fail("bad escape")
          }
        }
      }
    }
  }

  private fun fail(message: String): Nothing = throw JsonSyntaxException("$message at $at")
}
