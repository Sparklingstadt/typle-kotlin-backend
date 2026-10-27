package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonValue

// Following official packs follows src/core/followedPacks.ts, as Go's server/internal/workspace/follow.go: a followed
// pack is saved as a pack of the account's, and every read makes it equal to its official pack again. The fixtures of
// typle-r's tests/fixtures/follow (copied to src/test/resources/follow) are checked by the app, Go and Kotlin.

/**
 * An official pack as the catalog holds it, in the saved shape: its lists have the pack's id as packId and no records
 * (Go's OfficialPack).
 */
data class OfficialPack(val catalogId: String, val version: Int, val pack: Pack, val lists: List<WordList>)

/** The official packs followOfficial follows on the server, read once (Go's catalog). They are immutable. */
val catalog: List<OfficialPack> by lazy { officialPacks() }

private fun resource(name: String): JsonValue {
  val text = OfficialPack::class.java.getResource("/packs/$name")?.readText()
    ?: throw IllegalStateException("official pack data $name is missing")
  return JsonValue.parse(text)
}

/**
 * officialPacks (src/plugins/packs/catalog.ts), as Go's OfficialPacks: the packs made of the lists of
 * src/plugins/packs/{samples,countries,commands}.json (copied to src/main/resources/packs, as Go embeds them) as lists
 * saved before packs existed are (convertLibrary), each with its lists in the saved shape. Each pack's catalog id is its
 * own id, at version 1.
 */
fun officialPacks(): List<OfficialPack> {
  val samples = (resource("samples.json") as JsonArray).items.mapIndexed { index, item ->
    JsonObject(LinkedHashMap((item as JsonObject).members).apply {
      put("id", JsonString("sample-${index + 1}"))
      put("records", JsonArray(emptyList()))
    })
  }
  val countries = (resource("countries.json") as JsonArray).items
  val commands = (resource("commands.json") as JsonArray).items
  val lists = decodeLists(JsonArray(samples + countries + commands))
  val (packs, converted) = convertLibrary(lists)
  return packs.map { pack ->
    OfficialPack(pack.id, 1, pack.copy(origin = Origin(pack.id, 1.0)), converted.filter { it.packId == pack.id })
  }
}

/**
 * convertLibrary (src/core/wordPacks.ts), as Go's convertLibrary: lists saved before packs existed get one pack for each
 * group, named after it, and one for each list without a group, named after the list. Kotlin uses it for the catalog
 * only; it does not convert saved rows (see Workspace.kt).
 */
internal fun convertLibrary(lists: List<WordList>): Pair<List<Pack>, List<WordList>> {
  val packs = ArrayList<Pack>()
  val packOf = HashMap<String, Int>()
  val names = HashSet<String>()
  val converted = ArrayList<WordList>()
  for (list in lists) {
    val group = trimJS(list.group)
    val key = if (group != "") "group:$group" else "list:${list.id}"
    val at = packOf.getOrPut(key) {
      val members = if (group != "") lists.filter { trimJS(it.group) == group } else listOf(list)
      var oldest = members[0]
      val tags = ArrayList<String>()
      for (member in members) {
        if (earlier(member.createdAt, oldest.createdAt)) oldest = member
        tags.addAll(member.tags)
      }
      val wanted = if (group != "") group else trimJS(list.name)
      val (category, kept) = labelsOf(list.category, tags)
      packs.add(Pack("pack-${list.id}", unique(names, wanted) { fit(wanted, "（$it）", MAX_LIST_NAME) }, category = category,
        tags = kept, createdAt = oldest.createdAt))
      packs.size - 1
    }
    converted.add(list.copy(group = "", category = "", tags = emptyList(), packId = packs[at].id))
  }
  return packs to separateLevels(converted)
}

// unique (src/core/workspaceDocument.ts), as Go's unique: wanted, or the first variant whose lowercase is not taken.
private fun unique(taken: MutableSet<String>, wanted: String, variant: (Int) -> String): String {
  var value = wanted
  var count = 2
  while (lowerCase(value) in taken) value = variant(count++)
  taken.add(lowerCase(value))
  return value
}

// labelsOf (src/core/wordPacks.ts), as Go's labelsOf: the category and tags trimmed, without empty tags or a tag repeated
// in another case, and the first MAX_TAGS of them.
private fun labelsOf(category: String, tags: List<String>): Pair<String, List<String>> {
  val kept = ArrayList<String>()
  val seen = HashSet<String>()
  for (tag in tags) {
    val value = trimJS(tag)
    if (value == "" || !seen.add(lowerCase(value))) continue
    kept.add(value)
  }
  return trimJS(category) to kept.take(MAX_TAGS)
}

// Whether the date a is before b, as Date.parse(a) < Date.parse(b): a date that does not parse is never before another.
private fun earlier(a: String, b: String): Boolean {
  val at = parseTime(a) ?: return false
  val bt = parseTime(b) ?: return false
  return at.isBefore(bt)
}

/** The listId of the origin of a list of a followed pack, or null when it has none (it is kept in extra). */
fun listOriginId(list: WordList): String? {
  if (list.extra.isEmpty()) return null
  val origin = (JsonValue.parse(list.extra) as JsonObject).members["origin"] as? JsonObject ?: return null
  return (origin.members["listId"] as? JsonString)?.value
}

/**
 * followOfficial (src/core/followedPacks.ts), as Go's followOfficial: it brings a library in line with the catalog, in
 * two steps; applying it again changes nothing.
 *  1. Copies from before typle-r v0.98.0 are removed with their lists and records: a pack with an origin that is not
 *     followed, and a pack without an origin whose id is a catalog id.
 *  2. Each followed pack is made equal to its official pack (removed when the catalog has none), keeping its id, name,
 *     createdAt and the records of the lists it keeps. Its lists take the place of its first list.
 *
 * Go runs it on a library it has repaired, where every list is in a pack. Kotlin does not repair saved rows, so a list
 * whose packId is not among packs at all is kept as it is (a save with packs never has one: checkPacks refuses it).
 */
fun followOfficial(packs: List<Pack>, lists: List<WordList>, official: List<OfficialPack>): Pair<List<Pack>, List<WordList>> {
  val entries = official.associateBy { it.catalogId }
  val kept = ArrayList<Pack>()
  for (pack in packs) {
    val origin = pack.origin
    if (origin != null) {
      val from = entries[origin.catalogId]?.pack
      if (!origin.followed || from == null) continue
      kept.add(pack.copy(description = from.description, category = from.category, tags = from.tags))
    } else if (pack.id !in entries) {
      kept.add(pack)
    }
  }
  val byId = kept.associateBy { it.id }
  val synced = HashMap<String, List<WordList>>()
  for (pack in kept) {
    val origin = pack.origin ?: continue
    val mine = lists.filter { it.packId == pack.id }
    synced[pack.id] = entries.getValue(origin.catalogId).lists.map { item ->
      // The id of a new list comes from the official list's, so that the app, Go and Kotlin make the same list.
      val list = mine.firstOrNull { listOriginId(it) == item.id } ?: WordList(
        "${pack.id}:${item.id}", item.name, item.words, emptyList(), item.createdAt,
        withMember("", "origin", JsonObject(mapOf("listId" to JsonString(item.id)))), packId = pack.id,
      )
      withLevels(list.copy(name = item.name, words = item.words), levelsOf(item))
    }
  }
  val known = packs.map { it.id }.toSet()
  val placed = HashSet<String>()
  val result = ArrayList<WordList>()
  for (list in lists) {
    val pack = byId[list.packId]
    when {
      pack == null -> if (list.packId !in known) result.add(list)
      pack.origin == null -> result.add(list)
      placed.add(pack.id) -> result.addAll(synced.getValue(pack.id))
    }
  }
  return kept to result
}
