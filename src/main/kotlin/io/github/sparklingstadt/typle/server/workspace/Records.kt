package io.github.sparklingstadt.typle.server.workspace

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// Changing one practice record at a time follows Go's server/internal/workspace/records.go (AddRecord and DeleteRecord),
// which follow addRecord and removeRecord (src/core/records.ts). The fixtures of typle-r's tests/fixtures/records and
// tests/fixtures/record-operations (copied to src/test/resources) are checked by the app, Go and Kotlin.

private const val MAX_RECORD_ID = 100

// The built-in device's name in the app's base language (format.defaultDevice in src/messages/ja).
private const val DEFAULT_DEVICE_NAME = "デフォルト"

// Integers stay within JavaScript's safe integers, so the app reads them back exactly.
private const val MAX_SAFE_INTEGER = 9007199254740991.0

private val recordIdPattern = Regex("^[A-Za-z0-9_-]+$")

private val isoFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * A practice run as a request gives it (Go's RecordInput). Numbers are read as numbers so that a value such as 1.5
 * mistakes is refused as an invalid record. The device is either named by deviceId, and saved as it is registered when
 * the run is added, or given as device, as it was when the run was practised.
 */
data class RecordInput(
  val id: String? = null, val time: Double? = null, val date: String? = null, val mistakes: Double? = null,
  val accuracy: Double? = null, val wordCount: Double? = null, val deviceId: String? = null, val device: Device? = null,
)

/** A new id in the form of crypto.randomUUID, as Go's NewID gives a record. */
fun newId(): String = UUID.randomUUID().toString()

/** A time written as JavaScript's toISOString writes it (Go's FormatTime). */
fun formatTime(at: Instant): String = isoFormat.format(at)

/**
 * Adds a run to a list, as Go's AddRecord. A run with the id of one the list already has is not added again: that saved
 * record is returned with added false, so a request can be sent again safely. Throws WorkspaceError: listNotFound,
 * invalidRecord, recordLimit or deviceNotFound.
 */
fun addRecord(w: Workspace, listId: String, input: RecordInput, newId: String): Triple<Workspace, Record, Boolean> {
  val at = w.lists.indexOfFirst { it.id == listId }
  if (at < 0) throw WorkspaceError("listNotFound")
  val list = w.lists[at]
  fun invalid(): Nothing = throw WorkspaceError("invalidRecord")
  if (!validTime(input.time) || input.date == null || !validAccuracy(input.accuracy)) invalid()
  val date = recordDate(input.date) ?: invalid()
  val mistakes = integer(input.mistakes, 0) ?: invalid()
  val wordCount = if (input.wordCount == null) list.words.size.toLong() else integer(input.wordCount, 1) ?: invalid()
  val id = input.id ?: newId
  if (input.id != null && (utf16Length(input.id) > MAX_RECORD_ID || !recordIdPattern.matches(input.id))) invalid()
  findRecord(list, id)?.let { return Triple(w, it, false) }
  if (list.records.size >= MAX_RECORDS) throw WorkspaceError("recordLimit")
  val device = when {
    input.device != null && (input.deviceId != null || !isDevice(input.device)) -> invalid()
    input.device != null -> Device(input.device.id, input.device.name, input.device.layout)
    input.deviceId != null -> findDevice(w, input.deviceId) ?: throw WorkspaceError("deviceNotFound")
    else -> null
  }
  val record = Record(id, input.time!!, formatTime(date), mistakes, input.accuracy!!, wordCount, device)
  val lists = w.lists.toMutableList().also { it[at] = list.copy(records = list.records + record) }
  return Triple(w.copy(lists = lists), record, true)
}

/** Removes a saved run, as Go's DeleteRecord. Throws WorkspaceError listNotFound or recordNotFound. */
fun deleteRecord(w: Workspace, listId: String, recordId: String): Workspace {
  val at = w.lists.indexOfFirst { it.id == listId }
  if (at < 0) throw WorkspaceError("listNotFound")
  val list = w.lists[at]
  val i = list.records.indexOfFirst { recordId != "" && it.id == recordId }
  if (i < 0) throw WorkspaceError("recordNotFound")
  val lists = w.lists.toMutableList().also { it[at] = list.copy(records = list.records.filterIndexed { n, _ -> n != i }) }
  return w.copy(lists = lists)
}

/** The list's record with this id, or null. Records saved without an id cannot be found (Go's FindRecord). */
fun findRecord(list: WordList, id: String): Record? = list.records.firstOrNull { it.id != null && id != "" && it.id == id }

private fun finite(value: Double?) = value != null && value.isFinite()

private fun validTime(value: Double?) = finite(value) && value!! >= 0

private fun validAccuracy(value: Double?) = finite(value) && value!! >= 0 && value <= 100

// The value as a whole number from min up to the largest safe integer, or null (Go's integer).
private fun integer(value: Double?, min: Int): Long? {
  if (!finite(value) || value!! != Math.floor(value) || value < min || value > MAX_SAFE_INTEGER) return null
  return value.toLong()
}

// A record's date (Go's recordDate): saved in UTC, it must stay within the years toISOString writes as four digits.
private fun recordDate(value: String): Instant? {
  val date = parseTime(value) ?: return null
  val year = date.atZone(ZoneOffset.UTC).year
  return if (year in 0..9999) date else null
}

// A copy of a registered device, or of the built-in one (Go's findDevice).
private fun findDevice(w: Workspace, id: String): Device? {
  if (id == DEFAULT_DEVICE_ID) return Device(DEFAULT_DEVICE_ID, DEFAULT_DEVICE_NAME, "")
  return w.devices.firstOrNull { it.id == id }
}
