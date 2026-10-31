package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.GoJson
import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue

// The limits of a user's interests (MAX_INTERESTS and MAX_INTEREST_LENGTH in src/core/interests.ts), as Go's
// MaxInterests and MaxInterestLength. Lengths are in UTF-16 code units, as JavaScript measures them.
const val MAX_INTERESTS = 50
const val MAX_INTEREST_LENGTH = 50

/**
 * The interests of a user who has saved none (since typle-r v0.101.1), as Go's DefaultInterests and DEFAULT_INTERESTS
 * in src/core/interests.ts: every option, so that every official pack fits. The test checks it against a copy of
 * typle-r's tests/fixtures/default-interests.json.
 */
val DEFAULT_INTERESTS: List<String> = listOf(
  "language", "language/zh", "language/ru", "language/ko", "language/en", "language/es", "language/fr", "language/de", "language/cs",
  "programming", "programming/go", "programming/kotlin", "programming/java", "programming/typescript", "programming/php",
  "programming/ruby", "programming/sql", "programming/linux", "programming/vim", "programming/test", "programming/node",
  "programming/shell", "programming/cloud",
)

private fun invalidInterests(): Nothing = throw InvalidWorkspaceException("interests are invalid")

/**
 * Reads saved interests (the interests column's text) as Go's DecodeInterests: nothing (NULL) or null is none, and
 * anything but a list of at most MAX_INTERESTS distinct, non-empty strings of at most MAX_INTEREST_LENGTH is refused
 * (InvalidWorkspaceException). The ids are not checked against the options: an unknown one is kept as it is.
 */
fun decodeInterests(data: String?): List<String> {
  if (data.isNullOrEmpty()) return emptyList()
  val value = try {
    JsonValue.parse(data)
  } catch (_: JsonSyntaxException) {
    invalidInterests()
  }
  return decodeInterests(value)
}

/** decodeInterests for interests already read as JSON, such as those of a save. */
fun decodeInterests(value: JsonValue): List<String> {
  val interests = when (value) {
    JsonNull -> emptyList()
    is JsonArray -> value.items.map { (it as? JsonString)?.value ?: invalidInterests() }
    else -> invalidInterests()
  }
  checkInterests(interests)
  return interests
}

/**
 * Refuses (InvalidWorkspaceException) interests decodeInterests would refuse, as Go's CheckInterests: more than
 * MAX_INTERESTS, or one that is empty, longer than MAX_INTEREST_LENGTH or repeated.
 */
fun checkInterests(interests: List<String>) {
  if (interests.size > MAX_INTERESTS) invalidInterests()
  val seen = HashSet<String>()
  for (id in interests) {
    if (id.isEmpty() || utf16Length(id) > MAX_INTEREST_LENGTH || !seen.add(id)) invalidInterests()
  }
}

/** Writes interests as they are saved, as Go's EncodeInterests: none is []. */
fun encodeInterests(interests: List<String>): String =
  interests.joinToString(",", "[", "]") { GoJson.string(it, escapeHtml = false) }
