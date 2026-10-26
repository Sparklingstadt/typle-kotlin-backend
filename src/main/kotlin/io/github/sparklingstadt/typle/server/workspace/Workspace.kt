// Package workspace reads a Typle workspace: the lists, word packs and devices of an account, as the app saves them in
// the typle_workspaces table. The checks are those of the Go backend's server/internal/workspace (Decode), which follow
// the app's decodeLists, decodePacks and decodeDevices, so data the app would read is read here and data it would
// refuse is refused. As in Go, null in an optional field reads as the field left out. Unlike Go, lists saved before
// packs existed are not turned into packs here, nor are saved packs repaired: the app does that when it reads them, and
// /api/workspace answers what is stored (as Go's GET does). Of Go's repairs, only the separation of lists of a pack whose
// levels overlap (separateLevels) is made, on rows that have packs, where the server builds something from a saved row
// rather than answering it as stored: the snapshot of a shared link (readSaved, used by PutShare). A save with packs is
// still checked as it was sent, so overlaps there are refused (levelsConflict) and so is a level given twice in a list
// (levelDuplicate); on reading, a repeated level gets （n） first (distinctLevels).
package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue

private const val MAX_DEVICES = 100
private const val MAX_DEVICE_ID = 100
private const val MAX_DEVICE_NAME = 100
private const val MAX_DEVICE_LAYOUT = 200
const val DEFAULT_DEVICE_ID = "default"

// Each stored shape keeps the fields Typle does not know in extra: one JSON object with sorted names and compact
// values (see Encode.kt), or "" when there are none.

data class Word(val display: String, val input: String, val annotation: String, val extra: String = "")

data class Device(val id: String, val name: String, val layout: String, val extra: String = "")

/** A practice run as it is stored. Records saved by older versions may have no id, mistakes, accuracy, wordCount or device. */
data class Record(
  val id: String?, val time: Double, val date: String, val mistakes: Long?, val accuracy: Double?, val wordCount: Long?,
  val device: Device?, val extra: String = "",
)

/**
 * A list as it is stored. packId is the word pack it belongs to, "" when it has none (lists saved before packs existed).
 * group, category and tags are those of a list saved by an app that does not know packs: "" and empty when it has none,
 * which is how they are stored, left out. A list saved with packs has none of the three; its pack has the labels.
 */
data class WordList(
  val id: String, val name: String, val words: List<Word>, val records: List<Record>, val createdAt: String,
  val extra: String = "", val packId: String = "", val group: String = "", val category: String = "",
  val tags: List<String> = emptyList(),
)

/** Origin is set on a pack added from the official catalog. */
data class Origin(val catalogId: String, val version: Double)

/** A word pack. description and category are "" and tags empty when it has none, which is how they are stored: left out. */
data class Pack(
  val id: String, val name: String, val description: String = "", val category: String = "", val tags: List<String> = emptyList(),
  val origin: Origin? = null, val createdAt: String, val extra: String = "",
)

/** A workspace as read. packs is empty when the packs column is NULL (lists saved before packs existed). */
data class Workspace(val lists: List<WordList>, val devices: List<Device>, val packs: List<Pack> = emptyList())

/** Saved data, or a save, that the app would not read. */
class InvalidWorkspaceException(message: String) : Exception(message)

private fun invalid(message: String): Nothing = throw InvalidWorkspaceException(message)

/** Reads a saved row: its lists, devices and packs columns. packs is null (NULL) for lists saved before packs existed. */
fun decode(lists: String, devices: String, packs: String? = null): Workspace =
  decodeValues(parse(lists, "lists"), parse(devices, "devices"), packs?.let { parse(it, "packs") })

/**
 * A saved row as the server reads it to build an answer or new data from it, such as the snapshot of a shared link:
 * decode, with the lists of a row that has packs separated as Go's Decode separates them when it repairs saved packs
 * (separateLevels), so it reads them as Go does. A row without packs (packs null) is read as decode reads it, as Kotlin
 * does not make its packs. /api/workspace answers the row as stored and does not use this.
 */
fun readSaved(lists: String, devices: String, packs: String?): Workspace {
  val w = decode(lists, devices, packs)
  return if (packs == null) w else w.copy(lists = separateLevels(w.lists))
}

/** decode for lists, devices and packs already read as JSON, such as the parts of a save. */
fun decodeValues(lists: JsonValue, devices: JsonValue, packs: JsonValue? = null): Workspace {
  val decoded = decodeLists(lists)
  val rawDevices = when (val parsed = devices) {
    is JsonArray -> parsed.items
    JsonNull -> emptyList()
    else -> invalid("devices are not valid")
  }
  val registered = decodeDevices(rawDevices)
  return Workspace(decoded, registered, packs?.let(::decodePacks) ?: emptyList())
}

/** Reads lists as they were sent or saved: each keeps its packId, group, category and tags as they are. */
fun decodeLists(lists: JsonValue): List<WordList> {
  if (lists !is JsonArray) invalid("lists must be an array")
  return decodeLists(lists.items)
}

/** decodePacks (src/core/wordPacks.ts), as Go's decodePacks. */
fun decodePacks(packs: JsonValue): List<Pack> {
  if (packs == JsonNull) invalid("packs must be an array")
  if (packs !is JsonArray) invalid("packs are not valid")
  val ids = HashSet<String>()
  return packs.items.mapIndexed { index, item ->
    val pack = try {
      decodePack(item)
    } catch (e: InvalidWorkspaceException) {
      invalid("pack ${index + 1}: ${e.message}")
    }
    if (!ids.add(pack.id)) invalid("pack ${index + 1} is invalid")
    pack
  }
}

private fun decodePack(raw: JsonValue): Pack {
  val fields = fieldsOf(raw) ?: invalid("is invalid")
  val id = optionalString(fields, "id")
  val name = optionalString(fields, "name")
  val createdAt = optionalString(fields, "createdAt")
  val origin = when (val value = fields.members["origin"]) {
    null, JsonNull -> null
    is JsonObject -> {
      val catalogId = optionalString(value, "catalogId")
      val version = optionalFloat(value, "version")
      if (catalogId == null || version == null) invalid("is invalid")
      Origin(catalogId, version)
    }
    else -> invalid("origin must be an object")
  }
  if (id.isNullOrEmpty() || name == null || createdAt == null) invalid("is invalid")
  return Pack(id, name, optionalString(fields, "description") ?: "", optionalString(fields, "category") ?: "",
    optionalStrings(fields, "tags"), origin, createdAt,
    unknown(fields, "id", "name", "description", "category", "tags", "origin", "createdAt"))
}

private fun parse(text: String, part: String): JsonValue = try {
  JsonValue.parse(text)
} catch (e: JsonSyntaxException) {
  invalid("$part are not valid: ${e.message}")
}

private fun decodeLists(raw: List<JsonValue>): List<WordList> {
  val ids = HashSet<String>()
  return raw.mapIndexed { index, item ->
    val list = try {
      decodeList(item)
    } catch (e: InvalidWorkspaceException) {
      invalid("list ${index + 1}: ${e.message}")
    }
    if (!ids.add(list.id)) invalid("list ${index + 1}: id \"${list.id}\" is used twice")
    list
  }
}

private fun decodeList(raw: JsonValue): WordList {
  val fields = fieldsOf(raw) ?: invalid("id, name, createdAt, words and records are required")
  val id = optionalString(fields, "id")
  val name = optionalString(fields, "name")
  val createdAt = optionalString(fields, "createdAt")
  val words = optionalArray(fields, "words")
  val records = optionalArray(fields, "records")
  if (id.isNullOrEmpty() || name == null || createdAt == null || words == null || records == null) {
    invalid("id, name, createdAt, words and records are required")
  }
  // validateList: a name, at least one word, and no word without text to show or type.
  if (trimJS(name) == "") invalid("name is empty")
  if (words.isEmpty()) invalid("words are empty")
  val decodedWords = words.mapIndexed { index, item ->
    val word = fieldsOf(item)
    val display = word?.let { optionalString(it, "display") }
    val input = word?.let { optionalString(it, "input") }
    val annotation = word?.let { optionalString(it, "annotation") }
    if (word == null || display == null || input == null || annotation == null) {
      invalid("word ${index + 1}: display, input and annotation are required")
    }
    if (trimJS(display) == "" || trimJS(input) == "") invalid("word ${index + 1}: display and input must not be empty")
    Word(display, input, annotation, unknown(word, "display", "input", "annotation"))
  }
  val decodedRecords = records.mapIndexed { index, item ->
    try {
      decodeRecord(item)
    } catch (e: InvalidWorkspaceException) {
      invalid("record ${index + 1}: ${e.message}")
    }
  }
  // The labels are optional; decodeLists checks only that they are a string and strings.
  return WordList(id, name, decodedWords, decodedRecords, createdAt,
    unknown(fields, "id", "name", "category", "tags", "group", "packId", "createdAt", "words", "records", "shared", "shareId"),
    packId = optionalString(fields, "packId") ?: "", group = optionalString(fields, "group") ?: "",
    category = optionalString(fields, "category") ?: "", tags = optionalStrings(fields, "tags"))
}

private fun decodeRecord(raw: JsonValue): Record {
  val fields = fieldsOf(raw)
  val time = fields?.let { optionalFloat(it, "time") }
  val date = fields?.let { optionalString(it, "date") }
  if (fields == null || time == null || time < 0 || date == null) {
    invalid("time must be a number of 0 or more and date is required")
  }
  val id = optionalString(fields, "id")
  val mistakes = optionalInteger(fields, "mistakes")
  val accuracy = optionalFloat(fields, "accuracy")
  val wordCount = optionalInteger(fields, "wordCount")
  val device = when (val value = fields.members["device"]) {
    null, JsonNull -> null
    else -> recordDevice(value)
  }
  if (parseTime(date) == null) invalid("date \"$date\" is not a supported date")
  if (accuracy != null && (accuracy < 0 || accuracy > 100)) invalid("accuracy must be between 0 and 100")
  if (mistakes != null && mistakes < 0) invalid("mistakes must be 0 or more")
  if (wordCount != null && wordCount < 1) invalid("wordCount must be 1 or more")
  if (device != null && !isDevice(device)) invalid("device is invalid")
  return Record(id, time, date, mistakes, accuracy, wordCount, device,
    unknown(fields, "id", "time", "date", "mistakes", "accuracy", "wordCount", "device"))
}

// A device copied into a record is Go's Device with plain string fields: a field left out or null is "".
private fun recordDevice(raw: JsonValue): Device {
  if (raw !is JsonObject) invalid("device is invalid")
  fun plain(name: String) = optionalString(raw, name) ?: ""
  return Device(plain("id"), plain("name"), plain("layout"), unknown(raw, "id", "name", "layout"))
}

private fun isDevice(device: Device) =
  device.id != "" && utf16Length(device.id) <= MAX_DEVICE_ID &&
    trimJS(device.name) != "" && utf16Length(device.name) <= MAX_DEVICE_NAME &&
    utf16Length(device.layout) <= MAX_DEVICE_LAYOUT

private fun decodeDevices(raw: List<JsonValue>): List<Device> {
  if (raw.size > MAX_DEVICES) invalid("devices are invalid")
  val devices = ArrayList<Device>()
  for (item in raw) {
    val fields = try {
      fieldsOf(item)?.let { Triple(optionalString(it, "id"), optionalString(it, "name"), optionalString(it, "layout")) }
    } catch (_: InvalidWorkspaceException) {
      null
    }
    val (id, name, layout) = fields ?: invalid("devices are invalid")
    if (id == null || name == null || layout == null) invalid("devices are invalid")
    val device = Device(id, name, layout)
    // The built-in device is never stored with the registered ones.
    if (!isDevice(device) || id == DEFAULT_DEVICE_ID || devices.any { it.id == id }) invalid("devices are invalid")
    devices.add(Device(id, trimJS(name), trimJS(layout)))
  }
  return devices
}

// Go's json.Unmarshal into a pointer to a struct: null leaves it nil, an object fills it, anything else is an error.
private fun fieldsOf(value: JsonValue): JsonObject? = when (value) {
  JsonNull -> null
  is JsonObject -> value
  else -> invalid("expected an object")
}

// Go's *string: left out or null is nil, a string is itself, anything else is an error.
private fun optionalString(fields: JsonObject, name: String): String? = when (val value = fields.members[name]) {
  null, JsonNull -> null
  is JsonString -> value.value
  else -> invalid("$name must be a string")
}

// Go's []*string: left out or null is empty, an array of strings is itself, and a null in it or anything else is an
// error.
private fun optionalStrings(fields: JsonObject, name: String): List<String> =
  optionalArray(fields, name)?.map { (it as? JsonString)?.value ?: invalid("$name must be strings") } ?: emptyList()

private fun optionalArray(fields: JsonObject, name: String): List<JsonValue>? = when (val value = fields.members[name]) {
  null, JsonNull -> null
  is JsonArray -> value.items
  else -> invalid("$name must be an array")
}

// Go's *float64: any JSON number that is a finite double.
private fun optionalFloat(fields: JsonObject, name: String): Double? = when (val value = fields.members[name]) {
  null, JsonNull -> null
  is JsonNumber -> value.literal.toDouble().takeIf { it.isFinite() } ?: invalid("$name is out of range")
  else -> invalid("$name must be a number")
}

// Go's *int: only a number without a fraction or an exponent that fits in 64 bits.
private fun optionalInteger(fields: JsonObject, name: String): Long? = when (val value = fields.members[name]) {
  null, JsonNull -> null
  is JsonNumber -> value.literal.toLongOrNull() ?: invalid("$name must be an integer")
  else -> invalid("$name must be a number")
}
