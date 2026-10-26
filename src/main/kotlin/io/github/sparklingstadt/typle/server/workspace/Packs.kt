package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonValue

// Word packs follow src/core/wordPacks.ts, as Go's server/internal/workspace/packs_library.go: every list belongs to one
// pack, which holds the category and tags the lists used to have. Only the checks of a save are here; the conversion
// and repair of saved data are the app's.

// The limits of a pack (MAX_PACK_NAME and MAX_PACK_DESCRIPTION in src/core/wordPacks.ts), in UTF-16 code units.
const val MAX_PACK_NAME = 200
const val MAX_PACK_DESCRIPTION = 500

/**
 * JavaScript's toLowerCase, which the app uses to compare names. Java's root locale lowercases as it does, İ to i and a
 * combining dot above and Σ at the end of a word to ς included (Go's lowerCase).
 */
fun lowerCase(value: String): String = value.lowercase()

private fun nameKey(name: String) = lowerCase(trimJS(name))

/**
 * validateLibrary (src/core/wordPacks.ts), as Go's CheckPacks: refuses packs and lists that are not in the saved shape,
 * with invalidWorkspace.
 */
fun checkPacks(packs: List<Pack>, lists: List<WordList>) {
  fun invalid(): Nothing = throw WorkspaceError("invalidWorkspace")
  val ids = HashSet<String>()
  val names = HashSet<String>()
  for (pack in packs) {
    val name = nameKey(pack.name)
    if (pack.id == "" || pack.id in ids || name == "" || utf16Length(pack.name) > MAX_PACK_NAME || name in names ||
      utf16Length(pack.description) > MAX_PACK_DESCRIPTION || utf16Length(pack.category) > MAX_CATEGORY ||
      pack.tags.size > MAX_TAGS || pack.tags.any { utf16Length(it) > MAX_TAG }) {
      invalid()
    }
    ids.add(pack.id)
    names.add(name)
  }
  // List names may repeat only in different packs.
  val listNames = HashMap<String, HashSet<String>>()
  for (list in lists) {
    val taken = listNames.getOrPut(list.packId) { HashSet() }
    if (list.packId == "" || list.packId !in ids || !taken.add(nameKey(list.name)) || list.group != "" || list.category != "" ||
      list.tags.isNotEmpty()) {
      invalid()
    }
  }
  // Every pack holds a list.
  if (ids.size != listNames.size) invalid()
  // Lists of a pack whose paths overlap are refused (checkReach in Go), as levelsConflict. Kotlin does not repair
  // saved data, so it does not separate them as Go's separateLevels does when it reads.
  val paths = HashMap<String, MutableList<List<String>>>()
  for (list in lists) {
    val levels = try {
      levelsOf(list)
    } catch (_: InvalidWorkspaceException) {
      invalid()
    }
    val path = if (levels.isEmpty()) listOf(list.name) else levels
    val taken = paths.getOrPut(list.packId) { ArrayList() }
    if (taken.any { pathsOverlap(it, path) }) throw WorkspaceError("levelsConflict")
    taken.add(path)
  }
}

// pathsOverlap (src/core/packEditing.ts): two paths overlap when they are equal or one starts the other, compared as
// they are (case and spaces count).
private fun pathsOverlap(a: List<String>, b: List<String>): Boolean {
  val n = minOf(a.size, b.size)
  return a.subList(0, n) == b.subList(0, n)
}

/**
 * validateLibrary for a save that sends lists and packs, as Go's CheckLibrary. It reads them as they were sent: a list may
 * not have a group, category or tags at all, not even "" or [], and a pack's description, category, tags and origin may
 * be left out but not null, as decodePacks requires. Anything refused is invalidWorkspace.
 */
fun checkLibrary(lists: JsonValue, packs: JsonValue) {
  val invalid = WorkspaceError("invalidWorkspace")
  val decoded = try {
    decodeLists(lists)
  } catch (_: InvalidWorkspaceException) {
    throw invalid
  }
  if (has(lists) { name, _ -> name == "group" || name == "category" || name == "tags" } ||
    has(packs) { name, value -> name in setOf("description", "category", "tags", "origin") && value == JsonNull }) {
    throw invalid
  }
  val saved = try {
    decodePacks(packs)
  } catch (_: InvalidWorkspaceException) {
    throw invalid
  }
  checkPacks(saved, decoded)
}

// Whether an object of the JSON array value has a member that found accepts.
private fun has(value: JsonValue, found: (String, JsonValue) -> Boolean): Boolean =
  value is JsonArray && value.items.any { item -> item is JsonObject && item.members.any { (name, member) -> found(name, member) } }
