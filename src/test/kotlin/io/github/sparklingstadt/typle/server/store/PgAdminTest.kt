package io.github.sparklingstadt.typle.server.store

import java.sql.DriverManager
import java.sql.SQLException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

// Go's TestAdmin and TestOverview (server/internal/store/store_test.go). They run against TYPLE_TEST_DATABASE_URL, as
// PgStoreTest does, and are skipped without it.
class PgAdminTest {
  private val connection = System.getenv("TYPLE_TEST_DATABASE_URL").orEmpty()
  private lateinit var store: PgStore
  private val emails = mutableListOf<String>()

  @BeforeTest fun open() {
    assumeTrue(connection.isNotEmpty(), "TYPLE_TEST_DATABASE_URL is not set")
    store = PgStore.open(connection)
  }

  @AfterTest fun close() {
    if (!::store.isInitialized) return
    for (email in emails) exec("DELETE FROM typle_admins WHERE email = ?", email)
    store.close()
  }

  private fun exec(statement: String, vararg arguments: String): Int {
    val target = jdbcTarget(connection)
    return DriverManager.getConnection(target.url, target.user, target.password).use { db ->
      db.prepareStatement(statement).use { q ->
        arguments.forEachIndexed { i, value -> q.setString(i + 1, value) }
        q.executeUpdate()
      }
    }
  }

  private fun <T> query(statement: String, argument: String, read: (java.sql.ResultSet) -> T): T {
    val target = jdbcTarget(connection)
    return DriverManager.getConnection(target.url, target.user, target.password).use { db ->
      db.prepareStatement(statement).use { q ->
        q.setString(1, argument)
        q.executeQuery().use { r ->
          r.next()
          read(r)
        }
      }
    }
  }

  // A fresh account for each use, so that tests do not see each other's rows.
  private fun owner() = "github:9" + Random.nextLong(100_000_000_000L, 999_999_999_999L)

  // An administrator's email of no one else's test, removed after the test.
  private fun email() = ("kotlin-test-" + Random.nextLong(100_000_000_000L, 999_999_999_999L) + "@example.com").also { emails.add(it) }

  @Test fun admin() {
    val email = email()
    assertFalse(store.admin(email, "sub1"), "without a row")
    exec("INSERT INTO typle_admins (email) VALUES (?)", email)
    // An empty sub is not bound to the row, and an empty email is nobody.
    for ((e, s) in listOf(email to "", "" to "sub1", "" to "")) assertFalse(store.admin(e, s), "admin($e, $s)")
    assertNull(query("SELECT ffpf_sub FROM typle_admins WHERE email = ?", email) { it.getString(1) })
    assertTrue(store.admin(email.uppercase(), "sub1"), "first")
    val (sub, seen) = query("SELECT ffpf_sub, last_seen_at FROM typle_admins WHERE email = ?", email) { it.getString(1) to it.getObject(2) }
    assertEquals("sub1", sub)
    assertNotNull(seen)
    assertFalse(store.admin(email, "sub2"), "another sub")
    assertTrue(store.admin(email, "sub1"), "again")
    // An email that is not in lower case cannot be stored.
    assertFailsWith<SQLException> { exec("INSERT INTO typle_admins (email) VALUES ('Admin@example.com')") }
  }

  @Test fun overview() {
    val before = store.overview()
    val id = owner()
    exec("""INSERT INTO typle_workspaces (owner_id, lists, devices, packs, revision, updated_at)
      VALUES (?, '[{"id":"a"},{"id":"b"}]', '[{"id":"d"}]', NULL, 4, now() + interval '1 hour')""", id)
    exec("""INSERT INTO typle_shared_packs (id, owner_id, pack_id, data, updated_at)
      VALUES (gen_random_uuid(), ?, 'p', '{"pack":{"name":"Words"}}', now() + interval '1 hour')""", id)
    val adminEmail = email()
    exec("INSERT INTO typle_admins (email) VALUES (?)", adminEmail)
    val after = store.overview()
    assertEquals(listOf(before.workspaces + 1, before.activeDay + 1, before.activeMonth + 1, before.sharedPacks + 1),
      listOf(after.workspaces, after.activeDay, after.activeMonth, after.sharedPacks), "before $before, after $after")
    val users = store.users(0)
    assertEquals(after.workspaces, users.total)
    assertTrue(users.users.size <= ADMIN_USERS_PAGE)
    val first = users.users[0]
    assertEquals(AdminUser(id, 2, 0, 1, 1, 4, first.updatedAt), first)
    // A page past the last is empty, not an error.
    val past = store.users(users.total / ADMIN_USERS_PAGE + 1)
    assertEquals(users.total to emptyList(), past.total to past.users)
    assertEquals(id to "Words", after.recentShares[0].ownerId to after.recentShares[0].name)
    assertTrue(after.admins.any { it.email == adminEmail && it.ffpfSub == null && it.lastSeenAt == null }, after.admins.toString())
  }
}
