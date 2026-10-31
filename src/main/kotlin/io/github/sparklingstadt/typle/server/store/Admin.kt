// The accounts that may sign in to /admin are kept in typle_admins, by FFPF ID email in lower case, as Go's
// server/internal/store/admin.go. The FFPF ID sub is bound at the first sign-in, so that whoever takes the email over
// later is not let in.
package io.github.sparklingstadt.typle.server.store

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource

/** How many shared packs the dashboard lists (Go's AdminRecentRows). */
const val ADMIN_RECENT_ROWS = 20

/** How many accounts one page of the user list holds (Go's AdminUsersPage). */
const val ADMIN_USERS_PAGE = 50

/** Tells whether an FFPF ID account is an administrator (Go's web.AdminSource). */
interface AdminSource {
  /**
   * Whether the FFPF ID account with the verified email (any case) and the sub is an administrator; if so the sub is
   * bound to the row when it has none yet, and when it was last seen is recorded (Go's Store.Admin).
   */
  fun admin(email: String, sub: String): Boolean
}

/** A row of typle_admins. */
data class AdminAccount(val email: String, val ffpfSub: String?, val createdAt: Instant, val lastSeenAt: Instant?)

/** An account that has saved its workspace, as the user list shows it: how much it holds, not what. */
data class AdminUser(
  val ownerId: String, val lists: Int, val packs: Int, val devices: Int, val shares: Int, val revision: Long,
  val updatedAt: Instant,
)

/** One page of the user list, the most recently updated first, and how many accounts there are in all. */
data class AdminUsers(val total: Int, val users: List<AdminUser>)

/** A pack shared by link, as the dashboard lists it. */
data class AdminShare(val id: String, val ownerId: String, val name: String, val updatedAt: Instant)

/** What the dashboard shows. */
data class AdminOverview(
  val workspaces: Int, val activeDay: Int, val activeWeek: Int, val activeMonth: Int, val sharedPacks: Int,
  val recentShares: List<AdminShare>, val admins: List<AdminAccount>,
)

/**
 * Go's Store.Admin. An empty email or sub is no account, and is answered false without asking the database: an empty
 * sub must never be bound to a row.
 */
internal fun admin(dataSource: DataSource, email: String, sub: String): Boolean {
  if (email.isEmpty() || sub.isEmpty()) return false
  return dataSource.connection.use { db ->
    db.prepareStatement("""UPDATE typle_admins SET ffpf_sub = ?, last_seen_at = now()
      WHERE email = lower(?) AND (ffpf_sub IS NULL OR ffpf_sub = ?) RETURNING 1""").use { q ->
      q.setString(1, sub)
      q.setString(2, email)
      q.setString(3, sub)
      q.executeQuery().use { it.next() }
    }
  }
}

/** Go's Store.Overview: the dashboard's figures, all in one read-only transaction so that they agree with each other. */
internal fun overview(dataSource: DataSource): AdminOverview = readOnly(dataSource) { db ->
  val counts = db.prepareStatement("""SELECT count(*),
      count(*) FILTER (WHERE updated_at > now() - interval '1 day'),
      count(*) FILTER (WHERE updated_at > now() - interval '7 days'),
      count(*) FILTER (WHERE updated_at > now() - interval '30 days'),
      (SELECT count(*) FROM typle_shared_packs)
    FROM typle_workspaces""").use { q ->
    q.executeQuery().use { r ->
      r.next()
      (1..5).map { r.getInt(it) }
    }
  }
  val shares = db.prepareStatement("""SELECT id::text, owner_id, COALESCE(data #>> '{pack,name}', ''), updated_at
    FROM typle_shared_packs ORDER BY updated_at DESC, id LIMIT ?""").use { q ->
    q.setInt(1, ADMIN_RECENT_ROWS)
    q.executeQuery().use { r -> rows(r) { AdminShare(r.getString(1), r.getString(2), r.getString(3), instant(r, 4)!!) } }
  }
  val admins = db.prepareStatement("SELECT email, ffpf_sub, created_at, last_seen_at FROM typle_admins ORDER BY email").use { q ->
    q.executeQuery().use { r -> rows(r) { AdminAccount(r.getString(1), r.getString(2), instant(r, 3)!!, instant(r, 4)) } }
  }
  AdminOverview(counts[0], counts[1], counts[2], counts[3], counts[4], shares, admins)
}

/**
 * Go's Store.Users: page (from 0) of the accounts that have saved their workspace, ADMIN_USERS_PAGE at a time, the most
 * recently updated first. Guests keep their data in the browser and are not here.
 */
internal fun users(dataSource: DataSource, page: Int): AdminUsers = readOnly(dataSource) { db ->
  val total = db.prepareStatement("SELECT count(*) FROM typle_workspaces").use { q ->
    q.executeQuery().use { r -> r.next().let { r.getInt(1) } }
  }
  val users = db.prepareStatement("""SELECT w.owner_id,
      CASE WHEN jsonb_typeof(w.lists) = 'array' THEN jsonb_array_length(w.lists) ELSE 0 END,
      CASE WHEN jsonb_typeof(w.packs) = 'array' THEN jsonb_array_length(w.packs) ELSE 0 END,
      CASE WHEN jsonb_typeof(w.devices) = 'array' THEN jsonb_array_length(w.devices) ELSE 0 END,
      (SELECT count(*) FROM typle_shared_packs s WHERE s.owner_id = w.owner_id),
      w.revision, w.updated_at
    FROM typle_workspaces w ORDER BY w.updated_at DESC, w.owner_id LIMIT ? OFFSET ?""").use { q ->
    q.setInt(1, ADMIN_USERS_PAGE)
    q.setInt(2, page * ADMIN_USERS_PAGE)
    q.executeQuery().use { r ->
      rows(r) { AdminUser(r.getString(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getLong(6), instant(r, 7)!!) }
    }
  }
  AdminUsers(total, users)
}

// Runs block in one read-only REPEATABLE READ transaction, as Go's pgx.BeginTxFunc with ReadOnly and RepeatableRead.
private fun <T> readOnly(dataSource: DataSource, block: (Connection) -> T): T = dataSource.connection.use { db ->
  db.autoCommit = false
  db.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
  db.isReadOnly = true
  try {
    block(db).also { db.commit() }
  } catch (e: Throwable) {
    db.rollback()
    throw e
  } finally {
    db.isReadOnly = false
    db.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
    db.autoCommit = true
  }
}

private fun <T> rows(r: ResultSet, row: () -> T): List<T> = buildList { while (r.next()) add(row()) }

private fun instant(r: ResultSet, column: Int): Instant? = r.getObject(column, OffsetDateTime::class.java)?.toInstant()
