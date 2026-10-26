package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue

// Word packs follow src/core/wordPacks.ts, as Go's server/internal/workspace/packs_library.go: every list belongs to one
// pack, which holds the category and tags the lists used to have. The checks of a save are here, and of the repairs Go
// makes when it reads saved packs only separateLevels (see readSaved); the conversion and the other repairs are the app's.

// The limits of a pack (MAX_PACK_NAME and MAX_PACK_DESCRIPTION in src/core/wordPacks.ts), in UTF-16 code units.
const val MAX_PACK_NAME = 200
const val MAX_PACK_DESCRIPTION = 500

// The limit of a level (MAX_LEVEL in src/core/packEditing.ts, Go's MaxLevel), in UTF-16 code units.
const val MAX_LEVEL = 50

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
  // Lists of a pack whose paths overlap are refused (checkReach in Go), as levelsConflict. This checks a save as it was
  // sent; saved lists that overlap are separated where the server reads them (separateLevels, see readSaved).
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
 * fit (src/core/workspaceDocument.ts), as Go's fit: name with suffix after it, the name cut so that the whole stays within
 * limit UTF-16 code units, without the spaces the cut leaves at its end. A character of two code units is not split.
 */
internal fun fit(name: String, suffix: String, limit: Int): String {
  var kept = name
  val keep = limit - utf16Length(suffix)
  if (utf16Length(kept) > keep) {
    kept = kept.substring(0, maxOf(keep, 0))
    if (kept.isNotEmpty() && kept.last().isHighSurrogate()) kept = kept.dropLast(1)
  }
  return trimEndJS(kept) + suffix
}

/**
 * separateLevels (src/core/wordPacks.ts), as Go's separateLevels, which brings back in reach the lists an older app (or
 * lists saved without their packs) let overlap: a list whose path overlaps that of an earlier list of its pack gets
 * （2）, （3）… on the level where they meet, or on its name when it has no levels (a name another list of the pack does
 * not use, compared trimmed and lowercased). Earlier lists, and lists without overlaps, are kept as they are; a list
 * whose levels change keeps the other fields Typle does not know.
 */
fun separateLevels(lists: List<WordList>): List<WordList> {
  val names = HashMap<String, HashSet<String>>()
  for (list in lists) names.getOrPut(list.packId) { HashSet() }.add(nameKey(list.name))
  val accepted = HashMap<String, MutableList<List<String>>>()
  return lists.map { list ->
    val taken = names.getValue(list.packId)
    val levels = levelsOf(list)
    val hasLevels = levels.isNotEmpty()
    val limit = if (hasLevels) MAX_LEVEL else MAX_LIST_NAME
    val base = if (hasLevels) levels else listOf(list.name)
    val path = base.toMutableList()
    val others = accepted.getOrPut(list.packId) { ArrayList() }
    fun nameTaken() = !hasLevels && nameKey(path[0]) != nameKey(list.name) && nameKey(path[0]) in taken
    var count = 2
    while (true) {
      val other = others.firstOrNull { pathsOverlap(it, path) }
      if (other == null && !nameTaken()) break
      val at = if (other != null) minOf(path.size, other.size) - 1 else 0
      path[at] = fit(base[at], "（$count）", limit)
      count++
    }
    others.add(path.toList())
    when {
      path == base -> list
      hasLevels -> withLevels(list, path)
      else -> {
        taken.add(nameKey(path[0]))
        list.copy(name = path[0])
      }
    }
  }
}

// list with levels in place of its own, which Kotlin keeps with the fields Typle does not know (extra, which then has
// levels).
private fun withLevels(list: WordList, levels: List<String>): WordList {
  val members = LinkedHashMap((JsonValue.parse(list.extra) as JsonObject).members)
  members["levels"] = JsonArray(levels.map(::JsonString))
  return list.copy(extra = unknown(JsonObject(members)))
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
