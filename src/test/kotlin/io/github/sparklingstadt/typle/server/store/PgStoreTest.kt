package io.github.sparklingstadt.typle.server.store

import io.github.sparklingstadt.typle.server.workspace.Device
import io.github.sparklingstadt.typle.server.workspace.InvalidWorkspaceException
import io.github.sparklingstadt.typle.server.workspace.MAX_INTEREST_LENGTH
import io.github.sparklingstadt.typle.server.workspace.Pack
import io.github.sparklingstadt.typle.server.workspace.Word
import io.github.sparklingstadt.typle.server.workspace.WordList
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.decode
import io.github.sparklingstadt.typle.server.workspace.decodeInterests
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

// These run against TYPLE_TEST_DATABASE_URL, a database that npm run db:migrate has set up, and are skipped without it.
class PgStoreTest {
  private val connection = System.getenv("TYPLE_TEST_DATABASE_URL").orEmpty()
  private lateinit var store: PgStore

  @BeforeTest fun open() {
    assumeTrue(connection.isNotEmpty(), "TYPLE_TEST_DATABASE_URL is not set")
    store = PgStore.open(connection)
  }

  @AfterTest fun close() {
    if (::store.isInitialized) store.close()
  }

  private fun sql(): java.sql.Connection {
    val target = jdbcTarget(connection)
    return DriverManager.getConnection(target.url, target.user, target.password)
  }

  // A fresh account for each use, so that tests do not see each other's rows.
  private fun owner() = "github:9" + Random.nextLong(100_000_000_000L, 999_999_999_999L)

  private fun row(owner: String): Pair<String, Long> = sql().use { db ->
    db.prepareStatement("SELECT lists::text, revision FROM typle_workspaces WHERE owner_id = ?").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r -> r.next(); r.getString(1) to r.getLong(2) }
    }
  }

  private fun insert(owner: String, lists: String) = sql().use { db ->
    db.prepareStatement("INSERT INTO typle_workspaces (owner_id, lists) VALUES (?, ?::jsonb)").use { q ->
      q.setString(1, owner)
      q.setString(2, lists)
      q.executeUpdate()
    }
  }

  private fun newLists(n: Int) = List(n) { WordList("l$it", "list $it", listOf(Word("a", "a", "")), emptyList(), "x") }

  private fun lists(n: Int) = Change(newLists(n), emptyList(), hasLists = true, hasDevices = false)

  private val devices = listOf(Device("d1", "自宅", "JIS"))

  @Test fun ping() = store.ping()

  @Test fun readMissing() = assertNull(store.readRevision(owner()))

  @Test fun create() {
    val id = owner()
    val saved = store.writeRevision(id, 0, Change(newLists(2), devices, hasLists = true, hasDevices = true))
    assertEquals(1, saved.revision)
    assertEquals(2, decode(saved.lists, saved.devices).lists.size)
    assertEquals(devices, decode(saved.lists, saved.devices).devices)
    val read = store.readRevision(id)!!
    assertEquals(1, read.revision)
    assertEquals(2, decode(read.lists, read.devices).lists.size)
    assertIs<ConflictException>(assertFailsWith<Exception> { store.writeRevision(id, 0, lists(1)) })
  }

  @Test fun createWithDevicesOnly() {
    val saved = store.writeRevision(owner(), 0, Change(emptyList(), devices, hasLists = false, hasDevices = true))
    assertEquals(0, decode(saved.lists, saved.devices).lists.size)
    assertEquals(1, decode(saved.lists, saved.devices).devices.size)
  }

  @Test fun updateAndConflict() {
    val id = owner()
    store.writeRevision(id, 0, lists(1))
    val saved = store.writeRevision(id, 1, lists(2))
    assertEquals(2, saved.revision)
    assertFailsWith<ConflictException> { store.writeRevision(id, 1, lists(3)) }
    assertFailsWith<ConflictException> { store.writeRevision(owner(), 3, lists(1)) }
    assertEquals(2, row(id).second)
  }

  @Test fun keepsWhatIsLeftOut() {
    val id = owner()
    store.writeRevision(id, 0, Change(newLists(1), devices, hasLists = true, hasDevices = true))
    val onlyLists = store.writeRevision(id, 1, lists(2))
    assertEquals(devices, decode(onlyLists.lists, onlyLists.devices).devices)
    val onlyDevices = store.writeRevision(id, 2, Change(emptyList(), emptyList(), hasLists = false, hasDevices = true))
    val w = decode(onlyDevices.lists, onlyDevices.devices)
    assertEquals(2, w.lists.size)
    assertEquals(emptyList(), w.devices)
  }

  @Test fun listLimit() {
    assertEquals("listLimit", assertFailsWith<WorkspaceError> { store.writeRevision(owner(), 0, lists(151)) }.key)
    // Saved data merged from removed profiles may have more than 150 lists: a save may keep that number, not raise it.
    val id = owner()
    insert(id, encodeLists(151))
    assertEquals(2, store.writeRevision(id, 1, lists(151)).revision)
    assertEquals("listLimit", assertFailsWith<WorkspaceError> { store.writeRevision(id, 2, lists(152)) }.key)
    assertEquals(3, store.writeRevision(id, 2, lists(150)).revision)
  }

  private fun encodeLists(n: Int) = io.github.sparklingstadt.typle.server.workspace.encode(newLists(n))

  @Test fun limitsAreCheckedBeforeTheDatabase() {
    val manyWords = listOf(newLists(1)[0].copy(words = List(1001) { Word("a", "a", "") }))
    val fresh = owner()
    val error = assertFailsWith<WorkspaceError> {
      store.writeRevision(fresh, 0, Change(manyWords, emptyList(), hasLists = true, hasDevices = false))
    }
    assertEquals("invalidWorkspace", error.key)
    assertNull(store.readRevision(fresh))
  }

  @Test fun unreadableChangeIsNotWritten() {
    val twice = newLists(2).let { listOf(it[0], it[1].copy(id = it[0].id)) }
    val fresh = owner()
    assertFailsWith<InvalidWorkspaceException> {
      store.writeRevision(fresh, 0, Change(twice, emptyList(), hasLists = true, hasDevices = false))
    }
    assertNull(store.readRevision(fresh))
    val id = owner()
    store.writeRevision(id, 0, lists(1))
    assertFailsWith<InvalidWorkspaceException> {
      store.writeRevision(id, 1, Change(emptyList(), listOf(Device("d1", " ", "JIS")), hasLists = false, hasDevices = true))
    }
    assertEquals(1, row(id).second)
  }

  @Test fun keepsUnknownFields() {
    val id = owner()
    val withExtra = listOf(newLists(1)[0].copy(words = listOf(Word("a", "a", "", """{"x":{"a":[1,2]}}"""))))
    store.writeRevision(id, 0, Change(withExtra, emptyList(), hasLists = true, hasDevices = false))
    val read = store.readRevision(id)!!
    assertEquals("""{"x":{"a":[1,2]}}""", decode(read.lists, read.devices).lists[0].words[0].extra)
  }

  // The stored text is answered as the database writes it, but for the lists of a row whose records are in
  // typle_records: they are written again with their records, keys sorted, as Go's withRecords writes them.
  @Test fun readsStoredText() {
    val id = owner()
    store.writeRevision(id, 0, Change(emptyList(), listOf(Device("d1", "Mac", "JIS")), hasLists = false, hasDevices = true))
    assertTrue(store.readRevision(id)!!.devices.startsWith("[{\"id\": \"d1\""))
    store.writeRevision(id, 1, lists(1))
    assertTrue(store.readRevision(id)!!.lists.startsWith("[{\"createdAt\":\"x\",\"id\":\"l0\""), store.readRevision(id)!!.lists)
  }

  @Test fun unreadableSavedDataIsAnError() {
    val id = owner()
    insert(id, """[{"id": ""}]""")
    assertFailsWith<InvalidWorkspaceException> { store.readRevision(id) }
  }

  // An update reads only the saved revision and number of lists: unreadable saved lists can be replaced, and are kept
  // as they are by a save of the devices alone.
  @Test fun updateDoesNotDecodeSavedLists() {
    val unreadable = """[{"id": "x"}]"""
    val replaced = owner()
    val kept = owner()
    insert(replaced, unreadable)
    insert(kept, unreadable)
    assertEquals(2, store.writeRevision(replaced, 1, lists(1)).revision)
    store.readRevision(replaced)
    assertEquals(2, store.writeRevision(kept, 1, Change(emptyList(), devices, hasLists = false, hasDevices = true)).revision)
    assertEquals(unreadable, row(kept).first)
  }

  @Test fun concurrentSavesAtOneRevision() {
    val id = owner()
    store.writeRevision(id, 0, lists(1))
    val writers = 4
    val barrier = CyclicBarrier(writers)
    val pool = Executors.newFixedThreadPool(writers)
    try {
      val results = (1..writers).map {
        pool.submit(Callable {
          barrier.await()
          runCatching { store.writeRevision(id, 1, lists(2)) }
        })
      }.map { it.get() }
      assertEquals(1, results.count { it.isSuccess })
      assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is ConflictException })
      assertEquals(2, row(id).second)
    } finally {
      pool.shutdown()
    }
  }

  @Test fun concurrentCreates() {
    val id = owner()
    val writers = 4
    val barrier = CyclicBarrier(writers)
    val pool = Executors.newFixedThreadPool(writers)
    try {
      val results = (1..writers).map {
        pool.submit(Callable {
          barrier.await()
          runCatching { store.writeRevision(id, 0, lists(1)) }
        })
      }.map { it.get() }
      assertEquals(1, results.count { it.isSuccess })
      assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is ConflictException })
    } finally {
      pool.shutdown()
    }
  }

  private fun packsColumn(owner: String): String? = sql().use { db ->
    db.prepareStatement("SELECT packs::text FROM typle_workspaces WHERE owner_id = ?").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r -> r.next(); r.getString(1) }
    }
  }

  // A save with packs writes them, and its lists without labels; a save of lists alone puts each list in a pack of its
  // own, which its labels move to (typle-r v2.0.0).
  @Test fun writeRevisionPacks() {
    val id = owner()
    val packed = newLists(1).map { it.copy(packId = "p") }
    val packs = listOf(Pack("p", "P", category = "C", createdAt = "2026-10-10T01:23:45.000Z"))
    val saved = store.writeRevision(id, 0, Change(packed, emptyList(), hasLists = true, hasDevices = false, packs = packs, hasPacks = true))
    assertEquals(packs, decode(saved.lists, saved.devices, saved.packs).packs)
    assertEquals("p", decode(saved.lists, saved.devices, saved.packs).lists[0].packId)
    assertEquals(saved, store.readRevision(id))
    val sent = newLists(2).let { listOf(it[0].copy(packId = "p"), it[1].copy(category = "語学", tags = listOf("英語"), group = "G")) }
    val again = store.writeRevision(id, 1, Change(sent, emptyList(), hasLists = true, hasDevices = false))
    assertTrue("語学" !in again.lists && "group" !in again.lists, again.lists)
    val repaired = decode(again.lists, again.devices, again.packs)
    assertEquals(listOf("pack-l0" to "", "pack-l1" to "語学"), repaired.packs.map { it.id to it.category })
    assertEquals(again.packs, packsColumn(id))
    // A save of the devices alone leaves both as they are.
    val devicesOnly = store.writeRevision(id, 2, Change(emptyList(), devices, hasLists = false, hasDevices = true))
    assertEquals(again.lists to again.packs, devicesOnly.lists to devicesOnly.packs)
  }

  // Packs are set only with the lists they hold.
  @Test fun packsWithoutListsAreRefused() {
    val fresh = owner()
    val packs = listOf(Pack("p", "P", createdAt = "x"))
    val error = assertFailsWith<WorkspaceError> {
      store.writeRevision(fresh, 0, Change(emptyList(), devices, hasLists = false, hasDevices = true, packs = packs, hasPacks = true))
    }
    assertEquals("invalidWorkspace", error.key)
    assertNull(store.readRevision(fresh))
  }

  @Test fun unreadableSavedPacksAreAnError() {
    val id = owner()
    sql().use { db ->
      db.prepareStatement("INSERT INTO typle_workspaces (owner_id, lists, packs) VALUES (?, '[]'::jsonb, '[{\"id\": \"\"}]'::jsonb)").use { q ->
        q.setString(1, id)
        q.executeUpdate()
      }
    }
    assertFailsWith<InvalidWorkspaceException> { store.readRevision(id) }
  }

  // The interests have a column of their own: saved with a change that sets them, kept by one that leaves them out, and
  // null (NULL) for a row without them, which the app reads as the default (Go's TestWriteRevisionInterests; Kotlin has
  // no /api/v1 Modify).
  @Test fun writeRevisionInterests() {
    val id = owner()
    var saved = store.writeRevision(id, 0, lists(1))
    assertNull(saved.interests, "interests of a new row")
    val chosen = listOf("language", "language/zh", "x/unknown")
    saved = store.writeRevision(id, 1, Change(emptyList(), emptyList(), hasLists = false, hasDevices = false, interests = chosen, hasInterests = true))
    assertEquals(2L, saved.revision)
    assertEquals(chosen, decodeInterests(saved.interests))
    assertEquals(1, decode(saved.lists, saved.devices).lists.size)
    assertEquals(chosen, decodeInterests(store.readRevision(id)!!.interests))
    // Lists or devices alone leave the interests as they are.
    store.writeRevision(id, 2, lists(2))
    saved = store.writeRevision(id, 3, Change(emptyList(), devices, hasLists = false, hasDevices = true))
    assertEquals(chosen, decodeInterests(saved.interests))
    assertEquals(2, decode(saved.lists, saved.devices).lists.size)
    // They can be emptied.
    saved = store.writeRevision(id, 4, Change(emptyList(), emptyList(), hasLists = false, hasDevices = false, hasInterests = true))
    assertEquals("[]", saved.interests)
    assertEquals(saved, store.readRevision(id))
  }

  @Test fun createWithInterests() {
    val saved = store.writeRevision(owner(), 0,
      Change(emptyList(), emptyList(), hasLists = false, hasDevices = false, interests = listOf("programming"), hasInterests = true))
    assertEquals(1L, saved.revision)
    assertEquals(listOf("programming"), decodeInterests(saved.interests))
    assertEquals(0, decode(saved.lists, saved.devices).lists.size)
  }

  // Interests the app would refuse are never saved: the change is refused as an invalid workspace, before the database.
  @Test fun invalidInterestsAreRefused() {
    val fresh = owner()
    for (invalid in listOf(listOf("language", "language"), listOf(""), listOf("a".repeat(MAX_INTEREST_LENGTH + 1)))) {
      val error = assertFailsWith<WorkspaceError>(invalid.toString()) {
        store.writeRevision(fresh, 0, Change(emptyList(), emptyList(), hasLists = false, hasDevices = false, interests = invalid, hasInterests = true))
      }
      assertEquals("invalidWorkspace", error.key)
    }
    assertNull(store.readRevision(fresh))
  }

  // A row whose interests the app would refuse is refused when read, as its lists would be.
  @Test fun unreadableSavedInterestsAreAnError() {
    val id = owner()
    sql().use { db ->
      db.prepareStatement("INSERT INTO typle_workspaces (owner_id, lists, devices, interests) VALUES (?, '[]', '[]', '[\"a\",\"a\"]')").use { q ->
        q.setString(1, id)
        q.executeUpdate()
      }
    }
    assertFailsWith<InvalidWorkspaceException> { store.readRevision(id) }
  }
}
