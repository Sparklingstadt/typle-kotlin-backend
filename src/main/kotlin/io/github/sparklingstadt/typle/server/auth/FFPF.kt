package io.github.sparklingstadt.typle.server.auth

import io.github.sparklingstadt.typle.server.json.JsonArray
import io.github.sparklingstadt.typle.server.json.JsonBool
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.InvalidKeySpecException
import java.time.Duration
import java.util.Base64

/** Something FFPF ID's login came to: FFPFRejectedException or FFPFUnavailableException (Go's ErrFFPF*). */
sealed class FFPFException(message: String) : IOException(message)

/**
 * FFPF ID refusing the login: the token endpoint answered 4xx, or the ID token it gave is not one this app accepts.
 * Trying again with the same code will not help.
 */
class FFPFRejectedException(reason: String) : FFPFException("FFPF ID refused the login: $reason")

/** FFPF ID not answering, answering 5xx, or its JWKS not being had: nothing is known about the login. */
class FFPFUnavailableException(reason: String) : FFPFException("FFPF ID could not be reached: $reason")

/** An FFPF ID account, as its verified ID token describes it. */
data class FFPFUser(val sub: String, val email: String, val name: String) {
  /** The id the app's accounts go by, ffpf:<sub>. */
  val account: String get() = "ffpf:$sub"
}

/** The "Sign in with FFPF ID" client (Go's web.FFPFLogin). */
interface FFPFLogin {
  /**
   * Where to send the browser to sign in. challenge is the S256 code challenge of the verifier later given to exchange,
   * and nonce is the one exchange will require in the ID token.
   */
  fun authorizeUrl(state: String, challenge: String, nonce: String, redirectUri: String): String

  /**
   * Trades the code the portal sent back to the callback for the user's verified ID token, and returns who it names.
   * Throws FFPFRejectedException or FFPFUnavailableException, whose messages never include the client secret, the
   * code, the verifier or a token.
   */
  fun exchange(code: String, verifier: String, redirectUri: String, nonce: String): FFPFUser
}

// The JWKS is trusted this long, so most logins need one call to the portal, not two. A key the portal drops is
// therefore still accepted for up to this long.
private const val JWKS_CACHE_MILLIS = 10 * 60_000L

// An ID token is still accepted this long after its exp, for clocks that do not quite agree.
private const val LEEWAY_MILLIS = 60_000L

private val TIMEOUT: Duration = Duration.ofSeconds(10)

// One base64url segment of a compact JWT, without padding.
private val jwtSegment = Regex("[A-Za-z0-9_-]+")

// The form of an FFPF ID subject, which becomes part of the account id ffpf:<sub>.
private val ffpfSub = Regex("[a-z0-9]{1,64}")

private val oauthErrorCode = Regex("[a-z_]{1,64}")

private val base64Url = Base64.getUrlEncoder().withoutPadding()

// P-256 (secp256r1): y² = x³ - 3x + b over the field of P.
private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
private val p256: ECParameterSpec = AlgorithmParameters.getInstance("EC")
  .apply { init(ECGenParameterSpec("secp256r1")) }
  .getParameterSpec(ECParameterSpec::class.java)

/**
 * The OpenID Connect provider run by ffpf-portal, with the authorization code flow and PKCE, as Go's auth.FFPF. Its
 * endpoints are built from issuer (no discovery), the client secret is sent only to the token endpoint with HTTP Basic
 * auth (client_secret_basic), and the ID token that comes back is verified here: an ES256 signature by a key of the
 * portal's JWKS, and its iss, aud, exp, nonce, email_verified and sub. The access token is not used.
 */
class FFPF(
  issuer: String,
  val clientId: String,
  val clientSecret: String,
  /** The clock, in milliseconds. */
  private val now: () -> Long = System::currentTimeMillis,
) : FFPFLogin {
  /** The portal's issuer, without a trailing slash; ID tokens must name it exactly. */
  val issuer: String = issuer.removeSuffix("/")

  // The JWKS, by kid, and when it was fetched (null: never). Only P-256 EC keys are kept.
  private val lock = Any()
  private var keys: Map<String, ECPublicKey> = emptyMap()
  private var fetched: Long? = null

  override fun authorizeUrl(state: String, challenge: String, nonce: String, redirectUri: String): String =
    "$issuer/oauth/authorize?" + goForm(mapOf(
      "client_id" to clientId, "redirect_uri" to redirectUri, "response_type" to "code",
      "scope" to "openid email profile", "state" to state, "nonce" to nonce, "code_challenge" to challenge,
      "code_challenge_method" to "S256",
    ))

  override fun exchange(code: String, verifier: String, redirectUri: String, nonce: String): FFPFUser =
    verify(token(code, verifier, redirectUri), nonce)

  // The POST to the token endpoint, and the ID token of its answer.
  private fun token(code: String, verifier: String, redirectUri: String): String {
    val form = goForm(mapOf(
      "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier, "redirect_uri" to redirectUri,
    ))
    // RFC 6749 §2.3.1 has the client id and secret form-encoded before they are put in the Basic credentials.
    val credentials = goQueryEscape(clientId) + ":" + goQueryEscape(clientSecret)
    val request = HttpRequest.newBuilder(URI("$issuer/oauth/token")).timeout(TIMEOUT)
      .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(form))
      .build()
    val response = sendToPortal(request, "the token request")
    val status = response.statusCode()
    when (status) {
      in 200..299 -> {}
      in 400..499 -> {
        // The portal refused the code or the client (invalid_grant, invalid_client …). Only the RFC 6749 error code
        // is kept, and only if it looks like one, as the rest of the answer is not ours to log.
        val error = (readObject(response)?.members?.get("error") as? JsonString)?.value
        if (error != null && oauthErrorCode.matches(error)) {
          throw FFPFRejectedException("the token endpoint answered $status $error")
        }
        throw FFPFRejectedException("the token endpoint answered $status")
      }
      else -> {
        response.body().close()
        throw FFPFUnavailableException("the token endpoint answered $status")
      }
    }
    val answer = readObject(response) ?: throw FFPFRejectedException("the token answer is not JSON with a string id_token")
    return when (val idToken = answer.members["id_token"]) {
      null, JsonNull -> throw FFPFRejectedException("the token answer has no id_token")
      is JsonString -> idToken.value.ifEmpty { throw FFPFRejectedException("the token answer has no id_token") }
      else -> throw FFPFRejectedException("the token answer is not JSON with a string id_token")
    }
  }

  // Checks the ID token's signature and claims, and returns who it names.
  private fun verify(idToken: String, nonce: String): FFPFUser {
    fun reject(reason: String): Nothing = throw FFPFRejectedException("the ID token $reason")
    val segments = idToken.split('.')
    if (segments.size != 3) reject("is not a compact JWT")
    val decoded = segments.map { if (jwtSegment.matches(it)) strictDecode(it) ?: reject("is not a compact JWT") else reject("is not a compact JWT") }

    val header = parseObject(decoded[0]) ?: reject("header is not JSON")
    val alg = stringField(header, "alg") ?: reject("header is not JSON")
    val typ = when (val value = header.members["typ"]) {
      null, JsonNull -> null
      is JsonString -> value.value
      else -> reject("header is not JSON")
    }
    val kid = stringField(header, "kid") ?: reject("header is not JSON")
    // Only ES256 is accepted, whatever the header claims, so neither "none" nor an HMAC keyed with the public key can
    // get in.
    if (alg != "ES256") reject("is not ES256")
    if (typ != null && typ != "JWT") reject("is not typed JWT")
    if (kid.isEmpty()) reject("has no kid")
    val key = key(kid)
    // An ES256 signature is r‖s, 32 bytes each (RFC 7518 §3.4), not ASN.1.
    if (decoded[2].size != 64) reject("signature is not 64 bytes")
    val verified = try {
      Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initVerify(key)
        update((segments[0] + "." + segments[1]).toByteArray())
        verify(decoded[2])
      }
    } catch (_: SignatureException) {
      false
    }
    if (!verified) reject("signature does not verify")

    val claims = parseObject(decoded[1]) ?: reject("claims are not JSON of the expected types")
    fun text(name: String) = stringField(claims, name) ?: reject("claims are not JSON of the expected types")
    val iss = text("iss")
    val exp = when (val value = claims.members["exp"]) {
      null, JsonNull -> null
      is JsonNumber -> value.literal.toDouble()
      else -> reject("claims are not JSON of the expected types")
    }
    val claimedNonce = text("nonce")
    val sub = text("sub")
    val email = text("email")
    val name = text("name")
    if (iss != issuer) reject("is from another issuer")
    if (!audienceHas(claims.members["aud"], clientId)) reject("is for another client")
    // exp is seconds since 1970; one beyond 2^53 is not a time a portal would mean, and is kept out of the conversion.
    if (exp == null || exp < 0 || exp > (1L shl 53).toDouble()) reject("has no usable exp")
    if (now() > exp.toLong() * 1000 + LEEWAY_MILLIS) reject("has expired")
    if (nonce.isEmpty() || claimedNonce != nonce) reject("has another nonce")
    if (claims.members["email_verified"] != JsonBool(true)) reject("email is not verified")
    if (email.isEmpty()) reject("has no email")
    if (!ffpfSub.matches(sub)) reject("sub is not of the expected form")
    // The portal's name may be empty; the part of the email before "@" is shown instead.
    val shown = goTrimSpace(name).ifEmpty { email.substringBefore('@') }
    return FFPFUser(sub, email, shown)
  }

  /**
   * The portal's public key of kid. The JWKS is fetched when the cache is older than JWKS_CACHE_MILLIS, or when it does
   * not have kid (the portal may have a new key), but at most once per call, so one ID token can never make more than
   * one request to the portal. A kid the fresh JWKS does not have either is refused.
   */
  private fun key(kid: String): ECPublicKey {
    synchronized(lock) {
      val cached = keys[kid]
      val at = fetched
      if (cached != null && at != null && now() - at < JWKS_CACHE_MILLIS) return cached
    }
    val fresh = fetchJwks()
    synchronized(lock) {
      keys = fresh
      fetched = now()
    }
    return fresh[kid] ?: throw FFPFRejectedException("the ID token's kid is not in the JWKS")
  }

  // GET <issuer>/oauth/jwks. Keys that are not P-256 EC signing keys with a kid are left out; anything that goes wrong
  // with the request is unavailable.
  private fun fetchJwks(): Map<String, ECPublicKey> {
    val request = HttpRequest.newBuilder(URI("$issuer/oauth/jwks")).timeout(TIMEOUT).header("Accept", "application/json").GET().build()
    val response = sendToPortal(request, "the JWKS request")
    if (response.statusCode() != 200) {
      response.body().close()
      throw FFPFUnavailableException("the JWKS endpoint answered ${response.statusCode()}")
    }
    fun broken(): Nothing = throw FFPFUnavailableException("the JWKS is not JSON with keys")
    val list = readObject(response)?.members?.get("keys") as? JsonArray ?: broken()
    val keys = LinkedHashMap<String, ECPublicKey>()
    for (item in list.items) {
      // As Go's decoding into the struct: null is a key with nothing set, and any other value that is not an object
      // with string fields fails the whole JWKS.
      val k = when (item) {
        JsonNull -> continue
        is JsonObject -> item
        else -> broken()
      }
      val fields = listOf("kty", "crv", "use", "kid", "x", "y").associateWith { stringField(k, it) ?: broken() }
      val kid = fields.getValue("kid")
      if (fields["kty"] != "EC" || fields["crv"] != "P-256" || kid.isEmpty() || (fields["use"] != "" && fields["use"] != "sig")) continue
      if (kid in keys) continue
      p256Key(fields.getValue("x"), fields.getValue("y"))?.let { keys[kid] = it }
    }
    return keys
  }
}

// Only the cause's kind is kept, never the request's body or headers.
private fun sendToPortal(request: HttpRequest, what: String): HttpResponse<java.io.InputStream> = try {
  client.send(request, HttpResponse.BodyHandlers.ofInputStream())
} catch (e: IOException) {
  throw FFPFUnavailableException("$what failed: ${e.javaClass.simpleName}")
} catch (e: InterruptedException) {
  Thread.currentThread().interrupt()
  throw FFPFUnavailableException("$what was interrupted")
}

private fun parseObject(data: ByteArray): JsonObject? = try {
  JsonValue.parse(String(data, Charsets.UTF_8)) as? JsonObject
} catch (_: JsonSyntaxException) {
  null
}

// Whether aud, a string or an array of strings (RFC 7519 §4.1.3), names clientId.
private fun audienceHas(aud: JsonValue?, clientId: String): Boolean = when (aud) {
  is JsonString -> aud.value == clientId
  is JsonArray -> aud.items.all { it is JsonString } && aud.items.any { (it as JsonString).value == clientId }
  else -> false
}

// The public key of a JWK's x and y, if they are 32 bytes each and the point is on P-256.
private fun p256Key(x64: String, y64: String): ECPublicKey? {
  val xBytes = strictDecode(x64) ?: return null
  val yBytes = strictDecode(y64) ?: return null
  if (xBytes.size != 32 || yBytes.size != 32) return null
  val x = BigInteger(1, xBytes)
  val y = BigInteger(1, yBytes)
  if (x >= P || y >= P) return null
  val left = y.multiply(y).mod(P)
  val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
  if (left != right) return null
  return try {
    KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
  } catch (_: InvalidKeySpecException) {
    null
  }
}

// Strict, as Go's RawURLEncoding.Strict: no padding, and no set bits past the data, so a segment has one spelling.
private fun strictDecode(value: String): ByteArray? {
  val bytes = try {
    Base64.getUrlDecoder().decode(value)
  } catch (_: IllegalArgumentException) {
    return null
  }
  return if (base64Url.encodeToString(bytes) == value) bytes else null
}

/** Go's url.QueryEscape: only letters, digits and -_.~ stay as they are, and a space is +. */
fun goQueryEscape(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("*", "%2A").replace("%7E", "~")

/** Go's url.Values.Encode: sorted by key. */
fun goForm(values: Map<String, String>): String =
  values.toSortedMap().entries.joinToString("&") { goQueryEscape(it.key) + "=" + goQueryEscape(it.value) }
