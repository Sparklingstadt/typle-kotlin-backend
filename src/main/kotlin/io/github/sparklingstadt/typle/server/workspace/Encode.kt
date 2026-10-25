package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.GoJson
import io.github.sparklingstadt.typle.server.json.JsonObject

// The limits of validateLists (src/core/workspaceRequest.ts) and MAX_LISTS (src/core/listOverview.ts). Lengths are in
// UTF-16 code units, as JavaScript measures them.
const val MAX_LISTS = 150
const val MAX_LIST_NAME = 200
const val MAX_WORDS = 1000
const val MAX_WORD_TEXT = 1000
const val MAX_ANNOTATION = 2000
const val MAX_RECORDS = 10000
const val MAX_CATEGORY = 50
const val MAX_TAG = 50
const val MAX_TAGS = 20

/** A change that cannot be made; key is the error key the API answers with. */
class WorkspaceError(val key: String) : Exception(key)

/**
 * Refuses lists past the limits of validateLists, which the app applies to every save. The number of lists is not
 * checked here: it is compared with the saved ones when the change is written.
 */
fun checkLimits(lists: List<WordList>) {
  for (list in lists) {
    if (list.words.size > MAX_WORDS || list.records.size > MAX_RECORDS || utf16Length(list.name) > MAX_LIST_NAME ||
      utf16Length(list.category) > MAX_CATEGORY || list.tags.size > MAX_TAGS || list.tags.any { utf16Length(it) > MAX_TAG }) {
      throw WorkspaceError("invalidWorkspace")
    }
    for (word in list.words) {
      if (utf16Length(word.input) > MAX_WORD_TEXT || utf16Length(word.display) > MAX_WORD_TEXT ||
        utf16Length(word.annotation) > MAX_ANNOTATION) {
        throw WorkspaceError("invalidWorkspace")
      }
    }
  }
}

/** Writes lists as the app saves them in the lists column, as Go's workspace.Encode does. */
fun encode(lists: List<WordList>): String = lists.joinToString(",", "[", "]") { list ->
  object_(list.extra,
    "id" to str(list.id), "name" to str(list.name),
    "category" to list.category.takeIf { it.isNotEmpty() }?.let(::str),
    "tags" to list.tags.takeIf { it.isNotEmpty() }?.joinToString(",", "[", "]", transform = ::str),
    "createdAt" to str(list.createdAt),
    "words" to list.words.joinToString(",", "[", "]") { word ->
      object_(word.extra, "display" to str(word.display), "input" to str(word.input), "annotation" to str(word.annotation))
    },
    "records" to list.records.joinToString(",", "[", "]", transform = ::record),
  )
}

/** Writes the registered devices as the app saves them in the devices column. */
fun encodeDevices(devices: List<Device>): String = devices.joinToString(",", "[", "]", transform = ::device)

// A record leaves out what it does not have.
private fun record(r: Record): String = object_(r.extra,
  "id" to r.id?.let(::str), "time" to GoJson.number(r.time), "date" to str(r.date),
  "mistakes" to r.mistakes?.toString(), "accuracy" to r.accuracy?.let(GoJson::number),
  "wordCount" to r.wordCount?.toString(), "device" to r.device?.let(::device),
)

private fun device(d: Device): String = object_(d.extra, "id" to str(d.id), "name" to str(d.name), "layout" to str(d.layout))

private fun str(value: String) = GoJson.string(value, escapeHtml = false)

// The known fields in order, a null value left out, followed by the unknown ones.
private fun object_(extra: String, vararg fields: Pair<String, String?>): String {
  val known = fields.filter { it.second != null }.joinToString(",") { "\"${it.first}\":${it.second}" }
  return if (extra.isEmpty()) "{$known}" else "{$known,${extra.substring(1)}"
}

/**
 * The fields of an object other than known, as one JSON object with names sorted (by their UTF-8 bytes, as Go sorts
 * them) and compact values, or "" when there are none. Strings in the values are written anew, so an escape may be
 * spelled otherwise than it was stored; jsonb stores both alike.
 */
internal fun unknown(fields: JsonObject, vararg known: String): String {
  val names = fields.members.keys.filter { it !in known }
  if (names.isEmpty()) return ""
  val sorted = names.sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }
  return sorted.joinToString(",", "{", "}") { str(it) + ":" + fields.members.getValue(it).toJson() }
}

private fun compareBytes(a: ByteArray, b: ByteArray): Int {
  for (i in 0 until minOf(a.size, b.size)) {
    val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
    if (diff != 0) return diff
  }
  return a.size - b.size
}
