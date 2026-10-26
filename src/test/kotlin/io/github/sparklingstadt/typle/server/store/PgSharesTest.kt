package io.github.sparklingstadt.typle.server.store

import io.github.sparklingstadt.typle.server.workspace.Pack
import io.github.sparklingstadt.typle.server.workspace.Word
import io.github.sparklingstadt.typle.server.workspace.WordList
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.decode
import io.github.sparklingstadt.typle.server.workspace.encode
import io.github.sparklingstadt.typle.server.workspace.encodePackFile
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

// The cases of server/internal/store/shares_test.go, against TYPLE_TEST_DATABASE_URL (skipped without it).
class PgSharesTest {
  private val connection = System.getenv("TYPLE_TEST_DATABASE_URL").orEmpty()
  private lateinit var store: PgStore
  private val owners = ArrayList<String>()

  @BeforeTest fun open() {
    assumeTrue(connection.isNotEmpty(), "TYPLE_TEST_DATABASE_URL is not set")
    store = PgStore.open(connection)
  }

  @AfterTest fun close() {
    if (!::store.isInitialized) return
    sql().use { db ->
      for (owner in owners) {
        db.prepareStatement("DELETE FROM typle_shared_packs WHERE owner_id = ?").use { it.setString(1, owner); it.executeUpdate() }
      }
    }
    store.close()
  }

  private fun sql(): java.sql.Connection {
    val target = jdbcTarget(connection)
    return DriverManager.getConnection(target.url, target.user, target.password)
  }

  private fun owner() = ("github:7" + Random.nextLong(100_000_000_000L, 999_999_999_999L)).also { owners.add(it) }

  private fun list(id: String, packId: String, display: String = "a") =
    WordList(id, "list $id", listOf(Word(display, "a", "")), emptyList(), "2026-10-01", packId = packId)

  private fun pack(id: String, name: String = "Pack $id") = Pack(id, name, createdAt = "2026-10-01")

  private fun library(lists: List<WordList>, packs: List<Pack>) =
    Change(lists, emptyList(), hasLists = true, hasDevices = false, packs = packs, hasPacks = true)

  // Two packs, a and b, saved at revision 1.
  private fun twoPacks(owner: String): Saved =
    store.writeRevision(owner, 0, library(listOf(list("1", "a"), list("2", "b")), listOf(pack("a"), pack("b"))))

  @Test fun putShare() {
    val owner = owner()
    val saved = twoPacks(owner)
    val first = store.putShare(owner, "a")
    assertEquals("a", first.packId)
    assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(first.id), first.id)
    assertEquals(encodePackFile(decode(saved.lists, saved.devices, saved.packs), "a"), store.sharedPack(first.id))

    // Sharing again keeps the link and copies the pack as it is now.
    val renamed = store.writeRevision(owner, 1, library(listOf(list("1", "a"), list("2", "b")), listOf(pack("a", "Renamed"), pack("b"))))
    Thread.sleep(5)
    val second = store.putShare(owner, "a")
    assertEquals(first.id, second.id)
    assertTrue(second.updatedAt > first.updatedAt, "${second.updatedAt} > ${first.updatedAt}")
    val data = store.sharedPack(first.id)!!
    assertEquals(encodePackFile(decode(renamed.lists, renamed.devices, renamed.packs), "a"), data)
    assertTrue("Renamed" in data)

    assertEquals("packNotFound", assertFailsWith<WorkspaceError> { store.putShare(owner, "nope") }.key)
    // Kotlin does not make the initial workspace: an account without a row has no pack to share (see the README).
    assertEquals("packNotFound", assertFailsWith<WorkspaceError> { store.putShare(owner(), "a") }.key)
  }

  @Test fun sharedPackMissing() {
    assertNull(store.sharedPack("0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d"))
    assertNull(store.sharedPack("0B6F2C1E-3D4A-4F5B-8C9D-0E1F2A3B4C5D"))
    assertNull(store.sharedPack("nope"))
    assertNull(store.sharedPack(""))
    assertNull(store.sharedPack("0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5g"))
  }

  @Test fun deleteAndListShares() {
    val owner = owner()
    val other = owner()
    twoPacks(owner)
    twoPacks(other)
    val a = store.putShare(owner, "a")
    Thread.sleep(5)
    val b = store.putShare(owner, "b")
    val others = store.putShare(other, "a")
    assertEquals(listOf(b.id, a.id), store.listShares(owner).map { it.id })
    store.deleteShare(owner, "a")
    store.deleteShare(owner, "a")
    assertEquals(listOf(b), store.listShares(owner))
    assertNull(store.sharedPack(a.id))
    assertEquals(listOf(others.id), store.listShares(other).map { it.id })
    assertEquals(emptyList(), store.listShares(owner()))
  }

  @Test fun savesDeleteSharesOfRemovedPacks() {
    val owner = owner()
    twoPacks(owner)
    val a = store.putShare(owner, "a")
    val b = store.putShare(owner, "b")
    // Lists alone leave the links as they are, even of a pack none of them is in.
    store.writeRevision(owner, 1, Change(listOf(list("2", "b")), emptyList(), hasLists = true, hasDevices = false))
    assertEquals(setOf(a.id, b.id), store.listShares(owner).map { it.id }.toSet())
    // Devices alone too.
    store.writeRevision(owner, 2, Change(emptyList(), emptyList(), hasLists = false, hasDevices = true))
    assertEquals(2, store.listShares(owner).size)
    // Lists with packs delete the links of the packs that are not among them.
    store.writeRevision(owner, 3, library(listOf(list("2", "b")), listOf(pack("b"))))
    assertEquals(listOf(b.id), store.listShares(owner).map { it.id })
    // A save that fails changes nothing.
    assertFailsWith<ConflictException> { store.writeRevision(owner, 3, library(listOf(list("3", "c")), listOf(pack("c")))) }
    assertEquals(listOf(b.id), store.listShares(owner).map { it.id })
  }

  @Test fun putShareTooLarge() {
    val owner = owner()
    store.writeRevision(owner, 0, library(listOf(list("1", "a")), listOf(pack("a"))))
    // Past the limits of a save, which only a row written otherwise can have.
    sql().use { db ->
      db.prepareStatement("UPDATE typle_workspaces SET lists = ?::jsonb WHERE owner_id = ?").use { q ->
        q.setString(1, encode(listOf(list("1", "a", "x".repeat(1 shl 20)))))
        q.setString(2, owner)
        q.executeUpdate()
      }
    }
    assertEquals("invalidWorkspace", assertFailsWith<WorkspaceError> { store.putShare(owner, "a") }.key)
    assertEquals(emptyList(), store.listShares(owner))
    assertNotEquals(0, store.readRevision(owner)!!.revision)
  }
}
