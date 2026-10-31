// Package store reads and writes accounts' workspaces in the app's database (the typle_workspaces table, and their
// practice records in typle_records: see Records.kt and prisma/schema.prisma) with a revision, the packs they share by
// link (typle_shared_packs), when each account signed out of every device (typle_session_revocations), and the
// administrators of /admin (typle_admins: see Admin.kt), as the Go backend's server/internal/store (ReadRevision,
// WriteRevision, Modify, PutShare, DeleteShare, ListShares, SharedPack, RevokeSessions, SessionsRevokedAt, Admin,
// Overview and Users).
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
import io.github.sparklingstadt.typle.server.workspace.readSaved
import io.github.sparklingstadt.typle.server.workspace.repaired
import io.github.sparklingstadt.typle.server.workspace.withoutFollowedWords
import io.github.sparklingstadt.typle.server.workspace.decodeRecordText
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
 * are only the app's (Go's /api/v1 leaves them as they are). Packs are set only with their lists, which are written
 * without labels (encode); lists set without packs are each put in a pack of their own (typle-r v2.0.0).
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

/** When each account last signed out of every device (Go's web.SessionSource). */
interface SessionSource {
  /** Signs the owner out of every device: every session and app token of a login until now (Go's RevokeSessions). */
  fun revokeSessions(owner: String)

  /** When the owner last signed out of every device, or null when they never did (Go's SessionsRevokedAt). */
  fun sessionsRevokedAt(owner: String): Instant?
}

/** What the handlers need of the database. */
interface Store : SessionSource, AdminSource {
  fun ping()

  fun readRevision(owner: String): Saved?

  fun writeRevision(owner: String, revision: Long, change: Change): Saved

  fun putShare(owner: String, packId: String): Share

  fun deleteShare(owner: String, packId: String)

  fun listShares(owner: String): List<Share>

  fun sharedPack(id: String): String?

  /**
   * Changes the owner's workspace, read as Go's Decode reads it with its records, and saves it in one transaction that
   * holds the row (Go's Modify). change returns the workspace and whether it changed; nothing is written when it did not.
   */
  fun modify(owner: String, change: (Workspace) -> Pair<Workspace, Boolean>): Workspace

  /** The dashboard's figures (Go's Overview). */
  fun overview(): AdminOverview

  /** Page (from 0) of the user list (Go's Users). */
  fun users(page: Int): AdminUsers
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

  override fun admin(email: String, sub: String): Boolean = admin(dataSource, email, sub)

  override fun overview(): AdminOverview = overview(dataSource)

  override fun users(page: Int): AdminUsers = users(dataSource, page)

  /**
   * The owner's saved workspace with its revision, or null when nothing has been saved. The row is checked with decode
   * and decodeInterests, so saved data that cannot be read is an error, but it is answered as stored.
   */
  override fun readRevision(owner: String): Saved? {
    val saved = dataSource.connection.use { db -> readSaved(db, owner) } ?: return null
    readSaved(saved.lists, saved.devices, saved.packs)
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
    // null is a part left out: NULL in the queries below. full is the lists with their records, as the app sent them;
    // lists, as the column keeps them, have none: rows are their records, for typle_records.
    var lists: String? = null
    var full: String? = null
    var packs: String? = null
    var devices: String? = null
    var interests: String? = null
    var rows: List<RecordRow> = emptyList()
    if (change.hasPacks && !change.hasLists) throw WorkspaceError("invalidWorkspace")
    var saving = change
    // Lists sent without packs are each put in a pack of their own, as Go's Decode repairs lists without one.
    if (change.hasLists && !change.hasPacks) {
      checkLimits(change.lists)
      val w = repaired(Workspace(change.lists, emptyList(), emptyList()))
      saving = change.copy(lists = w.lists, packs = w.packs, hasPacks = true)
    }
    if (saving.hasLists) {
      checkLimits(saving.lists)
      packs = encodePacks(saving.packs)
      val (identified, recordRows) = recordRows(saving.lists)
      rows = recordRows
      // Followed packs' lists are saved without their words, which the catalog has.
      val slim = withoutFollowedWords(saving.packs, identified)
      full = encode(slim)
      lists = encode(withoutRecords(slim))
    }
    if (saving.hasDevices) devices = encodeDevices(saving.devices)
    if (saving.hasInterests) {
      // Interests the app would refuse are refused as the request would be.
      try {
        checkInterests(saving.interests)
      } catch (_: InvalidWorkspaceException) {
        throw WorkspaceError("invalidWorkspace")
      }
      interests = encodeInterests(saving.interests)
    }
    // A part left out is saved as it is, so only the other one can be refused; it is read with an empty one.
    readSaved(full ?: "[]", devices ?: "[]", packs ?: "[]")
    return transaction { db ->
      writeRevision(db, owner, revision, saving, lists, packs, devices, interests)
      // Lists saved bring their records: the owner's rows become exactly theirs.
      if (saving.hasLists) syncRecords(db, owner, null, rows)
      val saved = readSaved(db, owner) ?: throw ConflictException()
      if (saving.hasPacks) deleteRemovedShares(db, owner, saving.packs.map { it.id })
      saved
    }
  }

  // The database part of writeRevision, in the transaction of db. Lists written have their records in typle_records
  // (records_split), which the caller then writes.
  private fun writeRevision(db: Connection, owner: String, revision: Long, change: Change, lists: String?, packs: String?,
    devices: String?, interests: String?) {
    if (revision == 0L) {
      if (change.lists.size > MAX_LISTS) throw WorkspaceError("listLimit")
      // The links Go's PutShare made from the initial packs are seen by deleteRemovedShares (see lockOwner).
      lockOwner(db, owner)
      // Unique owner makes the existence check and the insert one atomic operation. Interests left out are none saved: NULL.
      val created = db.prepareStatement("""INSERT INTO typle_workspaces (owner_id, lists, packs, devices, interests, records_split)
        VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, true) ON CONFLICT (owner_id) DO NOTHING RETURNING revision""").use { q ->
        bind(q, arrayOf(owner, lists ?: "[]", packs, devices ?: "[]", interests))
        q.executeQuery().use { it.next() }
      }
      if (!created) throw ConflictException()
      forgetRecords(db, owner)
      return
    }
    val (savedRevision, savedLists) =
      db.prepareStatement("SELECT revision, jsonb_array_length(lists) FROM typle_workspaces WHERE owner_id = ?").use { q ->
        q.setString(1, owner)
        q.executeQuery().use { r -> if (r.next()) r.getLong(1) to r.getInt(2) else null }
      } ?: throw ConflictException()
    if (savedRevision != revision) throw ConflictException()
    if (change.lists.size > MAX_LISTS && change.lists.size > savedLists) throw WorkspaceError("listLimit")
    // Matching the revision in the UPDATE itself makes the comparison and the write one atomic operation.
    val updated = db.prepareStatement("""UPDATE typle_workspaces SET lists = COALESCE(?::jsonb, lists), packs = COALESCE(?::jsonb, packs),
      devices = COALESCE(?::jsonb, devices), interests = COALESCE(?::jsonb, interests),
      records_split = records_split OR ?::jsonb IS NOT NULL, revision = revision + 1, updated_at = now()
      WHERE owner_id = ? AND revision = ? RETURNING revision""").use { q ->
      bind(q, arrayOf(lists, packs, devices, interests, lists, owner, revision))
      q.executeQuery().use { it.next() }
    }
    if (!updated) throw ConflictException()
  }

  /**
   * Changes the owner's workspace and saves it, in one transaction that holds the row locked, as Go's Modify. An account
   * that has saved nothing starts from the initial workspace (empty). When change reports nothing changed, nothing is
   * written. Lists that grow past MAX_LISTS_BYTES are refused (workspaceTooLarge), and the links to packs the change
   * removed are deleted with it.
   */
  override fun modify(owner: String, change: (Workspace) -> Pair<Workspace, Boolean>): Workspace = transaction { db ->
    val (w, before) = lock(db, owner)
    val beforeSize = encode(withoutFollowedWords(w.packs, w.lists)).toByteArray().size + encodePacks(w.packs).toByteArray().size
    val (changed, isChanged) = change(w)
    if (!isChanged) return@transaction changed
    // Records are given ids before anything is encoded, so the workspace returned and the rows agree.
    val (identified, rows) = recordRows(changed.lists)
    val result = changed.copy(lists = identified)
    val after = encode(withoutFollowedWords(result.packs, result.lists))
    val afterPacks = encodePacks(result.packs)
    val afterSize = after.toByteArray().size + afterPacks.toByteArray().size
    if (afterSize > MAX_LISTS_BYTES && afterSize > beforeSize) throw WorkspaceError("workspaceTooLarge")
    val devices = encodeDevices(result.devices)
    // A workspace that would not be read back is never saved: the account would no longer load, here or in the app.
    try {
      readSaved(after, devices, afterPacks)
    } catch (e: InvalidWorkspaceException) {
      throw IllegalStateException("changed workspace of $owner: ${e.message}", e)
    }
    db.prepareStatement("""UPDATE typle_workspaces SET lists = ?::jsonb, packs = ?::jsonb, devices = ?::jsonb,
      records_split = true, revision = revision + 1, updated_at = now() WHERE owner_id = ?""").use { q ->
      bind(q, arrayOf(encode(withoutRecords(withoutFollowedWords(result.packs, result.lists))), afterPacks, devices, owner))
      q.executeUpdate()
    }
    syncRecords(db, owner, before, rows)
    deleteRemovedShares(db, owner, result.packs.map { it.id })
    result
  }

  // Reads the owner's row FOR UPDATE, first inserting one with the initial (empty) workspace when there is none, as Go's
  // lock. The records are read after the row is locked: before is them as rows, or null when the workspace's records
  // have not moved to typle_records yet.
  private fun lock(db: Connection, owner: String): Pair<Workspace, Map<RecordKey, RecordRow>?> {
    var inserted = false
    while (true) {
      val row = db.prepareStatement("""SELECT lists::text, packs::text, devices::text, records_split FROM typle_workspaces
        WHERE owner_id = ? FOR UPDATE""").use { q ->
        q.setString(1, owner)
        q.executeQuery().use { r -> if (r.next()) listOf(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4).toString()) else null }
      }
      if (row != null) {
        var lists = row[0]!!
        var before: Map<RecordKey, RecordRow>? = null
        if (row[3] == "true") {
          val stored = readRecords(db, owner)
          lists = withRecords(lists, stored.groupBy({ it.listId }, { io.github.sparklingstadt.typle.server.json.JsonValue.parse(it.data) }))
          // Each row as recordRows writes it, so that it compares with the record encoded here, not the database's text.
          before = stored.associate { RecordKey(it.listId, it.recordId) to it.copy(data = decodeRecordText(it.data)) }
        }
        val w = try {
          readSaved(lists, row[2]!!, row[1])
        } catch (e: InvalidWorkspaceException) {
          throw IllegalStateException("saved workspace of $owner: ${e.message}", e)
        }
        return w to before
      }
      if (inserted) throw IllegalStateException("workspace of $owner was not created")
      inserted = true
      // As the first save does (see lockOwner), so that the links PutShare made are seen.
      lockOwner(db, owner)
      val created = db.prepareStatement("""INSERT INTO typle_workspaces (owner_id, lists, packs, devices, records_split)
        VALUES (?, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, true) ON CONFLICT (owner_id) DO NOTHING""").use { q ->
        q.setString(1, owner)
        q.executeUpdate()
      }
      if (created == 1) forgetRecords(db, owner)
    }
  }

  override fun revokeSessions(owner: String) {
    dataSource.connection.use { db ->
      db.prepareStatement("""INSERT INTO typle_session_revocations (owner_id, revoked_at) VALUES (?, now())
        ON CONFLICT (owner_id) DO UPDATE SET revoked_at = EXCLUDED.revoked_at""").use { q ->
        q.setString(1, owner)
        q.executeUpdate()
      }
    }
  }

  override fun sessionsRevokedAt(owner: String): Instant? = dataSource.connection.use { db ->
    db.prepareStatement("SELECT revoked_at FROM typle_session_revocations WHERE owner_id = ?").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r -> if (r.next()) r.getObject(1, OffsetDateTime::class.java).toInstant() else null }
    }
  }

  // The owner's row as stored, with its records put back in its lists (see withRecords), in one statement, as Go's
  // readSaved; null when there is none.
  private fun readSaved(db: Connection, owner: String): Saved? =
    db.prepareStatement("SELECT $SAVED_COLUMNS, $RECORDS_COLUMN FROM typle_workspaces WHERE owner_id = ?").use { q ->
      q.setString(1, owner)
      q.executeQuery().use { r ->
        if (!r.next()) return null
        val saved = saved(r)
        val records = r.getString(6) ?: return saved
        saved.copy(lists = withRecords(saved.lists, decodeColumn(records)))
      }
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

  private fun bind(q: PreparedStatement, arguments: Array<out Any?>) {
    arguments.forEachIndexed { i, value ->
      when (value) {
        null, is String -> q.setString(i + 1, value as String?)
        is Long -> q.setLong(i + 1, value)
        is Int -> q.setInt(i + 1, value)
        else -> error("unexpected argument ${value::class}")
      }
    }
  }

  private fun saved(r: ResultSet) = Saved(r.getString(1), r.getString(3), r.getString(2), r.getLong(5), r.getString(4))
}

// The largest the lists and packs may grow to in a change (Go's MaxListsBytes), in bytes.
private const val MAX_LISTS_BYTES = 896 shl 10

// The columns saved reads. Interests not saved (NULL) are read as null.
private const val SAVED_COLUMNS = "lists::text, packs::text, devices::text, interests::text, revision"


/** Whether id is a UUID as the links are written: 8-4-4-4-12 hexadecimal digits, of either case (Go's isUUID). */
internal fun isUuid(id: String): Boolean = id.length == 36 && id.withIndex().all { (i, c) ->
  if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
