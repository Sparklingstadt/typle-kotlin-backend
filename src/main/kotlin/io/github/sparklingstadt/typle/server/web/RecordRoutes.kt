package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue
import io.github.sparklingstadt.typle.server.session.Session
import io.github.sparklingstadt.typle.server.store.Saved
import io.github.sparklingstadt.typle.server.workspace.Device
import io.github.sparklingstadt.typle.server.workspace.Record
import io.github.sparklingstadt.typle.server.workspace.RecordInput
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.addRecord
import io.github.sparklingstadt.typle.server.workspace.deleteRecord
import io.github.sparklingstadt.typle.server.workspace.encodeRecord
import io.github.sparklingstadt.typle.server.workspace.newId
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * POST and DELETE /api/workspace/records, as Go's records (typle-r v1.2.0 and v1.4.0): one practice record of the
 * account's workspace at a time, with the operations /api/v1 uses (Store.modify). Neither names a revision: a record is
 * saved or deleted however the lists were changed meanwhile, and a request sent again does the same. Both answer with
 * the workspace as now saved, as GET does.
 *
 *  - POST { accountId, listId, record } adds the record (RecordInput, which must have its id): 201 for a new record and
 *    200 when the list already had it, with the record.
 *  - DELETE { accountId, listId, recordId } deletes the record: 200, also when it, or its list, is already gone.
 */
suspend fun ApplicationCall.records(config: Config) {
  if (!methods(HttpMethod.Post, HttpMethod.Delete)) return
  val deleting = request.httpMethod == HttpMethod.Delete
  val reported = if (deleting) "record.delete" else "record.add"
  val (user, fields) = recordRequest(config, reported) ?: return
  if (deleting) deleteRecord(config, user.sub, fields) else addRecord(config, user.sub, fields)
}

// Checks a request to records as PUT /api/workspace is checked, and reads its body as a JSON object of the signed-in
// account. Otherwise it answers the request and returns null.
private suspend fun ApplicationCall.recordRequest(config: Config, reported: String): Pair<Session, Map<String, JsonValue>>? {
  val type = request.headers["Content-Type"].orEmpty().substringBefore(';').trim()
  if (!(fromApp(this) || config.sameOrigin(request.headers["Origin"])) || type != "application/json") {
    return null.also { refuse(HttpStatusCode.Forbidden, "forbiddenRequest") }
  }
  val user = signedIn(config) ?: return null
  // Go's server gives a request without a body (no length, or a length of 0, and no chunks) no body at all.
  val length = request.headers["Content-Length"]
  if (length == "0" || (length == null && request.headers["Transfer-Encoding"] == null)) {
    return null.also { refuse(HttpStatusCode.BadRequest, "emptyRequest") }
  }
  val body = try {
    withContext(Dispatchers.IO) { receiveStream().use { readLimited(it, MAX_BODY_BYTES) } }
  } catch (_: TooLargeException) {
    return null.also { refuse(HttpStatusCode.PayloadTooLarge, "workspaceTooLarge") }
  } catch (e: Exception) {
    report(e, reported)
    return null.also { refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed") }
  }
  val fields = try {
    parseObject(String(body, Charsets.UTF_8)).members
  } catch (_: InvalidRequestException) {
    return null.also { refuse(HttpStatusCode.BadRequest, "invalidRecord") }
  }
  val account = fields["accountId"]
  if (account !is JsonString || account.value != user.sub) {
    return null.also { refuse(HttpStatusCode.Unauthorized, "loginAccountChanged") }
  }
  return user to fields
}

// A field that must be a string other than "".
private fun stringField(fields: Map<String, JsonValue>, name: String): String? = (fields[name] as? JsonString)?.value?.ifEmpty { null }

private suspend fun ApplicationCall.addRecord(config: Config, owner: String, fields: Map<String, JsonValue>) {
  val listId = stringField(fields, "listId")
  val input = recordInput(fields["record"])
  if (listId == null || input == null || fields.size != 3) return refuse(HttpStatusCode.BadRequest, "invalidRecord")
  val id = newId()
  var added: Pair<Record, Boolean>? = null
  try {
    withContext(Dispatchers.IO) {
      config.store!!.modify(owner) { w ->
        val (next, record, isNew) = addRecord(w, listId, input, id)
        added = record to isNew
        next to isNew
      }
    }
  } catch (e: WorkspaceError) {
    return when (e.key) {
      "listNotFound" -> refuse(HttpStatusCode.NotFound, "recordListDeleted")
      "workspaceTooLarge" -> refuse(HttpStatusCode.PayloadTooLarge, e.key)
      else -> refuse(HttpStatusCode.BadRequest, e.key)
    }
  } catch (e: Exception) {
    report(e, "record.add")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  }
  val result = savedAfter(config, owner, "record.add") ?: return
  val (record, isNew) = added!!
  reply(if (isNew) HttpStatusCode.Created else HttpStatusCode.OK,
    answerJson(result, owner).dropLast(1) + ",\"record\":" + encodeRecord(record) + "}")
}

private suspend fun ApplicationCall.deleteRecord(config: Config, owner: String, fields: Map<String, JsonValue>) {
  val listId = stringField(fields, "listId")
  val recordId = stringField(fields, "recordId")
  if (listId == null || recordId == null || fields.size != 3) return refuse(HttpStatusCode.BadRequest, "invalidRecord")
  try {
    withContext(Dispatchers.IO) { config.store!!.modify(owner) { w -> deleteRecord(w, listId, recordId) to true } }
  } catch (e: WorkspaceError) {
    // Already gone is what was asked for: a request sent again after a lost answer is answered as the first.
    if (e.key != "recordNotFound" && e.key != "listNotFound") {
      return if (e.key == "workspaceTooLarge") refuse(HttpStatusCode.PayloadTooLarge, e.key) else refuse(HttpStatusCode.BadRequest, e.key)
    }
  } catch (e: Exception) {
    report(e, "record.delete")
    return refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed")
  }
  val result = savedAfter(config, owner, "record.delete") ?: return
  reply(HttpStatusCode.OK, answerJson(result, owner))
}

// The workspace as stored after a change, as GET answers it. Otherwise it answers the request and returns null.
private suspend fun ApplicationCall.savedAfter(config: Config, owner: String, reported: String): Saved? = try {
  withContext(Dispatchers.IO) { config.store!!.readRevision(owner) }
    ?: throw IllegalStateException("workspace missing after a record was changed")
} catch (e: Exception) {
  report(e, reported)
  null.also { refuse(HttpStatusCode.ServiceUnavailable, "workspaceSaveFailed") }
}

/**
 * The record of an add request, as Go's decodeRecord reads it: one object of RecordInput's fields only (null is a
 * field left out), with its id; null when it is not.
 */
private fun recordInput(raw: JsonValue?): RecordInput? {
  if (raw !is JsonObject) return null
  val known = setOf("id", "time", "date", "mistakes", "accuracy", "wordCount", "deviceId", "device")
  if (raw.members.keys.any { it !in known }) return null
  fun text(name: String): Result<String?> = when (val value = raw.members[name]) {
    null, JsonNull -> Result.success(null)
    is JsonString -> Result.success(value.value)
    else -> Result.failure(IllegalArgumentException(name))
  }
  fun number(name: String): Result<Double?> = when (val value = raw.members[name]) {
    null, JsonNull -> Result.success(null)
    is JsonNumber -> Result.success(value.literal.toDouble())
    else -> Result.failure(IllegalArgumentException(name))
  }
  val device = when (val value = raw.members["device"]) {
    null, JsonNull -> null
    is JsonObject -> {
      if (value.members.keys.any { it !in setOf("id", "name", "layout") }) return null
      fun part(name: String): String? = when (val item = value.members[name]) {
        null, JsonNull -> ""
        is JsonString -> item.value
        else -> null
      }
      Device(part("id") ?: return null, part("name") ?: return null, part("layout") ?: return null)
    }
    else -> return null
  }
  return try {
    RecordInput(text("id").getOrThrow() ?: return null, number("time").getOrThrow(), text("date").getOrThrow(),
      number("mistakes").getOrThrow(), number("accuracy").getOrThrow(), number("wordCount").getOrThrow(),
      text("deviceId").getOrThrow(), device)
  } catch (_: IllegalArgumentException) {
    null
  }
}
