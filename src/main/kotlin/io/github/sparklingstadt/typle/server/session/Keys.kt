package io.github.sparklingstadt.typle.server.session

import io.github.sparklingstadt.typle.server.json.GoJson
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import io.github.sparklingstadt.typle.server.json.JsonBool
import io.github.sparklingstadt.typle.server.json.JsonNull
import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonString
import io.github.sparklingstadt.typle.server.json.JsonSyntaxException
import io.github.sparklingstadt.typle.server.json.JsonValue

// The only subject accepted: a GitHub account (the numeric user id; ASCII digits only, as Go's \d) or an FFPF ID
// account (its lowercase alphanumeric sub). The prefix keeps the two id spaces from ever colliding.
private val accountSub = Regex("github:[0-9]+|ffpf:[a-z0-9]{1,64}")

// An FFPF ID sub as it stands, without the prefix: what the administrator's session names.
private val ffpfSub = Regex("[a-z0-9]{1,64}")

private val encoder = Base64.getUrlEncoder().withoutPadding()

/**
 * The signing key of each kind of value, derived from AUTH_SECRET with HKDF-SHA256 (no salt, 32 bytes) and a different
 * info for each kind, so one kind never opens as another. Each value is base64url(JSON) + "." + base64url(HMAC-SHA256).
 */
class Keys private constructor(
  private val session: ByteArray,
  private val oauth: ByteArray,
  private val app: ByteArray,
  private val code: ByteArray,
  private val admin: ByteArray,
) {
  companion object {
    /** Derives the keys from AUTH_SECRET. An empty secret would make every cookie forgeable, so it is refused. */
    fun create(secret: String): Keys {
      require(secret.isNotEmpty()) { "AUTH_SECRET is empty" }
      val bytes = secret.toByteArray()
      return Keys(
        hkdf(bytes, "typle session v1"), hkdf(bytes, "typle oauth v1"),
        hkdf(bytes, "typle app session v1"), hkdf(bytes, "typle app code v1"),
        // v2 since the administrator signs in with FFPF ID instead of GitHub: the new key keeps an administrator's
        // cookie of a GitHub sign-in from ever opening, whatever its JSON says.
        hkdf(bytes, "typle admin v2"),
      )
    }
  }

  fun seal(s: Session): String = seal(session, sessionJson(s))

  /** The session in value if it is ours, unexpired and for a GitHub or FFPF ID account. */
  fun open(value: String, nowMillis: Long): Session? = openSession(session, value, nowMillis)

  /** Seals s as an app's token, which it sends as a bearer token instead of the cookie. */
  fun sealApp(s: Session): String = seal(app, sessionJson(s))

  fun openApp(value: String, nowMillis: Long): Session? = openSession(app, value, nowMillis)

  fun sealOAuth(o: OAuthState): String {
    // The challenge and admin are left out of the web app's login, whose cookie thus stays as it was, and the provider
    // and nonce likewise for GitHub, after them so that the bytes before them do not move.
    val challenge = if (o.challenge.isEmpty()) "" else ",\"challenge\":" + str(o.challenge)
    val admin = if (o.admin) ",\"admin\":true" else ""
    val provider = if (o.provider.isEmpty()) "" else ",\"provider\":" + str(o.provider)
    val nonce = if (o.nonce.isEmpty()) "" else ",\"nonce\":" + str(o.nonce)
    return seal(oauth, "{\"state\":${str(o.state)},\"verifier\":${str(o.verifier)},\"returnTo\":${str(o.returnTo)}," +
      "\"exp\":${o.expires}$challenge$admin$provider$nonce}")
  }

  /** The login state in value if it is ours and unexpired. */
  fun openOAuth(value: String, nowMillis: Long): OAuthState? {
    val fields = open(oauth, value) ?: return null
    val o = OAuthState(
      text(fields, "state") ?: return null, text(fields, "verifier") ?: return null,
      text(fields, "returnTo") ?: return null, text(fields, "challenge") ?: return null,
      integer(fields, "exp") ?: return null, flag(fields, "admin") ?: return null,
      text(fields, "provider") ?: return null, text(fields, "nonce") ?: return null,
    )
    return if (nowMillis < o.expires * 1000) o else null
  }

  fun sealAdmin(a: Admin): String =
    seal(admin, "{\"email\":${str(a.email)},\"sub\":${str(a.sub)},\"iat\":${a.issuedAt},\"exp\":${a.expires}}")

  /**
   * The administrator's session in value if it is ours, unexpired and names an FFPF ID account: an email and a sub.
   * Whether that account is still an administrator is for the caller to check.
   */
  fun openAdmin(value: String, nowMillis: Long): Admin? {
    val fields = open(admin, value) ?: return null
    val a = Admin(
      text(fields, "email") ?: return null, text(fields, "sub") ?: return null,
      integer(fields, "iat") ?: return null, integer(fields, "exp") ?: return null,
    )
    return if (nowMillis < a.expires * 1000 && a.email.isNotEmpty() && ffpfSub.matches(a.sub)) a else null
  }

  fun sealCode(c: AppCode): String = seal(code,
    "{\"sub\":${str(c.sub)},\"name\":${str(c.name)},\"image\":${str(c.image)},\"challenge\":${str(c.challenge)},\"exp\":${c.expires}}")

  /**
   * The code in value if it is ours, unexpired, for a GitHub or FFPF ID account and with a challenge to check the
   * verifier by.
   */
  fun openCode(value: String, nowMillis: Long): AppCode? {
    val fields = open(code, value) ?: return null
    val c = AppCode(
      text(fields, "sub") ?: return null, text(fields, "name") ?: return null, text(fields, "image") ?: return null,
      text(fields, "challenge") ?: return null, integer(fields, "exp") ?: return null,
    )
    return if (nowMillis < c.expires * 1000 && accountSub.matches(c.sub) && c.challenge.isNotEmpty()) c else null
  }

  internal fun signSession(payload: String): ByteArray = sign(session, payload)

  internal fun signOAuth(payload: String): ByteArray = sign(oauth, payload)

  private fun openSession(key: ByteArray, value: String, nowMillis: Long): Session? {
    val fields = open(key, value) ?: return null
    val s = Session(
      text(fields, "sub") ?: return null, text(fields, "name") ?: return null, text(fields, "image") ?: return null,
      integer(fields, "iat") ?: return null, integer(fields, "exp") ?: return null,
    )
    return if (nowMillis < s.expires * 1000 && accountSub.matches(s.sub)) s else null
  }

  // The field order is the order of Go's struct, and the bytes are signed.
  private fun sessionJson(s: Session) =
    "{\"sub\":${str(s.sub)},\"name\":${str(s.name)},\"image\":${str(s.image)},\"iat\":${s.issuedAt},\"exp\":${s.expires}}"

  private fun str(value: String) = GoJson.string(value, escapeHtml = false)

  private fun seal(key: ByteArray, json: String): String {
    val payload = encoder.encodeToString(json.toByteArray())
    return payload + "." + encoder.encodeToString(sign(key, payload))
  }

  // The signature is checked over the payload as received, and only then is the payload read as JSON.
  private fun open(key: ByteArray, value: String): JsonObject? {
    val dot = value.indexOf('.')
    if (dot < 0) return null
    val payload = value.substring(0, dot)
    val got = strictDecode(value.substring(dot + 1)) ?: return null
    if (!MessageDigest.isEqual(got, sign(key, payload))) return null
    val data = strictDecode(payload) ?: return null
    return try {
      JsonValue.parse(String(data, Charsets.UTF_8)) as? JsonObject
    } catch (_: JsonSyntaxException) {
      null
    }
  }
}

// Strict, as Go's RawURLEncoding.Strict: no padding, and no set bits past the data, so one value has one spelling.
private fun strictDecode(value: String): ByteArray? {
  val bytes = try {
    Base64.getUrlDecoder().decode(value)
  } catch (_: IllegalArgumentException) {
    return null
  }
  return if (encoder.encodeToString(bytes) == value) bytes else null
}

private fun sign(key: ByteArray, payload: String): ByteArray {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(key, "HmacSHA256"))
  return mac.doFinal(payload.toByteArray())
}

// HKDF-SHA256 (RFC 5869) with no salt, which is a salt of 32 zero bytes, for one 32-byte block.
private fun hkdf(secret: ByteArray, info: String): ByteArray {
  val extract = Mac.getInstance("HmacSHA256")
  extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
  val prk = extract.doFinal(secret)
  val expand = Mac.getInstance("HmacSHA256")
  expand.init(SecretKeySpec(prk, "HmacSHA256"))
  expand.update(info.toByteArray())
  expand.update(1)
  return expand.doFinal()
}

// Go's json.Unmarshal into the wire structs: a string field takes a string, and null or a missing field leaves it "";
// an integer field takes an integer, and null or missing leaves it 0; a boolean field takes a boolean, and null or
// missing leaves it false. Anything else does not open.
private fun text(fields: JsonObject, name: String): String? = when (val v = fields.members[name]) {
  null, JsonNull -> ""
  is JsonString -> v.value
  else -> null
}

private fun integer(fields: JsonObject, name: String): Long? = when (val v = fields.members[name]) {
  null, JsonNull -> 0
  is JsonNumber -> v.literal.toLongOrNull()
  else -> null
}

private fun flag(fields: JsonObject, name: String): Boolean? = when (val v = fields.members[name]) {
  null, JsonNull -> false
  is JsonBool -> v.value
  else -> null
}
