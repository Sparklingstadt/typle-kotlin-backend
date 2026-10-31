package io.github.sparklingstadt.typle.server.web

import io.github.sparklingstadt.typle.server.auth.GitHubAccount
import io.github.sparklingstadt.typle.server.auth.OAuthClient
import io.github.sparklingstadt.typle.server.auth.UserSource
import io.github.sparklingstadt.typle.server.session.Keys
import io.github.sparklingstadt.typle.server.session.newSession
import io.github.sparklingstadt.typle.server.store.Change
import io.github.sparklingstadt.typle.server.store.Saved
import io.github.sparklingstadt.typle.server.store.Share
import io.github.sparklingstadt.typle.server.store.Store
import io.github.sparklingstadt.typle.server.workspace.encode
import io.github.sparklingstadt.typle.server.workspace.encodeDevices
import io.github.sparklingstadt.typle.server.workspace.encodeInterests
import io.github.sparklingstadt.typle.server.workspace.encodePacks
import io.github.sparklingstadt.typle.server.workspace.Workspace
import io.github.sparklingstadt.typle.server.workspace.readSaved
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

const val TEST_BASE = "http://localhost:3000"
const val TEST_OWNER = "github:42"
const val TEST_SECRET = "test-secret"

/**
 * A store that answers from memory, counting reads and writes and keeping the last change. Its links answer what the
 * test sets (shares, put, putFailure, shared, and failure for the others), recording the calls and what reached them.
 */
class FakeStore(
  var saved: Saved? = null,
  var failure: Exception? = null,
  var pingFailure: Exception? = null,
) : Store {
  var reads = 0
  var writes = 0
  var revision = -1L
  var change: Change? = null
  var shares: List<Share> = emptyList()
  var put: Share? = null
  var putFailure: Exception? = null
  var shared: String? = null
  val calls = ArrayList<String>()
  var owner = ""
  var packId = ""
  var sharedId: String? = null

  override fun ping() {
    pingFailure?.let { throw it }
  }

  override fun readRevision(owner: String): Saved? {
    reads++
    failure?.let { throw it }
    return saved
  }

  override fun writeRevision(owner: String, revision: Long, change: Change): Saved {
    writes++
    failure?.let { throw it }
    this.revision = revision
    this.change = change
    // As the store does: lists are saved with their packs (decodeFields makes them for lists sent without).
    val lists = if (change.hasLists) encode(change.lists) else saved?.lists ?: "[]"
    val packs = if (change.hasPacks) encodePacks(change.packs) else saved?.packs
    val devices = if (change.hasDevices) encodeDevices(change.devices) else saved?.devices ?: "[]"
    // Interests left out stay as saved, as the store keeps them.
    val interests = if (change.hasInterests) encodeInterests(change.interests) else saved?.interests
    return Saved(lists, devices, packs, revision + 1, interests).also { saved = it }
  }

  override fun putShare(owner: String, packId: String): Share {
    calls.add("put")
    this.owner = owner
    this.packId = packId
    putFailure?.let { throw it }
    return put!!
  }

  override fun deleteShare(owner: String, packId: String) {
    calls.add("delete")
    this.owner = owner
    this.packId = packId
    failure?.let { throw it }
  }

  override fun listShares(owner: String): List<Share> {
    calls.add("list")
    this.owner = owner
    failure?.let { throw it }
    return shares
  }

  /** When the owner signed out of every device; revokeSessions sets it. */
  var revokedAt: java.time.Instant? = null
  var revocationFailure: Exception? = null

  override fun revokeSessions(owner: String) {
    calls.add("revoke")
    revocationFailure?.let { throw it }
    revokedAt = java.time.Instant.now()
  }

  override fun sessionsRevokedAt(owner: String): java.time.Instant? {
    revocationFailure?.let { throw it }
    return revokedAt
  }

  // As the store does, on the saved row read as Go reads it, but without moving records to their own rows.
  override fun modify(owner: String, change: (Workspace) -> Pair<Workspace, Boolean>): Workspace {
    calls.add("modify")
    failure?.let { throw it }
    val current = saved?.let { readSaved(it.lists, it.devices, it.packs) } ?: Workspace(emptyList(), emptyList(), emptyList())
    val (next, changed) = change(current)
    if (changed) {
      saved = Saved(encode(next.lists), encodeDevices(next.devices), encodePacks(next.packs), (saved?.revision ?: 0) + 1, saved?.interests)
    }
    return next
  }

  override fun sharedPack(id: String): String? {
    calls.add("shared")
    sharedId = id
    failure?.let { throw it }
    return shared
  }
}

/** A GitHub OAuth App that hands out the token "gho_<code>" for any code but "bad", and records what it was given. */
class FakeOAuth : OAuthClient {
  var verifier = ""
  var redirectUri = ""

  override fun authorizeUrl(state: String, challenge: String, redirectUri: String) =
    "https://github.example/authorize?state=$state&code_challenge=$challenge&redirect_uri=" +
      java.net.URLEncoder.encode(redirectUri, Charsets.UTF_8)

  override fun exchange(code: String, verifier: String, redirectUri: String): String {
    if (code == "bad") throw IOException("GitHub refused the code: bad_verification_code")
    this.verifier = verifier
    this.redirectUri = redirectUri
    return "gho_$code"
  }
}

/** GitHub's /user: the account octo (github:7) for gho_good, and an error for any other token. */
class FakeGitHub(var account: GitHubAccount = GitHubAccount(7, "octo", "Octo Cat", "https://avatars.example/7")) : UserSource {
  override fun user(token: String): GitHubAccount = if (token == "gho_good") account else throw IOException("GitHub answered 502")
}

fun testConfig(
  store: Store? = FakeStore(),
  base: String = TEST_BASE,
  oauth: OAuthClient? = FakeOAuth(),
  gitHub: UserSource? = FakeGitHub(),
  localLogin: Boolean = false,
  now: () -> Long = System::currentTimeMillis,
) = Config(store, Keys.create(TEST_SECRET), parseBaseUrl(base)!!, now, oauth, gitHub, localLogin)

/** The app's backend on a free local port, spoken to over real HTTP, so that headers and HEAD are as a browser sees them. */
class TestServer(module: Application.() -> Unit) : AutoCloseable {
  private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1", module = module).start()
  private val port = runBlocking { server.engine.resolvedConnectors().first().port }
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  fun request(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
      .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
    for ((name, value) in headers) builder.header(name, value)
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  override fun close() = server.stop(0, 0)
}

/** The session cookie of sub, sealed with the test secret. */
fun sessionCookie(sub: String, secure: Boolean = false, nowMillis: Long = System.currentTimeMillis()): String {
  val name = if (secure) "__Host-typle-session" else "typle-session"
  return name + "=" + Keys.create(TEST_SECRET).seal(newSession(sub, "Tester", "", nowMillis))
}

fun appToken(sub: String, nowMillis: Long = System.currentTimeMillis()): String =
  Keys.create(TEST_SECRET).sealApp(newSession(sub, "Tester", "", nowMillis))

val securityHeaders = mapOf(
  "Content-Security-Policy" to "frame-ancestors 'none'; base-uri 'self'; object-src 'none'",
  "X-Frame-Options" to "DENY",
  "X-Content-Type-Options" to "nosniff",
  "Referrer-Policy" to "strict-origin-when-cross-origin",
)

fun expectSecurityHeaders(name: String, response: HttpResponse<String>) {
  for ((header, want) in securityHeaders) assertEquals(listOf(want), response.headers().allValues(header), "$name: $header")
}

fun expectJsonHeaders(name: String, response: HttpResponse<String>) {
  val headers = response.headers()
  assertEquals("application/json; charset=utf-8", headers.firstValue("Content-Type").orElse(null), name)
  assertEquals("private, no-store", headers.firstValue("Cache-Control").orElse(null), name)
  assertEquals("Cookie", headers.firstValue("Vary").orElse(null), name)
}

fun expectError(response: HttpResponse<String>, status: Int, key: String, name: String = "") {
  assertEquals(status to "{\"error\":\"$key\"}\n", response.statusCode() to response.body(), name)
  expectJsonHeaders(name, response)
}
