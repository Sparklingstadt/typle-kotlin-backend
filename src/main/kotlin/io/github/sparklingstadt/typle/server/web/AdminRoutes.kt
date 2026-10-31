package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.FFPFUser
import io.github.sparklingstadt.typle.server.json.GoJson
import io.github.sparklingstadt.typle.server.session.Admin
import io.github.sparklingstadt.typle.server.session.SessionTimes
import io.github.sparklingstadt.typle.server.session.adminCookieName
import io.github.sparklingstadt.typle.server.session.newAdmin
import io.github.sparklingstadt.typle.server.store.ADMIN_USERS_PAGE
import io.github.sparklingstadt.typle.server.store.AdminAccount
import io.github.sparklingstadt.typle.server.store.AdminOverview
import io.github.sparklingstadt.typle.server.store.AdminShare
import io.github.sparklingstadt.typle.server.store.AdminUser
import io.github.sparklingstadt.typle.server.store.Store
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The administrator's dashboard (/admin) has a login of its own, as Go's web/admin.go. It goes through FFPF ID only
// (/api/auth/signin?admin=1, then /api/auth/callback/ffpf), and ends with the administrator's cookie instead of the
// session cookie, for an account that typle_admins lets in: one registered there by its email, which binds the
// account's sub at its first sign-in. Each /api/admin request checks the account against typle_admins again, so an
// administrator whose row is removed is let in no longer.

internal const val ADMIN_PAGE = "/admin"

/** Where a login of an account that is not an administrator ends: the page says so. */
internal const val ADMIN_REFUSED = "$ADMIN_PAGE?error=adminForbidden"

// Where a login to the dashboard ends when typle_admins could not be asked.
private const val ADMIN_UNAVAILABLE = "$ADMIN_PAGE?error=adminUnavailable"

// Keeps the OFFSET of a made-up page number from making the database walk far.
private const val MAX_USERS_PAGE = 10000

// The administrator's cookie is SameSite=Strict: the dashboard is only reached from the app's own pages, and its cookie
// is never sent with a request another site starts.
private fun ApplicationCall.adminCookie(config: Config, value: String, maxAge: Int) =
  setCookie(adminCookieName(config.secure), value, "/", maxAge, config.secure, sameSite = "Strict")

/**
 * Ends a login to the dashboard through FFPF ID: the administrator's cookie if typle_admins lets the account in, and
 * ADMIN_REFUSED otherwise, or ADMIN_UNAVAILABLE when the database does not answer. It never sets the session cookie,
 * so a login to the dashboard does not sign in to the app.
 */
internal suspend fun ApplicationCall.finishAdmin(config: Config, user: FFPFUser) {
  val email = user.email.lowercase()
  val allowed = try {
    withContext(Dispatchers.IO) { isAdmin(config, email, user.sub) }
  } catch (e: Exception) {
    report(e, "admin.check")
    return goTo(ADMIN_UNAVAILABLE)
  }
  if (!allowed) return goTo(ADMIN_REFUSED)
  adminCookie(config, config.keys.sealAdmin(newAdmin(email, user.sub, config.now())), SessionTimes.ADMIN_MAX_AGE.toInt())
  goTo(ADMIN_PAGE)
}

// Asks typle_admins, through Config.admins or else Config.store, whether the FFPF ID account is an administrator.
private fun isAdmin(config: Config, email: String, sub: String): Boolean {
  val source = config.admins ?: config.store ?: throw IllegalStateException("no database to check the administrator against")
  return source.admin(email, sub)
}

/**
 * Lets the administrator through: adminRequired without their cookie, and adminForbidden, with the cookie removed,
 * when typle_admins no longer lets the account in. The caller answers only when it returns the administrator.
 */
private suspend fun ApplicationCall.allowAdmin(config: Config, store: Store): Admin? {
  val admin = cookie(this, adminCookieName(config.secure))?.let { config.keys.openAdmin(it, config.now()) }
  if (admin == null) {
    refuse(HttpStatusCode.Unauthorized, "adminRequired")
    return null
  }
  val allowed = try {
    withContext(Dispatchers.IO) { store.admin(admin.email, admin.sub) }
  } catch (e: Exception) {
    report(e, "admin.check")
    refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
    return null
  }
  if (!allowed) {
    adminCookie(config, "", -1)
    refuse(HttpStatusCode.Forbidden, "adminForbidden")
    return null
  }
  return admin
}

// The store /api/admin needs; without one the answer is databaseUnavailable.
private suspend fun ApplicationCall.adminStore(config: Config): Store? {
  config.store?.let { return it }
  refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
  return null
}

/** Answers the dashboard's figures, and who is signed in to it, to the administrator. */
internal suspend fun ApplicationCall.adminDashboard(config: Config) {
  if (!methods(HttpMethod.Get, HttpMethod.Head)) return
  val store = adminStore(config) ?: return
  val admin = allowAdmin(config, store) ?: return
  val overview = try {
    withContext(Dispatchers.IO) { store.overview() }
  } catch (e: Exception) {
    report(e, "admin.overview")
    return refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
  }
  reply(HttpStatusCode.OK, "{\"admin\":{\"email\":${text(admin.email)},\"sub\":${text(admin.sub)}},\"overview\":${overviewJson(overview)}}")
}

/**
 * Answers one page of the user list (?page=, from 0) to the administrator. A page that is not a number from 0 to
 * MAX_USERS_PAGE is invalidPage.
 */
internal suspend fun ApplicationCall.adminUsers(config: Config) {
  if (!methods(HttpMethod.Get, HttpMethod.Head)) return
  val store = adminStore(config) ?: return
  allowAdmin(config, store) ?: return
  val value = request.queryParameters["page"].orEmpty()
  val page = if (value.isEmpty()) 0 else goAtoi(value)
  if (page == null || page < 0 || page > MAX_USERS_PAGE) return refuse(HttpStatusCode.BadRequest, "invalidPage")
  val users = try {
    withContext(Dispatchers.IO) { store.users(page) }
  } catch (e: Exception) {
    report(e, "admin.users")
    return refuse(HttpStatusCode.ServiceUnavailable, "databaseUnavailable")
  }
  reply(HttpStatusCode.OK, "{\"page\":$page,\"pageSize\":$ADMIN_USERS_PAGE,\"total\":${users.total},\"users\":" +
    users.users.joinToString(",", "[", "]", transform = ::userJson) + "}")
}

/** Forgets the administrator's cookie. Like signout, it needs the app's own Origin. */
internal suspend fun ApplicationCall.adminSignout(config: Config) {
  if (!methods(HttpMethod.Post)) return
  if (!config.sameOrigin(request.headers["Origin"])) return refuse(HttpStatusCode.Forbidden, "forbiddenRequest")
  adminCookie(config, "", -1)
  noContent()
}

private val decimal = Regex("[+-]?[0-9]+")

// Go's strconv.Atoi: an optional sign and decimal digits. A number past an Int is answered as one, and refused all the
// same as a page.
private fun goAtoi(value: String): Int? {
  if (!decimal.matches(value)) return null
  val number = value.toBigInteger()
  return if (number.bitLength() < 32) number.toInt() else if (number.signum() < 0) -1 else Int.MAX_VALUE
}

// The answers are written as Go's encoder writes these structs, field by field in their order.
private fun text(value: String) = GoJson.string(value, escapeHtml = true)

private fun overviewJson(o: AdminOverview) =
  "{\"workspaces\":${o.workspaces},\"activeDay\":${o.activeDay},\"activeWeek\":${o.activeWeek}," +
    "\"activeMonth\":${o.activeMonth},\"sharedPacks\":${o.sharedPacks}," +
    "\"recentShares\":" + o.recentShares.joinToString(",", "[", "]", transform = ::shareJson) +
    ",\"admins\":" + o.admins.joinToString(",", "[", "]", transform = ::accountJson) + "}"

private fun shareJson(s: AdminShare) =
  "{\"id\":${text(s.id)},\"ownerId\":${text(s.ownerId)},\"name\":${text(s.name)},\"updatedAt\":${time(s.updatedAt)}}"

private fun accountJson(a: AdminAccount) =
  "{\"email\":${text(a.email)},\"ffpfSub\":${a.ffpfSub?.let(::text) ?: "null"},\"createdAt\":${time(a.createdAt)}," +
    "\"lastSeenAt\":${a.lastSeenAt?.let(::time) ?: "null"}}"

private fun userJson(u: AdminUser) =
  "{\"ownerId\":${text(u.ownerId)},\"lists\":${u.lists},\"packs\":${u.packs},\"devices\":${u.devices}," +
    "\"shares\":${u.shares},\"revision\":${u.revision},\"updatedAt\":${time(u.updatedAt)}}"

private val seconds = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

/**
 * A time as Go's time.Time writes itself (RFC 3339 with the fraction's trailing zeros dropped), in UTC, as on Vercel,
 * where the Go functions' local time zone is UTC.
 */
private fun time(at: Instant): String {
  val fraction = if (at.nano == 0) "" else "." + at.nano.toString().padStart(9, '0').trimEnd('0')
  return "\"" + seconds.format(at) + fraction + "Z\""
}
