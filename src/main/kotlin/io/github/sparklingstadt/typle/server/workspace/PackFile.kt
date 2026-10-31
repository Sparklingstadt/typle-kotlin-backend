package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue

// A pack as a file (typle-pack v1) follows src/core/packFile.ts, as Go's server/internal/workspace/packfile.go
// (EncodePackFile, PackFile.Encode and DecodePackFile), and is written byte for byte as the app writes it:
// typle-r's tests/fixtures/pack-file holds the cases and its README the rules. Only what a shared link needs is here.

private const val PACK_FILE_FORMAT = "typle-pack"

/** A pack in the shape of a file, with no records, ids, dates or origin. Empty optional fields are left out. */
private class PackFile(val name: String, val description: String, val category: String, val tags: List<String>,
  val lists: List<PackFileList>)

private class PackFileList(val name: String, val levels: List<String>, val words: List<Word>)

/**
 * The pack packId of w and its lists in saved order, as JSON.stringify(file, null, 2) and a newline write them (Go's
 * EncodePackFile). A pack that is not in w is packNotFound.
 */
fun encodePackFile(w: Workspace, packId: String): String {
  val pack = w.packs.firstOrNull { it.id == packId } ?: throw WorkspaceError("packNotFound")
  val lists = w.lists.filter { it.packId == packId }.map { PackFileList(it.name, levelsOf(it), it.words) }
  return writePackFile(PackFile(pack.name, pack.description, pack.category, pack.tags, lists))
}

/**
 * A pack file read back (as decodePackFile reads it) and written again as encodePackFile writes it, whatever the
 * spacing and order of keys it was stored with (Go's DecodePackFile and PackFile.Encode). A file that cannot be read
 * is InvalidWorkspaceException.
 */
fun encodePackFileAgain(data: String): String = writePackFile(decodePackFile(data))

/**
 * The levels of a list, which Kotlin keeps with the fields Typle does not know (extra): [] when it has none or they are
 * empty, as the file leaves them out either way. Levels that are not strings are InvalidWorkspaceException, as Go's
 * Decode refuses them.
 */
fun levelsOf(list: WordList): List<String> {
  if (list.extra.isEmpty()) return emptyList()
  return when (val levels = (JsonValue.parse(list.extra) as JsonObject).members["levels"]) {
    null, JsonNull -> emptyList()
    is JsonArray -> levels.items.map { (it as? JsonString)?.value ?: throw InvalidWorkspaceException("levels must be strings") }
    else -> throw InvalidWorkspaceException("levels must be an array")
  }
}

private fun decodePackFile(data: String): PackFile {
  fun unreadable(): Nothing = throw InvalidWorkspaceException("packFileUnreadable")
  val top = try {
    JsonValue.parse(data.removePrefix("\uFEFF"))
  } catch (_: JsonSyntaxException) {
    unreadable()
  }
  if (top !is JsonObject) unreadable()
  val version = top.members["version"]
  val info = top.members["pack"]
  val lists = top.members["lists"]
  if (top.members["format"] != JsonString(PACK_FILE_FORMAT) || version !is JsonNumber || version.literal.toDouble() != 1.0 ||
    info !is JsonObject || lists !is JsonArray || lists.items.isEmpty()) {
    unreadable()
  }
  fun string(fields: JsonObject, name: String): String = (fields.members[name] as? JsonString)?.value ?: unreadable()
  // An optional field left out or null is read as left out.
  fun optional(fields: JsonObject, name: String): String = when (val value = fields.members[name]) {
    null, JsonNull -> ""
    is JsonString -> value.value
    else -> unreadable()
  }
  fun strings(fields: JsonObject, name: String): List<String> = when (val value = fields.members[name]) {
    null, JsonNull -> emptyList()
    is JsonArray -> value.items.map { (it as? JsonString)?.value ?: unreadable() }
    else -> unreadable()
  }
  return PackFile(string(info, "name"), optional(info, "description"), optional(info, "category"), strings(info, "tags"),
    lists.items.map { item ->
      val fields = item as? JsonObject ?: unreadable()
      val name = string(fields, "name")
      val words = fields.members["words"] as? JsonArray ?: unreadable()
      PackFileList(name, strings(fields, "levels"), words.items.map { raw ->
        val word = raw as? JsonObject ?: unreadable()
        val inputType = try {
          inputTypeOf(word)
        } catch (_: InvalidWorkspaceException) {
          unreadable()
        }
        Word(string(word, "display"), string(word, "input"), string(word, "annotation"), inputType = inputType)
      })
    })
}

// A JSON value to write with indentation: text already written, or an object or array to indent.
private sealed interface Node

private class Text(val json: String) : Node

private class Obj(val fields: List<Pair<String, Node>>) : Node

private class Arr(val items: List<Node>) : Node

private fun strings(values: List<String>): Node? = values.ifEmpty { null }?.let { list -> Arr(list.map { Text(str(it)) }) }

// JSON.stringify(file, null, 2) + "\n": the fields in the order of the file, empty optional ones left out.
private fun writePackFile(file: PackFile): String {
  val pack = Obj(listOfNotNull(
    "name" to Text(str(file.name)),
    file.description.ifEmpty { null }?.let { "description" to Text(str(it)) },
    file.category.ifEmpty { null }?.let { "category" to Text(str(it)) },
    strings(file.tags)?.let { "tags" to it },
  ))
  val lists = Arr(file.lists.map { list ->
    Obj(listOfNotNull(
      "name" to Text(str(list.name)),
      strings(list.levels)?.let { "levels" to it },
      "words" to Arr(list.words.map { word ->
        Obj(listOfNotNull("display" to Text(str(word.display)), "input" to Text(str(word.input)),
          "annotation" to Text(str(word.annotation)), word.inputType.ifEmpty { null }?.let { "input_type" to Text(str(it)) }))
      }),
    ))
  })
  val top = Obj(listOf("format" to Text(str(PACK_FILE_FORMAT)), "version" to Text("1"), "pack" to pack, "lists" to lists))
  return StringBuilder().also { write(it, top, "") }.append('\n').toString()
}

private fun write(out: StringBuilder, node: Node, indent: String) {
  val inner = "$indent  "
  when (node) {
    is Text -> out.append(node.json)
    is Obj -> if (node.fields.isEmpty()) out.append("{}") else {
      out.append("{\n")
      node.fields.forEachIndexed { i, (name, value) ->
        if (i > 0) out.append(",\n")
        out.append(inner).append(str(name)).append(": ")
        write(out, value, inner)
      }
      out.append('\n').append(indent).append('}')
    }
    is Arr -> if (node.items.isEmpty()) out.append("[]") else {
      out.append("[\n")
      node.items.forEachIndexed { i, value ->
        if (i > 0) out.append(",\n")
        out.append(inner)
        write(out, value, inner)
      }
      out.append('\n').append(indent).append(']')
    }
  }
}

/**
 * A string as Go's encoder writes it with HTML left alone, with U+2028 and U+2029 written as themselves as
 * JSON.stringify does (Go's separatorsAsJavaScript): short escapes for " \ and \b \f \n \r \t, \u00xx for other
 * control characters, an unpaired surrogate (which Go reads as U+FFFD) as U+FFFD, and anything else as it is.
 */
private fun str(value: String): String {
  val out = StringBuilder(value.length + 2).append('"')
  var i = 0
  while (i < value.length) {
    val c = value[i]
    when {
      c == '"' || c == '\\' -> out.append('\\').append(c)
      c == '\b' -> out.append("\\b")
      c == '\u000c' -> out.append("\\f")
      c == '\n' -> out.append("\\n")
      c == '\r' -> out.append("\\r")
      c == '\t' -> out.append("\\t")
      c < ' ' -> out.append("\\u00").append("0123456789abcdef"[c.code shr 4]).append("0123456789abcdef"[c.code and 0xf])
      c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
        out.append(c).append(value[i + 1])
        i++
      }
      c.isSurrogate() -> out.append('\uFFFD')
      else -> out.append(c)
    }
    i++
  }
  return out.append('"').toString()
}
