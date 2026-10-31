package io.github.sparklingstadt.typle.server.store

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue
import io.github.sparklingstadt.typle.server.workspace.Record
import io.github.sparklingstadt.typle.server.workspace.WordList
import io.github.sparklingstadt.typle.server.workspace.encodeRecord
import io.github.sparklingstadt.typle.server.workspace.parseTime
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant

// Practice records are kept in typle_records, one row each, rather than in their lists, as Go's
// server/internal/store/records.go (typle-r v1.3.0, docs/superpowers/specs/2026-10-29-record-table.md). A workspace
// whose records_split is false still has them in its lists, as saved before the table existed: the first write that
// saves its lists moves them. Reads put the records back in their lists, so everything above the store sees lists with
// their records, as before.

/** A record as typle_records keeps it: data is the record as its list held it, with its id; position its place. */
internal data class RecordRow(val listId: String, val recordId: String, val position: Int, val at: Instant, val data: String)

internal data class RecordKey(val listId: String, val recordId: String)

/**
 * The id given to a record saved without one, or with the id of an earlier record of its list, as Go's legacyID: the
 * same record gets the same id however often its list is saved or moved.
 */
internal fun legacyId(listId: String, position: Int, record: Record): String {
  val digest = MessageDigest.getInstance("SHA-256")
    .digest((listId + "\u0000" + position + "\u0000" + record.date + "\u0000" + formatG(record.time)).toByteArray())
  return "legacy-" + digest.joinToString("") { "%02x".format(it) }.take(20)
}

/**
 * Every record of the lists with an id that keeps it apart from the rest of its list (see legacyId), and the records as
 * rows, as Go's recordRows.
 */
internal fun recordRows(lists: List<WordList>): Pair<List<WordList>, List<RecordRow>> {
  val rows = ArrayList<RecordRow>()
  val identified = lists.map { list ->
    val seen = HashSet<String>()
    list.copy(records = list.records.mapIndexed { position, given ->
      val record = if (given.id.isNullOrEmpty() || given.id in seen) given.copy(id = legacyId(list.id, position, given)) else given
      seen.add(record.id!!)
      val at = parseTime(record.date) ?: throw IllegalStateException("record ${record.id} of list ${list.id} has no date")
      rows.add(RecordRow(list.id, record.id, position, at, encodeRecord(record)))
      record
    })
  }
  return identified to rows
}

/** The lists with no records, as the lists column keeps them once the records moved (Go's withoutRecords). */
internal fun withoutRecords(lists: List<WordList>): List<WordList> = lists.map { it.copy(records = emptyList()) }

/**
 * The rows of typle_records of a workspace whose records moved, as one JSON array of [listId, record] in order, read in
 * the same statement (and so the same snapshot) as the row; NULL when they have not (Go's recordsColumn).
 */
internal const val RECORDS_COLUMN = """CASE WHEN records_split THEN (SELECT COALESCE(jsonb_agg(jsonb_build_array(r.list_id, r.data)
  ORDER BY r.list_id, r.position), '[]'::jsonb) FROM typle_records r WHERE r.owner_id = typle_workspaces.owner_id)::text END"""

/** RECORDS_COLUMN read: each list's records, in order (Go's decodeColumn). */
internal fun decodeColumn(column: String): Map<String, List<JsonValue>> {
  val records = LinkedHashMap<String, MutableList<JsonValue>>()
  for (pair in (JsonValue.parse(column) as JsonArray).items) {
    val (listId, record) = (pair as JsonArray).items
    records.getOrPut((listId as JsonString).value) { ArrayList() }.add(record)
  }
  return records
}

/**
 * The stored records put back in the lists that hold them, as Go's withRecords. A list may still hold records of its
 * own: an app from before the table can have saved them while a deployment switched over. They come first, and a record
 * of the same id is the table's. Rows of a list that is no longer saved are left out; the next write deletes them. As in
 * Go, the lists are written again with their keys sorted.
 */
internal fun withRecords(lists: String, records: Map<String, List<JsonValue>>): String {
  val objects = (JsonValue.parse(lists) as? JsonArray ?: throw IllegalStateException("lists are not valid")).items
  return JsonArray(objects.map { item ->
    val members = LinkedHashMap((item as JsonObject).members)
    val moved = records[(members["id"] as? JsonString)?.value].orEmpty()
    if (moved.isNotEmpty()) {
      val own = when (val raw = members["records"]) {
        null, JsonNull -> emptyList()
        is JsonArray -> raw.items
        else -> throw IllegalStateException("records of a list are not valid")
      }
      val ids = moved.map(::recordId).toSet()
      members["records"] = JsonArray(own.filter { recordId(it).let { id -> id == "" || id !in ids } } + moved)
    }
    JsonObject(members.toSortedMap { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) })
  }).toJson()
}

private fun recordId(record: JsonValue): String = ((record as? JsonObject)?.members?.get("id") as? JsonString)?.value ?: ""

private fun compareBytes(a: ByteArray, b: ByteArray): Int {
  for (i in 0 until minOf(a.size, b.size)) {
    val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
    if (diff != 0) return diff
  }
  return a.size - b.size
}

/** The owner's rows of typle_records, by list in order, as Go's readRecords. */
internal fun readRecords(db: Connection, owner: String): List<RecordRow> =
  db.prepareStatement("SELECT list_id, record_id, position, at, data::text FROM typle_records WHERE owner_id = ? ORDER BY list_id, position").use { q ->
    q.setString(1, owner)
    q.executeQuery().use { r ->
      buildList {
        while (r.next()) {
          add(RecordRow(r.getString(1), r.getString(2), r.getInt(3), r.getObject(4, java.time.OffsetDateTime::class.java).toInstant(), r.getString(5)))
        }
      }
    }
  }

/**
 * Makes the owner's rows of typle_records the rows given, as Go's syncRecords. With the rows as they were read (before),
 * only the rows that changed are written and only those no longer there are deleted. Without (null), every row given is
 * written and every other row of the owner deleted. Either way the number of rows is then checked against the rows
 * given, so that records are never lost or doubled unnoticed: the transaction, and the save with it, fails instead.
 */
internal fun syncRecords(db: Connection, owner: String, before: Map<RecordKey, RecordRow>?, rows: List<RecordRow>) {
  val after = rows.map { RecordKey(it.listId, it.recordId) }.toSet()
  val write = rows.filter { row ->
    val saved = before?.get(RecordKey(row.listId, row.recordId))
    before == null || saved == null || saved.position != row.position || saved.data != row.data
  }
  if (before == null) {
    db.prepareStatement("""DELETE FROM typle_records r WHERE r.owner_id = ? AND NOT EXISTS (SELECT 1 FROM
      unnest(?::text[], ?::text[]) AS k(list_id, record_id) WHERE k.list_id = r.list_id AND k.record_id = r.record_id)""").use { q ->
      q.setString(1, owner)
      q.setArray(2, db.createArrayOf("text", rows.map { it.listId }.toTypedArray()))
      q.setArray(3, db.createArrayOf("text", rows.map { it.recordId }.toTypedArray()))
      q.executeUpdate()
    }
  } else {
    val gone = before.filterKeys { it !in after }.values
    if (gone.isNotEmpty()) {
      db.prepareStatement("""DELETE FROM typle_records r USING unnest(?::text[], ?::text[]) AS k(list_id, record_id)
        WHERE r.owner_id = ? AND r.list_id = k.list_id AND r.record_id = k.record_id""").use { q ->
        q.setArray(1, db.createArrayOf("text", gone.map { it.listId }.toTypedArray()))
        q.setArray(2, db.createArrayOf("text", gone.map { it.recordId }.toTypedArray()))
        q.setString(3, owner)
        q.executeUpdate()
      }
    }
  }
  if (write.isNotEmpty()) {
    db.prepareStatement("""INSERT INTO typle_records (owner_id, list_id, record_id, position, at, data)
      SELECT ?, k.list_id, k.record_id, k.position, k.at, k.data::jsonb
      FROM unnest(?::text[], ?::text[], ?::int[], ?::timestamptz[], ?::text[]) AS k(list_id, record_id, position, at, data)
      ON CONFLICT (owner_id, list_id, record_id) DO UPDATE SET position = EXCLUDED.position, at = EXCLUDED.at, data = EXCLUDED.data
      WHERE (typle_records.position, typle_records.at, typle_records.data) IS DISTINCT FROM (EXCLUDED.position, EXCLUDED.at, EXCLUDED.data)""").use { q ->
      q.setString(1, owner)
      q.setArray(2, db.createArrayOf("text", write.map { it.listId }.toTypedArray()))
      q.setArray(3, db.createArrayOf("text", write.map { it.recordId }.toTypedArray()))
      q.setArray(4, db.createArrayOf("int4", write.map { it.position }.toTypedArray()))
      q.setArray(5, db.createArrayOf("text", write.map { it.at.toString() }.toTypedArray()))
      q.setArray(6, db.createArrayOf("text", write.map { it.data }.toTypedArray()))
      q.executeUpdate()
    }
  }
  val count = db.prepareStatement("SELECT count(*) FROM typle_records WHERE owner_id = ?").use { q ->
    q.setString(1, owner)
    q.executeQuery().use { r -> r.next(); r.getInt(1) }
  }
  if (count != rows.size) throw IllegalStateException("records of $owner: $count rows saved for ${rows.size} records")
}

/** Deletes the owner's rows when its workspace row is created, as Go's forgetRecords. */
internal fun forgetRecords(db: Connection, owner: String) {
  db.prepareStatement("DELETE FROM typle_records WHERE owner_id = ?").use { q ->
    q.setString(1, owner)
    q.executeUpdate()
  }
}

/**
 * A number as Go's strconv.FormatFloat(value, 'g', -1, 64) writes it, which legacyId hashes: the shortest digits that
 * read back as value, in %e form (two exponent digits at least) when the exponent is below -4 or 6 and more, and in %f
 * form otherwise.
 */
internal fun formatG(value: Double): String {
  if (value == 0.0) return if (1 / value < 0) "-0" else "0"
  if (value.isNaN()) return "NaN"
  if (value.isInfinite()) return if (value > 0) "+Inf" else "-Inf"
  // Java's Double.toString gives the shortest digits that read back as value (since JDK 19).
  val decimal = BigDecimal(value.toString()).stripTrailingZeros()
  val sign = if (decimal.signum() < 0) "-" else ""
  val digits = decimal.unscaledValue().abs().toString()
  val dp = digits.length - decimal.scale() // the decimal point's place after the first digit, as Go's digs.dp
  // With the shortest digits Go decides by a precision of 6.
  val exp = dp - 1
  if (exp < -4 || exp >= 6) {
    val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
    val e = if (exp < 0) "-" else "+"
    return "$sign${mantissa}e$e${kotlin.math.abs(exp).toString().padStart(2, '0')}"
  }
  val plain = when {
    dp <= 0 -> "0." + "0".repeat(-dp) + digits
    dp >= digits.length -> digits + "0".repeat(dp - digits.length)
    else -> digits.substring(0, dp) + "." + digits.substring(dp)
  }
  return sign + plain
}
