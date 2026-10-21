// Package workspace reads a Typle workspace: the lists and devices of an account, as the app saves them in the
// typle_workspaces table. The checks are those of the Go backend's server/internal/workspace (Decode), which follow
// the app's decodeLists and decodeDevices, so data the app would read is read here and data it would refuse is
// refused. As in Go, null in an optional field reads as the field left out.
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

data class WordList(
  val id: String, val name: String, val words: List<Word>, val records: List<Record>, val createdAt: String,
  val extra: String = "",
)

data class Workspace(val lists: List<WordList>, val devices: List<Device>)

/** Saved data, or a save, that the app would not read. */
class InvalidWorkspaceException(message: String) : Exception(message)

private fun invalid(message: String): Nothing = throw InvalidWorkspaceException(message)

/** Reads a saved row: its lists and devices columns. */
fun decode(lists: String, devices: String): Workspace = decodeValues(parse(lists, "lists"), parse(devices, "devices"))

/** decode for lists and devices already read as JSON, such as the parts of a save. */
fun decodeValues(lists: JsonValue, devices: JsonValue): Workspace {
  val rawLists = lists
  if (rawLists !is JsonArray) invalid("lists must be an array")
  val rawDevices = when (val parsed = devices) {
    is JsonArray -> parsed.items
    JsonNull -> emptyList()
    else -> invalid("devices are not valid")
  }
  return Workspace(decodeLists(rawLists.items), decodeDevices(rawDevices))
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
  return WordList(id, name, decodedWords, decodedRecords, createdAt,
    unknown(fields, "id", "name", "createdAt", "words", "records", "shared", "shareId"))
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
