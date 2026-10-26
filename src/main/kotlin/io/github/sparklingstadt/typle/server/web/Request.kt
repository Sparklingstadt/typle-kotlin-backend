package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue
import io.github.sparklingstadt.typle.server.store.Change
import io.github.sparklingstadt.typle.server.workspace.InvalidWorkspaceException
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.checkLibrary
import io.github.sparklingstadt.typle.server.workspace.decodeValues
import io.github.sparklingstadt.typle.server.workspace.trimJS
import java.io.InputStream

/** MAX_WORKSPACE_BYTES (src/core/workspaceRequest.ts). */
const val MAX_BODY_BYTES = 1 shl 20

// Number.MAX_SAFE_INTEGER, the largest revision the app's JavaScript could have counted to.
private const val MAX_SAFE_INTEGER = (1L shl 53) - 1

class TooLargeException : Exception("request body is too large")

/**
 * A save from a screen of the app before profiles were removed, for a profile other than the first or changing the
 * profiles. It is answered with a conflict, so that screen reloads (LegacyProfileRequest in the app).
 */
class LegacyRequestException : Exception("request for a profile that no longer exists")

class InvalidRequestException : Exception("request is not a valid workspace")

/** Reads the body, giving up as soon as it is past limit rather than after reading all of it. */
fun readLimited(input: InputStream, limit: Int): ByteArray {
  val data = input.readNBytes(limit + 1)
  if (data.size > limit) throw TooLargeException()
  return data
}

/** Reads the body as one JSON object; anything else is InvalidRequestException. */
fun parseObject(body: String): JsonObject = try {
  JsonValue.parse(body) as? JsonObject ?: throw InvalidRequestException()
} catch (_: JsonSyntaxException) {
  throw InvalidRequestException()
}

/**
 * Reads a save, as validateWorkspaceRequest does and in its order: the revision, then the legacy profile
 * (LegacyRequestException), then the lists, packs and devices (InvalidRequestException). The limits of the lists and
 * the number of them are checked by the store, before it touches the database.
 */
fun decodeFields(fields: JsonObject): Pair<Long, Change> {
  val members = fields.members
  val revision = safeInteger(members["revision"]) ?: throw InvalidRequestException()
  if ("profileId" in members && members["profileId"] != JsonString("default")) throw LegacyRequestException()
  if ("profiles" in members) throw LegacyRequestException()
  val lists = members["lists"]
  val packs = members["packs"]
  val devices = members["devices"]
  // Packs only make sense with the lists that belong to them.
  if (packs != null && lists == null) throw InvalidRequestException()
  if (lists == null && devices == null) return revision to Change(emptyList(), emptyList(), hasLists = false, hasDevices = false)
  // null is not a left-out part: the app refuses it.
  if (lists == JsonNull || packs == JsonNull || devices == JsonNull) throw InvalidRequestException()
  if (listOfNotNull(lists, packs, devices).any(::unstorable)) throw InvalidRequestException()
  // Lists sent with packs are checked as they were sent.
  if (packs != null) {
    try {
      checkLibrary(lists!!, packs)
    } catch (_: WorkspaceError) {
      throw InvalidRequestException()
    }
  }
  // A part that is left out is read as empty, so that only the other one is checked.
  val decoded = try {
    decodeValues(lists ?: JsonArray(emptyList()), devices ?: JsonArray(emptyList()), packs)
  } catch (_: InvalidWorkspaceException) {
    throw InvalidRequestException()
  }
  if (packs == null) {
    // An app that does not know packs sends its lists with their labels, which are saved as it sent them.
    return revision to Change(decoded.lists, decoded.devices, hasLists = lists != null, hasDevices = devices != null)
  }
  // Go repairs the library before it is saved, which leaves one that checkLibrary accepts as it is but for the names of
  // its packs and lists, trimmed (their trimmed lowercase is already unique).
  return revision to Change(decoded.lists.map { it.copy(name = trimJS(it.name)) }, decoded.devices, hasLists = true,
    hasDevices = devices != null, packs = decoded.packs.map { it.copy(name = trimJS(it.name)) }, hasPacks = true)
}

/**
 * Whether a string or object name anywhere in value holds what PostgreSQL's jsonb cannot store, so that the save
 * would fail in the database instead of being refused: U+0000, or a UTF-16 surrogate that is not part of a pair. The
 * parser reads \u0000 and \ud800 as those units, and valid JSON can write them no other way, so this is the Go
 * backend's scan of the raw text.
 */
fun unstorable(value: JsonValue): Boolean = when (value) {
  is JsonString -> unstorable(value.value)
  is JsonArray -> value.items.any(::unstorable)
  is JsonObject -> value.members.any { (name, member) -> unstorable(name) || unstorable(member) }
  else -> false
}

private fun unstorable(text: String): Boolean {
  var i = 0
  while (i < text.length) {
    val c = text[i]
    when {
      c == '\u0000' -> return true
      c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i++
      c.isSurrogate() -> return true
    }
    i++
  }
  return false
}

/**
 * A JSON number that Number.isSafeInteger accepts and is not below 0, which is what a revision is. It is read as the
 * double JavaScript would make of it, so 1.0 and 1e2 are integers and 1e400 is none.
 */
fun safeInteger(value: JsonValue?): Long? {
  if (value !is JsonNumber) return null
  val number = value.literal.toDouble()
  if (number != Math.floor(number) || number < 0 || number > MAX_SAFE_INTEGER) return null
  return number.toLong()
}
