// Package store reads and writes accounts' workspaces in the app's database (the typle_workspaces table, see
// prisma/schema.prisma) with a revision, and the packs they share by link (typle_shared_packs), as the Go backend's
// server/internal/store (ReadRevision, WriteRevision, PutShare, DeleteShare, ListShares and SharedPack).
package io.github.sparklingstadt.typle.server.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.sparklingstadt.typle.server.workspace.Device
import io.github.sparklingstadt.typle.server.workspace.InvalidWorkspaceException
import io.github.sparklingstadt.typle.server.workspace.MAX_LISTS
import io.github.sparklingstadt.typle.server.workspace.Pack
import io.github.sparklingstadt.typle.server.workspace.WordList
import io.github.sparklingstadt.typle.server.workspace.Workspace
import io.github.sparklingstadt.typle.server.workspace.WorkspaceError
import io.github.sparklingstadt.typle.server.workspace.checkInterests
import io.github.sparklingstadt.typle.server.workspace.checkLimits
import io.github.sparklingstadt.typle.server.workspace.decode
import io.github.sparklingstadt.typle.server.workspace.decodeInterests
import io.github.sparklingstadt.typle.server.workspace.encode
import io.github.sparklingstadt.typle.server.workspace.encodeDevices
import io.github.sparklingstadt.typle.server.workspace.encodeInterests
import io.github.sparklingstadt.typle.server.workspace.encodePackFile
import io.github.sparklingstadt.typle.server.workspace.encodePackFileAgain
import io.github.sparklingstadt.typle.server.workspace.encodePacks
import io.github.sparklingstadt.typle.server.workspace.encodeWithLabels
import io.github.sparklingstadt.typle.server.workspace.readSaved
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A workspace as stored: its lists, devices, packs and interests columns as the database's own text of the jsonb, so
 * object keys may be in another order than they were written in, with the revision a later save must name. packs is
 * null when the column is NULL: the lists were saved before packs existed. interests is null when its column is NULL:
 * none have been saved.
 */
data class Saved(val lists: String, val devices: String, val packs: String?, val revision: Long, val interests: String? = null)

/**
 * What a save sets. A part that is not set (hasLists, hasDevices, hasPacks, hasInterests) stays as it is. Interests
 * are only the app's (Go's /api/v1 leaves them as they are). Packs are set only with the lists they hold, which are then written without labels (encode). Lists set without packs come from an app that does
 * not know packs: they are written as it sent them, labels and all (encodeWithLabels), for the app to settle with the
 * saved packs when it reads them.
 */
data class Change(
  val lists: List<WordList>, val devices: List<Device>, val hasLists: Boolean, val hasDevices: Boolean,
  val packs: List<Pack> = emptyList(), val hasPacks: Boolean = false,
  val interests: List<String> = emptyList(), val hasInterests: Boolean = false,
)

/**
 * The revision a save was made against is no longer the saved one, or the workspace it expected to create is already
 * there. The app reloads and asks again (409).
 */
class ConflictException : Exception("workspace revision is stale")

/** The largest snapshot a link keeps (Go's MaxSharedPackBytes), in bytes of UTF-8. */
const val MAX_SHARED_PACK_BYTES = 1 shl 20

/** A link to one of the owner's packs; name is the pack's name in the snapshot, or "" when it has none. */
data class Share(val id: String, val packId: String, val name: String, val updatedAt: Instant)

/** What the handlers need of the database. */
interface Store {
  fun ping()

  fun readRevision(owner: String): Saved?

  fun writeRevision(owner: String, revision: Long, change: Change): Saved

  fun putShare(owner: String, packId: String): Share

  fun deleteShare(owner: String, packId: String)

  fun listShares(owner: String): List<Share>

  fun sharedPack(id: String): String?
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
   * The owner's saved workspace with its revision, or null when nothing has been saved. The row is checked with decode
   * and decodeInterests, so saved data that cannot be read is an error, but it is answered as stored.
   */
  override fun readRevision(owner: String): Saved? {
    val saved = query("SELECT $SAVED_COLUMNS FROM typle_workspaces WHERE owner_id = ?", owner) ?: return null
    decode(saved.lists, saved.devices, saved.packs)
    decodeInterests(saved.interests)
    return saved
  }

  /**
   * Saves change over the workspace at revision: revision 0 creates the row, any other revision replaces what is saved
   * only if it is still that revision (else ConflictException). Lists may be more than MAX_LISTS in saved data merged
   * from removed profiles; a save may keep or lower that number, never raise it. A save with packs deletes, in the same
   * transaction, the owner's links to packs that are no longer among them. Interests left out are kept as saved. So does a save of lists without packs, against
   * the packs the row then has (derived from the lists when it has none); devices alone leave the links as they are.
   *
   * Throws ConflictException for a stale revision, WorkspaceError for lists past the limits or packs without lists
   * (invalidWorkspace), interests the app would refuse (invalidWorkspace) or more lists than allowed (listLimit),
   * InvalidWorkspaceException for a change decode would
   * refuse, and any other exception when the change cannot be written. Whether packs hold their lists (checkLibrary) is
   * the handler's to check, on what was sent. All of this, apart from the conflict and the saved number of lists, is
   * checked before the database is touched.
   */
  override fun writeRevision(owner: String, revision: Long, change: Change): Saved {
    // null is a part left out: NULL in the queries below.
    var lists: String? = null
    var packs: String? = null
    var devices: String? = null
    var interests: String? = null
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
    if (change.hasInterests) {
      // Interests the app would refuse are refused as the request would be.
      try {
        checkInterests(change.interests)
      } catch (_: InvalidWorkspaceException) {
        throw WorkspaceError("invalidWorkspace")
      }
      interests = encodeInterests(change.interests)
    }
    // A part left out is saved as it is, so only the other one can be refused; it is read with an empty one.
    decode(lists ?: "[]", devices ?: "[]", packs)
    return transaction { db ->
      val saved = writeRevision(db, owner, revision, change, lists, packs, devices, interests)
      if (change.hasPacks) {
        deleteRemovedShares(db, owner, change.packs.map { it.id })
      } else if (change.hasLists) {
        val w = try {
          decode(saved.lists, saved.devices, saved.packs)
        } catch (e: InvalidWorkspaceException) {
          throw IllegalStateException("saved workspace of $owner: ${e.message}", e)
        }
        deleteRemovedShares(db, owner, packsAfterLists(w))
      }
      saved
    }
  }

  // The database part of writeRevision, in the transaction of db.
  private fun writeRevision(db: Connection, owner: String, revision: Long, change: Change, lists: String?, packs: String?,
    devices: String?, interests: String?): Saved {
    if (revision == 0L) {
      if (change.lists.size > MAX_LISTS) throw WorkspaceError("listLimit")
      // The links Go's PutShare made from the initial packs are seen by deleteRemovedShares (see lockOwner).
      lockOwner(db, owner)
      // Unique owner makes the existence check and the insert one atomic operation. Lists created without packs are
      // lists saved before packs existed: packs NULL. Interests left out are none saved: NULL.
      return query(db, """INSERT INTO typle_workspaces (owner_id, lists, packs, devices, interests)
        VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb) ON CONFLICT (owner_id) DO NOTHING RETURNING $SAVED_COLUMNS""",
        owner, lists ?: "[]", packs, devices ?: "[]", interests)
        ?: throw ConflictException()
    }
    val (savedRevision, savedLists) =
      db.prepareStatement("SELECT revision, jsonb_array_length(lists) FROM typle_workspaces WHERE owner_id = ?").use { q ->
        q.setString(1, owner)
        q.executeQuery().use { r -> if (r.next()) r.getLong(1) to r.getInt(2) else null }
      } ?: throw ConflictException()
    if (savedRevision != revision) throw ConflictException()
    if (change.lists.size > MAX_LISTS && change.lists.size > savedLists) throw WorkspaceError("listLimit")
    // Matching the revision in the UPDATE itself makes the comparison and the write one atomic operation.
    return query(db, """UPDATE typle_workspaces SET lists = COALESCE(?::jsonb, lists), packs = COALESCE(?::jsonb, packs),
      devices = COALESCE(?::jsonb, devices), interests = COALESCE(?::jsonb, interests), revision = revision + 1,
      updated_at = now() WHERE owner_id = ? AND revision = ? RETURNING $SAVED_COLUMNS""",
      lists, packs, devices, interests, owner, revision)
      ?: throw ConflictException()
  }

  /**
   * Shares the owner's pack packId as it is saved, or copies it again when it is already shared, keeping the link (Go's
   * PutShare). A save that removes the pack at the same moment must delete the link: the row of the workspace is read
   * FOR UPDATE, after the owner's lock (lockOwner) that a first save takes too, so the save either comes first (and the
   * pack is not found) or deletes the link once this is done.
   *
   * Unlike Go, an account that has saved nothing has no pack to share (packNotFound): Kotlin does not make the initial
   * workspace, nor does it turn lists saved before packs existed into packs. A pack not in the workspace is
   * packNotFound (WorkspaceError); a snapshot past MAX_SHARED_PACK_BYTES is invalidWorkspace. Lists of the pack whose
   * levels overlap are shared separated, as Go's Decode reads them (readSaved).
   */
  override fun putShare(owner: String, packId: String): Share = transaction { db ->
    lockOwner(db, owner)
    val saved = db.prepareStatement(
      "SELECT $SAVED_COLUMNS FROM typle_workspaces WHERE owner_id = ? FOR UPDATE",
    ).use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r -> if (r.next()) saved(r) else null }
    } ?: throw WorkspaceError("packNotFound")
    // Read as Go reads the row, with overlapping levels separated, so the snapshot can be added again (readSaved).
    val workspace = try {
      readSaved(saved.lists, saved.devices, saved.packs)
    } catch (e: InvalidWorkspaceException) {
      throw IllegalStateException("saved workspace of $owner: ${e.message}", e)
    }
    val data = encodePackFile(workspace, packId)
    if (data.toByteArray(Charsets.UTF_8).size > MAX_SHARED_PACK_BYTES) throw WorkspaceError("invalidWorkspace")
    db.prepareStatement("""INSERT INTO typle_shared_packs (id, owner_id, pack_id, data) VALUES (?::uuid, ?, ?, ?::jsonb)
      ON CONFLICT (owner_id, pack_id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()
      RETURNING id::text, COALESCE(data #>> '{pack,name}', ''), updated_at""").use { q ->
      bind(q, arrayOf(UUID.randomUUID().toString(), owner, packId, data))
      q.executeQuery().use { r ->
        r.next()
        Share(r.getString(1), packId, r.getString(2), r.getObject(3, OffsetDateTime::class.java).toInstant())
      }
    }
  }

  /** Stops sharing the owner's pack. A pack that is not shared is left as it is, without an error. */
  override fun deleteShare(owner: String, packId: String) {
    dataSource.connection.use { db ->
      db.prepareStatement("DELETE FROM typle_shared_packs WHERE owner_id = ? AND pack_id = ?").use { q ->
        bind(q, arrayOf(owner, packId))
        q.executeUpdate()
      }
    }
  }

  /** The owner's links, the most recently updated first. */
  override fun listShares(owner: String): List<Share> = dataSource.connection.use { db ->
    db.prepareStatement("""SELECT id::text, pack_id, COALESCE(data #>> '{pack,name}', ''), updated_at FROM typle_shared_packs WHERE owner_id = ?
      ORDER BY updated_at DESC, id""").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r ->
        buildList { while (r.next()) add(Share(r.getString(1), r.getString(2), r.getString(3), r.getObject(4, OffsetDateTime::class.java).toInstant())) }
      }
    }
  }

  /**
   * The pack file a link shares, written as encodePackFile wrote it (jsonb keeps neither its spacing nor its key
   * order). It is for anyone with the link, so it has nothing of the owner. A link that is not there, or an id that is
   * not a UUID, is null.
   */
  override fun sharedPack(id: String): String? {
    if (!isUuid(id)) return null
    val data = dataSource.connection.use { db ->
      db.prepareStatement("SELECT data::text FROM typle_shared_packs WHERE id = ?::uuid").use { q ->
        q.setString(1, id)
        q.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
      }
    } ?: return null
    return try {
      encodePackFileAgain(data)
    } catch (e: InvalidWorkspaceException) {
      throw IllegalStateException("shared pack $id: ${e.message}", e)
    }
  }

  // The packs a row has once its lists are saved without packs, as the ids Go's Decode leaves: a saved pack some list is
  // in, and "pack-<id>" for each list that is in none (a row without packs: for every list, a superset of the packs Go's
  // conversion makes, so no link of Go's is deleted that Go keeps). Kotlin does not repair the packs themselves, so only
  // their ids are known, and ids are all deleteRemovedShares needs.
  private fun packsAfterLists(w: Workspace): List<String> {
    val ids = w.packs.map { it.id }
    val saved = ids.toSet()
    val used = w.lists.map { it.packId }.toSet()
    val own = w.lists.filter { it.packId.isEmpty() || it.packId !in saved }.map { "pack-" + it.id }
    return ids.filter { it in used } + own
  }

  // Deletes, in the transaction of a save, the owner's links to packs that are no longer among the packs saved.
  private fun deleteRemovedShares(db: Connection, owner: String, packIds: Collection<String>) {
    db.prepareStatement("DELETE FROM typle_shared_packs WHERE owner_id = ? AND NOT (pack_id = ANY(?::text[]))").use { q ->
      q.setString(1, owner)
      q.setArray(2, db.createArrayOf("text", packIds.toTypedArray()))
      q.executeUpdate()
    }
  }

  // Holds the owner's advisory lock until the end of the transaction of db, as Go's lockOwner: it stands in for the
  // row lock where there is no row yet. putShare takes it, and so does every save that creates the row.
  private fun lockOwner(db: Connection, owner: String) {
    db.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('typle_workspaces:' || ?))").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { it.next() }
    }
  }

  // Runs block in one transaction, committed when it returns and rolled back when it throws.
  private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { db ->
    db.autoCommit = false
    try {
      block(db).also { db.commit() }
    } catch (e: Throwable) {
      db.rollback()
      throw e
    } finally {
      db.autoCommit = true
    }
  }

  // Runs a statement that returns at most one row of SAVED_COLUMNS.
  private fun query(sql: String, vararg arguments: Any?): Saved? = dataSource.connection.use { db -> query(db, sql, *arguments) }

  private fun query(db: Connection, sql: String, vararg arguments: Any?): Saved? = db.prepareStatement(sql).use { q ->
    bind(q, arguments)
    q.executeQuery().use { r -> if (r.next()) saved(r) else null }
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

  private fun saved(r: ResultSet) = Saved(r.getString(1), r.getString(3), r.getString(2), r.getLong(5), r.getString(4))
}

// The columns saved reads, as Go's savedColumns. Interests not saved (NULL) are read as null.
private const val SAVED_COLUMNS = "lists::text, packs::text, devices::text, interests::text, revision"


/** Whether id is a UUID as the links are written: 8-4-4-4-12 hexadecimal digits, of either case (Go's isUUID). */
internal fun isUuid(id: String): Boolean = id.length == 36 && id.withIndex().all { (i, c) ->
  if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
