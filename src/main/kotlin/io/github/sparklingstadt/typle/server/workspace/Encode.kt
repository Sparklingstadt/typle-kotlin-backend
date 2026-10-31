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

// The limits of a category and tags (MAX_CATEGORY, MAX_TAG and MAX_TAGS in src/core/wordLists.ts).
const val MAX_CATEGORY = 50
const val MAX_TAG = 50
const val MAX_TAGS = 20

// The limit of the group a list saved by an app that does not know packs may have (MAX_GROUP in src/core/wordLists.ts):
// it becomes the name of a pack when the lists are read.
const val MAX_GROUP = 200

/** A change that cannot be made; key is the error key the API answers with. */
class WorkspaceError(val key: String) : Exception(key)

/**
 * Refuses lists past the limits of validateLists, which the app applies to every save. The group, category and tags are
 * those of lists sent by an app that does not know packs. The number of lists is not checked here: it is compared with
 * the saved ones when the change is written.
 */
fun checkLimits(lists: List<WordList>) {
  for (list in lists) {
    if (list.words.size > MAX_WORDS || list.records.size > MAX_RECORDS || utf16Length(list.name) > MAX_LIST_NAME ||
      utf16Length(list.category) > MAX_CATEGORY || list.tags.size > MAX_TAGS || list.tags.any { utf16Length(it) > MAX_TAG } ||
      utf16Length(list.group) > MAX_GROUP) {
      throw WorkspaceError("invalidWorkspace")
    }
    for (word in list.words) {
      if (utf16Length(word.input) > MAX_WORD_TEXT || utf16Length(word.display) > MAX_WORD_TEXT ||
        utf16Length(word.annotation) > MAX_ANNOTATION || !validInputType(word.inputType)) {
        throw WorkspaceError("invalidWorkspace")
      }
    }
  }
}

/**
 * Writes lists as the app saves them in the lists column, as Go's workspace.Encode does. The category and tags belong to
 * the list's pack, so they are left out (see encodePacks), and so is the group.
 */
fun encode(lists: List<WordList>): String = lists.joinToString(",", "[", "]") { list ->
  object_(list.extra,
    "id" to str(list.id), "name" to str(list.name), "packId" to list.packId.ifEmpty { null }?.let(::str),
    "createdAt" to str(list.createdAt),
    "words" to list.words.joinToString(",", "[", "]") { word ->
      object_(word.extra, "display" to str(word.display), "input" to str(word.input), "annotation" to str(word.annotation),
        "input_type" to word.inputType.ifEmpty { null }?.let(::str))
    },
    "records" to list.records.joinToString(",", "[", "]", transform = ::record),
  )
}

/** Writes one record as a list's records array holds it (a row of typle_records), as Go's json.Marshal of a Record. */
fun encodeRecord(r: Record): String = record(r)

/** Writes packs as the app saves them in the packs column, as Go's workspace.EncodePacks does. */
fun encodePacks(packs: List<Pack>): String = packs.joinToString(",", "[", "]") { pack ->
  object_(pack.extra,
    "id" to str(pack.id), "name" to str(pack.name), "description" to pack.description.ifEmpty { null }?.let(::str),
    "category" to pack.category.ifEmpty { null }?.let(::str), "tags" to pack.tags.ifEmpty { null }?.let(::strings),
    "origin" to pack.origin?.let {
      object_("", "catalogId" to str(it.catalogId), "version" to GoJson.number(it.version), "followed" to (if (it.followed) "true" else null))
    },
    "createdAt" to str(pack.createdAt),
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

private fun strings(values: List<String>) = values.joinToString(",", "[", "]", transform = ::str)

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
