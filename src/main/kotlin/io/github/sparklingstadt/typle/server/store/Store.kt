// Package store reads and writes accounts' workspaces in the app's database (the typle_workspaces table, see
// prisma/schema.prisma) with a revision, as the Go backend's server/internal/store (ReadRevision and WriteRevision).
package io.github.sparklingstadt.typle.server.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.sparklingstadt.typle.server.workspace.Device
import io.github.sparklingstadt.typle.server.workspace.MAX_LISTS
import io.github.sparklingstadt.typle.server.workspace.Pack
import io.github.sparklingstadt.typle.server.workspace.WordList
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.checkLimits
import io.github.sparklingstadt.typle.server.workspace.decode
import io.github.sparklingstadt.typle.server.workspace.encode
import io.github.sparklingstadt.typle.server.workspace.encodeDevices
import io.github.sparklingstadt.typle.server.workspace.encodePacks
import io.github.sparklingstadt.typle.server.workspace.encodeWithLabels
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * A workspace as stored: its lists, devices and packs columns as the database's own text of the jsonb, so object keys
 * may be in another order than they were written in, with the revision a later save must name. packs is null when the
 * column is NULL: the lists were saved before packs existed.
 */
data class Saved(val lists: String, val devices: String, val packs: String?, val revision: Long)

/**
 * What a save sets. A part that is not set (hasLists, hasDevices, hasPacks) stays as it is. Packs are set only with the
 * lists they hold, which are then written without labels (encode). Lists set without packs come from an app that does
 * not know packs: they are written as it sent them, labels and all (encodeWithLabels), for the app to settle with the
 * saved packs when it reads them.
 */
data class Change(
  val lists: List<WordList>, val devices: List<Device>, val hasLists: Boolean, val hasDevices: Boolean,
  val packs: List<Pack> = emptyList(), val hasPacks: Boolean = false,
)

/**
 * The revision a save was made against is no longer the saved one, or the workspace it expected to create is already
 * there. The app reloads and asks again (409).
 */
class ConflictException : Exception("workspace revision is stale")

/** What the handlers need of the database. */
interface Store {
  fun ping()

  fun readRevision(owner: String): Saved?

  fun writeRevision(owner: String, revision: Long, change: Change): Saved
}

class PgStore(private val dataSource: HikariDataSource) : Store, AutoCloseable {
  companion object {
    /**
     * Connects as the app's backend does, writable and with a small pool, and checks that the database answers.
     * Connections idle for 30 seconds are closed.
     */
    fun open(connection: String): PgStore {
      val target = jdbcTarget(connection)
      val config = HikariConfig().apply {
        jdbcUrl = target.url
        username = target.user
        password = target.password
        maximumPoolSize = 5
        minimumIdle = 0
        idleTimeout = 30_000
        connectionTimeout = 10_000
        poolName = "typle"
      }
      return PgStore(HikariDataSource(config))
    }
  }

  override fun ping() {
    dataSource.connection.use { db ->
      db.prepareStatement("SELECT 1").use { q ->
        q.queryTimeout = 5
        q.executeQuery().use { it.next() }
      }
    }
  }

  override fun close() = dataSource.close()

  /**
   * The owner's saved workspace with its revision, or null when nothing has been saved. The row is checked with decode,
   * so saved data that cannot be read is an error, but it is answered as stored.
   */
  override fun readRevision(owner: String): Saved? {
    val saved = query("SELECT lists::text, packs::text, devices::text, revision FROM typle_workspaces WHERE owner_id = ?", owner)
      ?: return null
    decode(saved.lists, saved.devices, saved.packs)
    return saved
  }

  /**
   * Saves change over the workspace at revision: revision 0 creates the row, any other revision replaces what is saved
   * only if it is still that revision (else ConflictException). Lists may be more than MAX_LISTS in saved data merged
   * from removed profiles; a save may keep or lower that number, never raise it.
   *
   * Throws ConflictException for a stale revision, WorkspaceError for lists past the limits or packs without lists
   * (invalidWorkspace) or more lists than allowed (listLimit), InvalidWorkspaceException for a change decode would
   * refuse, and any other exception when the change cannot be written. Whether packs hold their lists (checkLibrary) is
   * the handler's to check, on what was sent. All of this, apart from the conflict and the saved number of lists, is
   * checked before the database is touched.
   */
  override fun writeRevision(owner: String, revision: Long, change: Change): Saved {
    // null is a part left out: NULL in the queries below.
    var lists: String? = null
    var packs: String? = null
    var devices: String? = null
    if (change.hasPacks && !change.hasLists) throw WorkspaceError("invalidWorkspace")
    if (change.hasLists) {
      checkLimits(change.lists)
      if (change.hasPacks) {
        packs = encodePacks(change.packs)
        lists = encode(change.lists)
      } else {
        lists = encodeWithLabels(change.lists)
      }
    }
    if (change.hasDevices) devices = encodeDevices(change.devices)
    // A part left out is saved as it is, so only the other one can be refused; it is read with an empty one.
    decode(lists ?: "[]", devices ?: "[]", packs)
    if (revision == 0L) {
      if (change.lists.size > MAX_LISTS) throw WorkspaceError("listLimit")
      // Unique owner makes the existence check and the insert one atomic operation. Lists created without packs are
      // lists saved before packs existed: packs NULL.
      return query("""INSERT INTO typle_workspaces (owner_id, lists, packs, devices) VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb)
        ON CONFLICT (owner_id) DO NOTHING RETURNING lists::text, packs::text, devices::text, revision""",
        owner, lists ?: "[]", packs, devices ?: "[]")
        ?: throw ConflictException()
    }
    val (savedRevision, savedLists) = dataSource.connection.use { db ->
      db.prepareStatement("SELECT revision, jsonb_array_length(lists) FROM typle_workspaces WHERE owner_id = ?").use { q ->
        q.setString(1, owner)
        q.executeQuery().use { r -> if (r.next()) r.getLong(1) to r.getInt(2) else null }
      }
    } ?: throw ConflictException()
    if (savedRevision != revision) throw ConflictException()
    if (change.lists.size > MAX_LISTS && change.lists.size > savedLists) throw WorkspaceError("listLimit")
    // Matching the revision in the UPDATE itself makes the comparison and the write one atomic operation.
    return query("""UPDATE typle_workspaces SET lists = COALESCE(?::jsonb, lists), packs = COALESCE(?::jsonb, packs),
      devices = COALESCE(?::jsonb, devices), revision = revision + 1, updated_at = now() WHERE owner_id = ? AND revision = ?
      RETURNING lists::text, packs::text, devices::text, revision""", lists, packs, devices, owner, revision)
      ?: throw ConflictException()
  }

  // Runs a statement that returns at most one row of lists, packs, devices and revision.
  private fun query(sql: String, vararg arguments: Any?): Saved? = dataSource.connection.use { db ->
    db.prepareStatement(sql).use { q ->
      bind(q, arguments)
      q.executeQuery().use { r -> if (r.next()) saved(r) else null }
    }
  }

  private fun bind(q: PreparedStatement, arguments: Array<out Any?>) {
    arguments.forEachIndexed { i, value ->
      when (value) {
        null, is String -> q.setString(i + 1, value as String?)
        is Long -> q.setLong(i + 1, value)
        else -> error("unexpected argument ${value::class}")
      }
    }
  }

  private fun saved(r: ResultSet) = Saved(r.getString(1), r.getString(3), r.getString(2), r.getLong(4))
}
