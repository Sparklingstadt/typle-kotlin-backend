package io.github.sparklingstadt.typle.server.store

import io.github.sparklingstadt.typle.server.workspace.Record
import io.github.sparklingstadt.typle.server.workspace.RecordInput
import io.github.sparklingstadt.typle.server.workspace.Word
import io.github.sparklingstadt.typle.server.workspace.WordList
import io.github.sparklingstadt.typle.server.workspace.addRecord
import io.github.sparklingstadt.typle.server.workspace.decode
import io.github.sparklingstadt.typle.server.workspace.deleteRecord
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

// Records in typle_records (typle-r v1.3.0), as Go's store/records_test.go, against TYPLE_TEST_DATABASE_URL.
class PgRecordsTest {
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

  private fun owner() = "github:7" + Random.nextLong(100_000_000_000L, 999_999_999_999L)

  // The owner's rows of typle_records as (list, record, position), and the lists column's records.
  private fun rows(owner: String): List<Triple<String, String, Int>> = sql().use { db ->
    db.prepareStatement("SELECT list_id, record_id, position FROM typle_records WHERE owner_id = ? ORDER BY list_id, position").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getString(1), r.getString(2), r.getInt(3))) } }
    }
  }

  private fun column(owner: String): Pair<String, Boolean> = sql().use { db ->
    db.prepareStatement("SELECT lists::text, records_split FROM typle_workspaces WHERE owner_id = ?").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r -> r.next(); r.getString(1) to r.getBoolean(2) }
    }
  }

  private fun list(id: String, vararg records: Record) =
    WordList(id, id, listOf(Word("a", "a", "")), records.toList(), "2026-10-01T00:00:00.000Z")

  // A save moves the lists' records to typle_records, gives a record without an id (or with a repeated one) its legacy
  // id, and the read puts them back in their lists.
  @Test fun savesMoveRecordsToTheirTable() {
    val owner = owner()
    val plain = Record(null, 3.0, "2026-10-02T00:00:00.000Z", null, null, null, null)
    val saved = store.writeRevision(owner, 0, Change(listOf(list("a", plain, plain.copy(id = "r"), plain.copy(id = "r", time = 4.0))), emptyList(),
      hasLists = true, hasDevices = false))
    val (lists, split) = column(owner)
    assertTrue(split && "\"records\": []" in lists && "\"time\"" !in lists, lists)
    val ids = rows(owner).map { it.second }
    assertEquals(3, ids.size)
    assertEquals(listOf("r"), ids.filter { !it.startsWith("legacy-") })
    assertEquals(legacyId("a", 0, plain), ids[0])
    val read = decode(saved.lists, saved.devices, saved.packs).lists.single().records
    assertEquals(ids, read.map { it.id })
    assertEquals(saved, store.readRevision(owner))
    // A save without the list deletes its rows.
    store.writeRevision(owner, 1, Change(listOf(list("b")), emptyList(), hasLists = true, hasDevices = false))
    assertEquals(emptyList(), rows(owner))
  }

  // A row saved before the table (records_split false) keeps its records in its lists until a write moves them.
  @Test fun rowsBeforeTheTableAreMovedByTheirNextWrite() {
    val owner = owner()
    sql().use { db ->
      db.prepareStatement("""INSERT INTO typle_workspaces (owner_id, lists, packs) VALUES (?, ?::jsonb, '[{"id":"p","name":"P","createdAt":"x"}]'::jsonb)""").use { q ->
        q.setString(1, owner)
        q.setString(2, """[{"id":"a","name":"a","packId":"p","createdAt":"x","words":[{"display":"a","input":"a","annotation":""}],"records":[{"id":"old","time":1,"date":"2026-10-01T00:00:00.000Z"}]}]""")
        q.executeUpdate()
      }
    }
    assertTrue("\"old\"" in store.readRevision(owner)!!.lists)
    store.modify(owner) { w ->
      val (next, _, added) = addRecord(w, "a", RecordInput("new", 2.0, "2026-10-02T00:00:00.000Z", 0.0, 100.0), "x")
      next to added
    }
    assertEquals(listOf("old", "new"), rows(owner).map { it.second })
    assertTrue(column(owner).second)
    val read = store.readRevision(owner)!!
    assertEquals(listOf("old", "new"), decode(read.lists, read.devices, read.packs).lists.single().records.map { it.id })
    store.modify(owner) { w -> deleteRecord(w, "a", "old") to true }
    assertEquals(listOf("new"), rows(owner).map { it.second })
  }

  // modify on an account that has saved nothing creates its row, which has no lists to add to.
  @Test fun modifyCreatesTheRow() {
    val owner = owner()
    assertNull(store.readRevision(owner))
    store.modify(owner) { w -> w to false }
    assertNotNull(store.readRevision(owner))
  }

  @Test fun revocations() {
    val owner = owner()
    assertNull(store.sessionsRevokedAt(owner))
    store.revokeSessions(owner)
    val first = assertNotNull(store.sessionsRevokedAt(owner))
    store.revokeSessions(owner)
    assertTrue(!store.sessionsRevokedAt(owner)!!.isBefore(first))
  }

  // Go's strconv.FormatFloat(value, 'g', -1, 64), which legacyId hashes.
  @Test fun formatsNumbersAsGo() {
    val cases = mapOf(3.0 to "3", 2.5 to "2.5", 0.0001 to "0.0001", 0.00001 to "1e-05", 123456.0 to "123456", 1234567.0 to "1.234567e+06",
      1e21 to "1e+21", 0.1 to "0.1", 100.0 to "100", 12.345 to "12.345")
    for ((value, want) in cases) assertEquals(want, formatG(value), value.toString())
  }
}
