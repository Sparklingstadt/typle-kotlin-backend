package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The fixtures are copies of typle-r's tests/fixtures/records and tests/fixtures/record-operations, which the app
// (tests/records.test.cjs) and Go (server/internal/workspace/records_test.go) check against.
private fun fixtures(directory: String): List<Pair<String, JsonObject>> =
  File(RecordsTest::class.java.getResource("/$directory")!!.toURI()).listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }
    .map { it.name to JsonValue.parse(it.readText()) as JsonObject }

private fun JsonObject.member(name: String) = members.getValue(name)

// A fixture's input, read as the request's record is (every field optional here).
private fun inputOf(value: JsonValue): RecordInput {
  val fields = (value as JsonObject).members
  fun text(name: String) = (fields[name] as? JsonString)?.value
  fun number(name: String) = (fields[name] as? JsonNumber)?.literal?.toDouble()
  val device = (fields["device"] as? JsonObject)?.members?.let { d ->
    Device((d["id"] as JsonString).value, (d["name"] as JsonString).value, (d["layout"] as JsonString).value)
  }
  return RecordInput(text("id"), number("time"), text("date"), number("mistakes"), number("accuracy"), number("wordCount"),
    text("deviceId"), device)
}

private fun workspaceOf(f: JsonObject) = decodeValues(f.member("lists"), f.member("devices"))

class RecordsTest {
  @Test fun addsRecordsAsTheFixtures() {
    for ((name, f) in fixtures("records")) {
      val expected = f.member("expected") as JsonObject
      val listId = (f.member("listId") as JsonString).value
      val error = expected.members["error"] as? JsonString
      if (error != null) {
        val thrown = assertFailsWith<WorkspaceError>(name) { addRecord(workspaceOf(f), listId, inputOf(f.member("input")), "new") }
        assertEquals(error.value, thrown.key, name)
        continue
      }
      val (w, record, added) = addRecord(workspaceOf(f), listId, inputOf(f.member("input")), "new")
      assertEquals(expected.member("added").toJson(), added.toString(), name)
      assertEquals(expected.member("record"), JsonValue.parse(encodeRecord(record)), name)
      if (added) assertEquals(record, w.lists.single { it.id == listId }.records.last(), name)
    }
  }

  @Test fun followsTheOperations() {
    for ((name, f) in fixtures("record-operations")) {
      var w = workspaceOf(f)
      for ((n, raw) in (f.member("steps") as JsonArray).items.withIndex()) {
        val step = raw as JsonObject
        val expected = step.member("expected") as JsonObject
        val listId = (step.member("listId") as JsonString).value
        val at = "$name step ${n + 1}"
        when ((step.member("op") as JsonString).value) {
          "add" -> {
            val error = expected.members["error"] as? JsonString
            if (error != null) {
              assertEquals(error.value, assertFailsWith<WorkspaceError>(at) { addRecord(w, listId, inputOf(step.member("input")), "new") }.key, at)
            } else {
              val (next, _, added) = addRecord(w, listId, inputOf(step.member("input")), "new")
              assertEquals(expected.member("added").toJson(), added.toString(), at)
              w = next
            }
          }
          "delete" -> {
            val recordId = (step.member("recordId") as JsonString).value
            val deleted = try {
              w = deleteRecord(w, listId, recordId)
              true
            } catch (e: WorkspaceError) {
              assertEquals(true, e.key == "recordNotFound" || e.key == "listNotFound", at)
              false
            }
            assertEquals(expected.member("deleted").toJson(), deleted.toString(), at)
          }
        }
      }
      val want = (f.member("expected") as JsonObject).members
      for ((listId, records) in want) {
        assertEquals(records, JsonValue.parse(w.lists.single { it.id == listId }.records.joinToString(",", "[", "]", transform = ::encodeRecord)), "$name $listId")
      }
    }
  }
}
