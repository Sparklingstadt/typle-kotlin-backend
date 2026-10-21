# Kotlin のバックエンド（手元で動く並行実装） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Go の `typle-web`（`/api/health`・`/api/workspace`・`/api/auth/*`）と同じ動きのバックエンドを `server-kotlin/` に Kotlin（Ktor）で作り、`TYPLE_BACKEND=kotlin` で `npm run dev` と E2E から使えるようにする。

**Architecture:** 独立した Gradle ビルド `server-kotlin/`。パッケージは Go の `server/internal/*` と 1 対 1（`json`・`session`・`workspace`・`store`・`auth`・`web`）で、Go のソースを「仕様の細部」として移す。HTTP は Ktor（CIO）の 1 つのインターセプターで Go の `ServeMux` と同じ振り分けをし、ヘッダー・Cookie・JSON の書き方は Go の出力にそろえる。

**Tech Stack:** Kotlin 2.1.21（JVM 21）、Ktor 3.1.3（`ktor-server-cio`、テストは `ktor-server-test-host`）、kotlinx-serialization-json 1.8.1（読み取りだけ）、PostgreSQL JDBC 42.7.7、HikariCP 6.3.0、slf4j-simple 2.0.17、JUnit 5（`kotlin("test")`・junit-jupiter 5.12.2）、Gradle 8.14.3（Wrapper は `android/` と同じ版）。

**Spec:** `docs/superpowers/specs/2026-10-21-kotlin-backend-design.md`

## Global Constraints

- 本番（Vercel）・`ci.yml`・Go のコード・画面・DB のスキーマは変えない。`.vercelignore` に `server-kotlin` を足す。
- 作る範囲は spec の「作る範囲」だけ。`/api/v1`・Sentry・Vercel の入口は作らない。
- 判定・ステータス・エラーのキー・ヘッダーは Go と同じ。迷ったら Go のソース（`server/internal/web`・`session`・`workspace`・`store`・`auth`）とそのテストが正しい。
- パッケージ名は `io.github.sparklingstadt.typle.server.<部分>`。ソースは `server-kotlin/src/main/kotlin/io/github/sparklingstadt/typle/server/`、テストは `server-kotlin/src/test/kotlin/…`。
- コメントは英語、Go のソースと同じ密度。ドキュメントとコミットメッセージは日本語。
- コミットの末尾に `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` と `Claude-Session: https://claude.ai/code/session_01JPmCe7o9BHrQmcnBF4Z3vU`。
- 版は `0.58.0`。

## Review Focus

1. **PostgreSQL が返す jsonb の文字列（`{"id": "a", …}` のように空白が入る）**：GET の答えは Go と同じく詰めて（compact）HTML の文字を `\u003c` などにエスケープして返す → Task 1 の `compact` のテストと Task 6 の GET のテストで固定。
2. **整数の項目に `1.0`・`1e2`・範囲外の数が来る（`mistakes`・`wordCount`）**：Go の `json.Unmarshal` は `*int` にそれらを入れられず保存を断る → Task 2 のテストで固定。`revision` は逆に `1.0`・`1e2` を受け付ける（Task 5）。
3. **Cookie の値の読み方**：Ktor の `request.cookies[name]` は既定で URL デコードする。生の値（`rawCookies`）で読み、同名の Cookie が 2 つあれば Go の `r.Cookie` と同じく最初のものを使う → Task 6 のテストで固定。
4. **DB が止まっている・あとから起動する**：起動直後から `/api/auth/*` は動き、`/api/health`・`/api/workspace`（と知らないパス）は `503 databaseUnavailable`、DB が上がれば次のリクエストからつながる。失敗後 1 秒は開き直さない → Task 8 のテストで固定。
5. **同じ名前の項目が JSON に 2 回ある・知らない項目**：最後のものが勝ち（Go の `map` と JavaScript と同じ）、知らない項目は保存で残る → Task 2・Task 5 のテストで固定。

---

## File Structure

```
server-kotlin/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew, gradlew.bat, gradle/wrapper/*
  README.md
  src/main/kotlin/io/github/sparklingstadt/typle/server/
    Main.kt                    起動・引数・停止（Task 8）
    Backend.kt                 DB を遅れて開く振り分け（Go の server/vercel）（Task 8）
    json/GoJson.kt             Go の encoding/json と同じ書き出し（文字列・数・compact）（Task 1）
    session/Keys.kt            HKDF と 4 種の seal/open（Task 1）
    session/Session.kt         Session・OAuth・AppCode・定数（Task 1）
    workspace/Workspace.kt     Word・Device・Record・List・decode（Task 2）
    workspace/Encode.kt        encode・encodeDevices・checkLimits・WorkspaceError（Task 2）
    workspace/Text.kt          trimJS・utf16Length・parseTime（Task 2）
    store/ConnString.kt        Go の ConnString と JDBC への変換（Task 3）
    store/Store.kt             Store インターフェースと PgStore（Task 3）
    auth/OAuth.kt              GitHub の authorize URL とコード交換（Task 4）
    auth/GitHubUser.kt         GET /user（Task 4）
    web/Config.kt              Config・fromEnv・localLoginAllowed・parseBaseUrl（Task 5）
    web/Request.kt             readLimited・decodeFields・safeInteger・unstorable（Task 5）
    web/App.kt                 振り分け・セキュリティヘッダー・reply/refuse・methods・health（Task 6）
    web/WorkspaceRoutes.kt     owner・sameOrigin・GET/PUT /api/workspace（Task 6）
    web/AuthRoutes.kt          signin・callback・token・session・signout・local（Task 7）
  src/test/kotlin/…            各部分のテスト
scripts/dev.cjs               TYPLE_BACKEND（Task 9）
playwright.config.ts          TYPLE_BACKEND（Task 9）
tests/devAddress.test.cjs     backendOf のテスト（Task 9）
.github/workflows/kotlin-server.yml（Task 10）
.gitignore, .vercelignore, package.json, package-lock.json, README.md（Task 10）
```

---

### Task 1: Gradle のビルド・Go と同じ JSON の書き出し・セッション

**Files:**
- Create: `server-kotlin/settings.gradle.kts`, `server-kotlin/build.gradle.kts`, `server-kotlin/gradle/libs.versions.toml`, `server-kotlin/gradlew`, `server-kotlin/gradlew.bat`, `server-kotlin/gradle/wrapper/gradle-wrapper.jar`, `server-kotlin/gradle/wrapper/gradle-wrapper.properties`（`android/` からコピー）
- Create: `server-kotlin/src/main/kotlin/…/json/GoJson.kt`, `…/session/Session.kt`, `…/session/Keys.kt`
- Test: `…/json/GoJsonTest.kt`, `…/session/KeysTest.kt`
- Modify: `.gitignore`（`server-kotlin/build/`・`server-kotlin/.gradle/`・`server-kotlin/.kotlin/`）、`.vercelignore`（`server-kotlin`）

**Interfaces:**
- Produces:
  - `object GoJson { fun string(value: String, escapeHtml: Boolean): String; fun number(value: Double): String; fun compact(raw: String, escapeHtml: Boolean): String }`
  - `data class Session(val sub: String, val name: String, val image: String, val issuedAt: Long, val expires: Long)`（秒）、`fun newSession(sub: String, name: String, image: String, nowMillis: Long): Session`、`fun Session.needsRenewal(nowMillis: Long): Boolean`
  - `data class OAuthState(val state: String, val verifier: String, val returnTo: String, val challenge: String, val expires: Long)`、`data class AppCode(val sub: String, val name: String, val image: String, val challenge: String, val expires: Long)`
  - `object SessionTimes { const val MAX_AGE = 30L*24*3600; const val OAUTH_MAX_AGE = 600L; const val APP_CODE_MAX_AGE = 120L }`、`const val OAUTH_COOKIE = "typle-oauth"`、`fun cookieName(secure: Boolean): String`
  - `class Keys private constructor(…) { companion object { fun create(secret: String): Keys /* 空なら IllegalArgumentException */ }; fun seal(s: Session): String; fun open(value: String, nowMillis: Long): Session?; fun sealApp(s: Session): String; fun openApp(value: String, nowMillis: Long): Session?; fun sealOAuth(o: OAuthState): String; fun openOAuth(value: String, nowMillis: Long): OAuthState?; fun sealCode(c: AppCode): String; fun openCode(value: String, nowMillis: Long): AppCode? }`

- [ ] **Step 1: ビルドの骨組み**

`build.gradle.kts` は `kotlin("jvm")`・`kotlin("plugin.serialization")`・`application`（`mainClass = "io.github.sparklingstadt.typle.server.MainKt"`、`applicationName = "typle-web"`）、`kotlin { jvmToolchain(21) }`、`tasks.test { useJUnitPlatform(); environment("TYPLE_TEST_DATABASE_URL", …をそのまま渡す) }`。依存は Tech Stack の版を `libs.versions.toml` に書く。`settings.gradle.kts` は `rootProject.name = "typle-web"`、`mavenCentral()`。

Run: `cd server-kotlin && ./gradlew build`
Expected: BUILD SUCCESSFUL（ソースなし）

- [ ] **Step 2: GoJson のテストを書く**

```kotlin
@Test fun stringWithoutHtmlEscape() = assertEquals("\"A & B <x>\\u2028\"", GoJson.string("A & B <x>\u2028", escapeHtml = false))
@Test fun stringWithHtmlEscape() = assertEquals("\"\\u0026\\u003c\\u003e\"", GoJson.string("&<>", escapeHtml = true))
@Test fun controlCharacters() = assertEquals("\"\\n\\t\\r\\u0001\\\\\\\"\"", GoJson.string("\n\t\r\u0001\\\"", escapeHtml = false))
@Test fun loneSurrogateIsReplaced() = assertEquals("\"\\ufffd\"", GoJson.string("\ud800", escapeHtml = false))
@Test fun numbers() {
  assertEquals("1", GoJson.number(1.0)); assertEquals("1.5", GoJson.number(1.5)); assertEquals("100", GoJson.number(1e2))
  assertEquals("0.000001", GoJson.number(1e-6)); assertEquals("1e-7", GoJson.number(1e-7)); assertEquals("1e+21", GoJson.number(1e21))
  assertEquals("123456789012345680000", GoJson.number(1.2345678901234568e20)); assertEquals("-0", GoJson.number(-0.0))
}
@Test fun compactJsonbText() = assertEquals("""[{"a":"x <y>","b":[1,2]}]""", GoJson.compact("""[{"a": "x <y>", "b": [1, 2]}]""", escapeHtml = false))
@Test fun compactWithHtmlEscape() = assertEquals("""{"a":"\u003c\u2028"}""", GoJson.compact("{\"a\": \"<\u2028\"}", escapeHtml = true))
```

Go の `\ufffd` は小文字の 16 進。Go は `escapeHtml=false` でも U+2028・U+2029 を `\u2028`・`\u2029` にする（`compact` も同じ）。`-0` は Go の `encoding/json` の出力（`-0`）に合わせる。

- [ ] **Step 3: 失敗を確かめる**

Run: `./gradlew test --tests '*GoJsonTest*'`
Expected: コンパイルエラー（`GoJson` がない）

- [ ] **Step 4: `GoJson` を書く**

- `string`：Go の `encoding/json` の `appendString` と同じ（`\"`・`\\`・`\b`・`\f`・`\n`・`\r`・`\t`、それ以外の 0x20 未満は `\u00XX`、`escapeHtml` なら `<>&` を `\u003c` 等、U+2028・U+2029 は常に `\u2028`・`\u2029`、対にならないサロゲートは `\ufffd`）。
- `number`：Go の `floatEncoder`（`1e-6 <= |x| < 1e21` は小数表記、それ以外は指数表記で `e-07` を `e-7` に）。最短の桁は `java.math.BigDecimal(x.toString())`（JDK 19 以降の `Double.toString` は最短）から作る。
- `compact`：文字列の外の空白を除き、文字列の中は書かれたまま（エスケープを解かない）で、`escapeHtml` なら `<>&` を、常に U+2028・U+2029 をエスケープする（Go の `appendCompact`）。入力は正しい JSON とみなす。

- [ ] **Step 5: 通ることを確かめる**

Run: `./gradlew test --tests '*GoJsonTest*'`
Expected: PASS

- [ ] **Step 6: Keys のテストを書く**

`server/internal/session/session_test.go` の各テストを移す（`testSecret = "typle-e2e-only-not-a-production-secret-32-bytes"`）。必ず含めるもの：
- `vector`・`escapedVector`（Go のテストの定数をそのまま）を `seal` が作り、`open(…, 1700000000_000)` が開く。
- `TestOpenHTMLEscapedCookie` と同じく、`\u0026` などで書かれた payload も開く（署名は受け取った payload に対して）。
- 期限ちょうど（`now == exp`）は開かない、`sub` が `github:abc`・`user:1` は開かない、署名の 1 文字違い・パディング付き・`.` なし・base64 の余分なビットは開かない。
- 4 種の鍵が別：session の値は `openApp`・`openOAuth`・`openCode` で開かない（逆も）。
- `openCode` は `challenge` が空なら開かない。`sealOAuth` は `challenge` が空なら JSON に `challenge` を書かない。
- `needsRenewal`：`iat` から 24 時間ちょうどで true、1 秒前で false。`cookieName(true) == "__Host-typle-session"`。
- **Go との互換**：oauth・app・code の値を Go で作ってテストに固定する。`server/internal/session/zz_vectors_test.go` を一時的に作り、`testKeys(t)` で `SealOAuth(OAuth{State:"s", Verifier:"v", ReturnTo:"/home", Challenge:"", Expires:time.Unix(1700000600,0)})`・同じく `Challenge:"c"`、`SealApp(<vector と同じ Session>)`、`SealCode(AppCode{Sub:"github:1", Name:"E2E User", Image:"", Challenge:"c", Expires:time.Unix(1700000120,0)})` を `t.Log` し、`go test ./server/internal/session -run Vectors -v` の出力を Kotlin のテストの期待値に貼る。一時ファイルは消してコミットしない。

- [ ] **Step 7: 失敗を確かめる**

Run: `./gradlew test --tests '*KeysTest*'`
Expected: コンパイルエラー

- [ ] **Step 8: `Session.kt`・`Keys.kt` を書く**

- HKDF-SHA256（RFC 5869、salt は 32 バイトのゼロ、出力 32 バイト）を `javax.crypto.Mac("HmacSHA256")` で。info は `typle session v1`・`typle oauth v1`・`typle app session v1`・`typle app code v1`。
- JSON は `GoJson.string(…, escapeHtml = false)` で Go の構造体の順に手で組む（spec の「セッション・トークン」の項目順）。
- `open` は `.` で分け、署名を `Base64.getUrlDecoder()` で厳密に（パディングや `=`・末尾の余分なビットを断る：デコードして再エンコードしたものが元と同じこと）読み、`MessageDigest.isEqual` で比べ、payload を kotlinx の `Json.parseToJsonElement` で読む。文字列の項目が文字列でない・整数の項目が整数でないなら開かない。
- `newSession` は秒に切り捨て、`expires = issuedAt + MAX_AGE`。

- [ ] **Step 9: 通ることを確かめる**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 10: コミット**

```bash
git add server-kotlin .gitignore .vercelignore
git commit -m "feat(kotlin): Gradle のビルドとセッションの発行・検証を足す"
```

---

### Task 2: 保存データの検証と書き出し（workspace）

**Files:**
- Create: `…/workspace/Workspace.kt`, `…/workspace/Encode.kt`, `…/workspace/Text.kt`
- Test: `…/workspace/WorkspaceTest.kt`, `…/workspace/EncodeTest.kt`, `…/workspace/TextTest.kt`

**Interfaces:**
- Consumes: `GoJson`（Task 1）
- Produces:
  - `data class Word(val display: String, val input: String, val annotation: String, val extra: String = "")`
  - `data class Device(val id: String, val name: String, val layout: String, val extra: String = "")`
  - `data class Record(val id: String?, val time: Double, val date: String, val mistakes: Long?, val accuracy: Double?, val wordCount: Long?, val device: Device?, val extra: String = "")`
  - `data class WordList(val id: String, val name: String, val words: List<Word>, val records: List<Record>, val createdAt: String, val extra: String = "")`
  - `data class Workspace(val lists: List<WordList>, val devices: List<Device>)`
  - `class InvalidWorkspaceException(message: String) : Exception(message)`、`fun decode(lists: String, devices: String): Workspace`（読めなければ `InvalidWorkspaceException`）
  - `fun encode(lists: List<WordList>): String`、`fun encodeDevices(devices: List<Device>): String`
  - `class WorkspaceError(val key: String) : Exception(key)`、`fun checkLimits(lists: List<WordList>)`（超えれば `WorkspaceError("invalidWorkspace")`）、`const val MAX_LISTS = 100`
  - `fun trimJS(value: String): String`、`fun utf16Length(value: String): Int`、`fun parseTime(value: String): java.time.Instant?`

- [ ] **Step 1: テストを書く**

`server/internal/workspace/workspace_test.go`・`encode_test.go` のうち `Decode`・`Encode`・`EncodeDevices`・`CheckLimits`・`ParseTime` を確かめるテストを、入力と期待をそのまま移す。それに加えて：
- `mistakes`・`wordCount` が `1.0`・`1e2`・`9223372036854775808` なら `decode` が `InvalidWorkspaceException`（Go の `*int` と同じ）。`time`・`accuracy` は `1e2` を受け付ける。
- 同じ名前の項目が 2 回ある単語（`{"display":"a","display":"b",…}`）は最後の `b`。
- 知らない項目：`{"display":"a","input":"a","annotation":"","zeta":1,"alpha":{"x": [1, 2]}}` を `decode` して `encode` すると、単語は `{"display":"a","input":"a","annotation":"","alpha":{"x":[1,2]},"zeta":1}`（既知の項目のあと、キーを並べ替え、値は詰める）。リストの `shared`・`shareId` は消える。
- 任意の項目の `null`（`id`・`mistakes`・`accuracy`・`wordCount`・`device`）は省略として読み、`encode` で書かない。
- `encode` は `& < >` をエスケープしない（`GoJson.string(…, false)`）。
- `trimJS("\u00a0\ufeff a \u3000") == "a"`、`trimJS("\u0085a") == "\u0085a"`。`utf16Length("😀") == 2`。
- `parseTime`：`2024-01-02T03:04:05.678Z`・`2024-01-02T03:04+09:00`・`2024-01-02`（UTC）・`2024/1/2 3:04:05`・`2024/1/2`（JVM の既定のタイムゾーン）を読み、`2024-13-01`・`yesterday` は null。

- [ ] **Step 2: 失敗を確かめる**

Run: `./gradlew test --tests '*workspace*'`
Expected: コンパイルエラー

- [ ] **Step 3: 実装する**

`server/internal/workspace/workspace.go`・`encode.go`・`extra.go`・`time.go`・`lowercase.go` の `trimJS` を移す。読み取りは kotlinx の `Json.parseToJsonElement`（重複キーは最後が勝つことを Step 1 のテストで確かめる）。Go の `json.Unmarshal` の型の規則に合わせる：文字列の項目に文字列以外→読めない、`*int` は小数点・指数のない 64 ビットの整数だけ、`float64` は有限の数、配列の項目に `null`→省略と同じ（必須なら読めない）。知らない項目は、キーを並べ替え、`GoJson.string(key, false)` と値の `JsonElement.toString()` を `{k:v,…}` に詰めた文字列として `extra` に持つ（値の文字列の中のエスケープの綴りは Go と違ってよい。jsonb に入れると同じになる）。数の書き出しは `GoJson.number`、整数は `toString()`。

- [ ] **Step 4: 通ることを確かめる**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): 保存データの検証と書き出しを Go と同じ規則で足す"
```

---

### Task 3: DB（store）

**Files:**
- Create: `…/store/ConnString.kt`, `…/store/Store.kt`
- Test: `…/store/ConnStringTest.kt`, `…/store/PgStoreTest.kt`

**Interfaces:**
- Consumes: `decode`・`encode`・`encodeDevices`・`checkLimits`・`WorkspaceError`・`MAX_LISTS`・`WordList`・`Device`（Task 2）
- Produces:
  - `data class Saved(val lists: String, val devices: String, val revision: Long)`（jsonb の文字列のまま）
  - `data class Change(val lists: List<WordList>, val devices: List<Device>, val hasLists: Boolean, val hasDevices: Boolean)`
  - `object ConflictException : Exception("workspace revision is stale")`
  - `interface Store { fun ping(); fun readRevision(owner: String): Saved?; fun writeRevision(owner: String, revision: Long, change: Change): Saved }`
  - `class PgStore(dataSource: javax.sql.DataSource) : Store, AutoCloseable { companion object { fun open(connection: String): PgStore } }`
  - `fun connString(connection: String): String`（Go と同じ文字列を返す）、`data class JdbcTarget(val url: String, val user: String?, val password: String?)`、`fun jdbcTarget(connection: String): JdbcTarget`

- [ ] **Step 1: ConnString のテストを書く**

`server/internal/store/store_test.go` の `ConnString` のテストを移す。加えて `jdbcTarget("postgresql://typle:p%40ss@db.example:6543/typle?sslmode=require")` が `JdbcTarget("jdbc:postgresql://db.example:6543/typle?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory", "typle", "p@ss")`、`sslmode` なしやポートなし（既定 5432 を省略したまま）、`sslmode=disable` はそのまま、`postgres://` も受け付け、`mysql://` は `IllegalArgumentException`。

- [ ] **Step 2: PgStore のテストを書く**

`TYPLE_TEST_DATABASE_URL` がないときは `Assumptions.assumeTrue` で飛ばす。各テストの前に `DELETE FROM typle_workspaces WHERE owner_id LIKE 'github:9%'` で片づける。`store_test.go` の `ReadRevision`・`WriteRevision` のテストを移す。最低限：
- 行がなければ `readRevision` は null、`writeRevision(owner, 0, …)` で作って `revision == 1`、同じことをもう一度は `ConflictException`。
- 古い `revision` は `ConflictException`、行がないのに `revision 3` も `ConflictException`。
- `hasDevices = false` なら保存済みの端末が残る（逆も）。
- 101 個のリストは新規で `WorkspaceError("listLimit")`、保存済みが 101 個なら 101 個のまま保存できる、102 個は `listLimit`。
- 上限を超える単語（1001 個）は DB に触らず `WorkspaceError("invalidWorkspace")`。
- 2 つのスレッドが同じ `revision` で同時に保存すると、片方だけ成功し片方は `ConflictException`。
- 保存データが `decode` で読めない行（`lists = '[{"id":""}]'`）は `readRevision` が例外（`ConflictException` 以外）。

- [ ] **Step 3: 失敗を確かめる**

Run: `./gradlew test --tests '*store*'`（手元では先に PostgreSQL を起動し `npm run db:migrate` した DB の URL を `TYPLE_TEST_DATABASE_URL` に入れる）
Expected: コンパイルエラー

- [ ] **Step 4: 実装する**

`server/internal/store/store.go` の `ConnString`・`Ping`・`ReadRevision`・`WriteRevision` を移す。SQL の文は Go と同じ（`$1` は `?` に、`::jsonb` はそのまま）。`readRevision` は `decode` で確かめてから文字列のまま返す。HikariCP は `maximumPoolSize = 5`、`idleTimeout = 30_000`、`minimumIdle = 0`、`connectionTimeout = 10_000`。`ping` は `SELECT 1` を 5 秒のクエリのタイムアウトで。

- [ ] **Step 5: 通ることを確かめる**

Run: `TYPLE_TEST_DATABASE_URL=… ./gradlew test`
Expected: PASS（DB のテストが SKIPPED でないこと）

- [ ] **Step 6: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): typle_workspaces を revision つきで読み書きする"
```

---

### Task 4: GitHub の OAuth とユーザー（auth）

**Files:**
- Create: `…/auth/OAuth.kt`, `…/auth/GitHubUser.kt`
- Test: `…/auth/OAuthTest.kt`, `…/auth/GitHubUserTest.kt`

**Interfaces:**
- Produces:
  - `interface OAuthClient { fun authorizeUrl(state: String, challenge: String, redirectUri: String): String; fun exchange(code: String, verifier: String, redirectUri: String): String }`
  - `class GitHubOAuth(clientId: String, clientSecret: String, authorizeEndpoint: String = "https://github.com/login/oauth/authorize", tokenUrl: String = "https://github.com/login/oauth/access_token") : OAuthClient`
  - `data class GitHubAccount(val id: Long, val login: String, val name: String, val avatarUrl: String) { val account: String /* "github:<id>" */; val displayName: String }`
  - `interface UserSource { fun user(token: String): GitHubAccount }`、`class GitHubUsers(baseUrl: String = "https://api.github.com") : UserSource`
  - 失敗はどれも `java.io.IOException`（メッセージに client secret・code・verifier・token を入れない）

- [ ] **Step 1: テストを書く**

JDK の `com.sun.net.httpserver.HttpServer` を `127.0.0.1:0` で立てて GitHub の代わりにする。`server/internal/auth/oauth_test.go` と `github_test.go` の `User` のテストを移す。最低限：
- `authorizeUrl` のクエリが `client_id`・`redirect_uri`・`state`・`code_challenge`・`code_challenge_method=S256` だけ（`scope` なし）。
- `exchange` は `POST`・`application/x-www-form-urlencoded`・`Accept: application/json` で 5 つの項目を送り、`access_token` を返す。200 で `error` があれば例外、`access_token` が空なら例外、302 は追わずに例外、500 は例外。例外のメッセージに secret・code・verifier が出ない。
- `user` は `Authorization: Bearer <token>`・`Accept: application/vnd.github+json` で `GET /user` を呼び、`id`・`login`・`name`・`avatar_url` を読む。401 は例外。`displayName` は `name` が空白だけなら `login`。

- [ ] **Step 2: 失敗を確かめる**

Run: `./gradlew test --tests '*auth*'`
Expected: コンパイルエラー

- [ ] **Step 3: 実装する**

JDK の `java.net.http.HttpClient`（`connectTimeout` 10 秒、`followRedirects(NEVER)`、各リクエストの `timeout` 10 秒）。答えは 1MiB まで読む。ヘッダーは Go の `github.go` の `User` と同じものを送る（そのファイルを確かめて合わせる）。

- [ ] **Step 4: 通ることを確かめる**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): GitHub の OAuth とユーザーの取得を足す"
```

---

### Task 5: 設定とリクエストの読み取り

**Files:**
- Create: `…/web/Config.kt`, `…/web/Request.kt`
- Test: `…/web/ConfigTest.kt`, `…/web/RequestTest.kt`

**Interfaces:**
- Consumes: `Keys`（Task 1）、`decode`・`WordList`・`Device`・`InvalidWorkspaceException`（Task 2）、`Store`・`Change`（Task 3）、`OAuthClient`・`UserSource`・`GitHubOAuth`・`GitHubUsers`（Task 4）
- Produces:
  - `data class BaseUrl(val scheme: String, val host: String, val port: Int?)` と `fun parseBaseUrl(value: String): BaseUrl?`（origin だけ残す）
  - `class Config(val store: Store?, val keys: Keys, val baseUrl: BaseUrl, val now: () -> Long = System::currentTimeMillis, val oauth: OAuthClient?, val gitHub: UserSource?, val localLogin: Boolean)`
  - `fun fromEnv(getenv: (String) -> String?, local: Boolean): Config`（`store = null`、足りなければ `IllegalStateException`、メッセージは Go と同じ）
  - `class TooLargeException : Exception()`、`fun readLimited(input: java.io.InputStream, limit: Int): ByteArray`
  - `object LegacyRequest : Exception()`、`object InvalidRequest : Exception()`
  - `fun parseObject(body: String): kotlinx.serialization.json.JsonObject`（オブジェクトでなければ `InvalidRequest`）
  - `fun decodeFields(fields: JsonObject): Pair<Long, Change>`（`LegacyRequest`・`InvalidRequest` を投げる）
  - `fun safeInteger(element: JsonElement?): Long?`、`fun unstorable(element: JsonElement): Boolean`

- [ ] **Step 1: テストを書く**

`server/internal/web/config_test.go` と `request_test.go` を移す。最低限：
- `fromEnv`：`AUTH_SECRET`・`AUTH_URL` がない・`AUTH_URL` が `ftp://x` なら、問題を全部並べた 1 つのエラー（Go と同じ文言 `the login settings are incomplete: …`）。`AUTH_GITHUB_ID` だけなら `oauth == null`。`local = true` と `LOCAL_DEVELOPMENT_LOGIN=1`・`AUTH_URL=http://localhost:3000` で `localLogin`、`https://localhost` や `local = false` では false、`http://192.168.1.5:3000` は `LOCAL_DEVELOPMENT_LAN_HOST=192.168.1.5` のときだけ true。`AUTH_URL=https://example.com/api/auth` は origin だけ。
- `safeInteger`：`0`・`1`・`1.0`・`1e2`・`9007199254740991` は数、`-1`・`1.5`・`9007199254740992`・`1e400`・`"1"`・`true`・`null`・なしは null。
- `unstorable`（JSON の文字列を `Json.parseToJsonElement` で読んで渡す）：`["\u0000"]`・`["\ud800"]`・`["\udc00"]`・`["\ud800\u0041"]`・`{"\u0000":1}`・`[{"a":["\ud800"]}]` は true、`["\\u0000"]`・`["\ud83d\ude00"]`・`["a"]` は false（2 つ目はエスケープされた `\` のあとの `u0000`、3 つ目は正しいサロゲートの対）。
- `decodeFields`：Go の `TestDecodeRequest` の表をそのまま。`profileId: "default"` は可、`"other"`・`profiles` があれば `LegacyRequest`、`lists: null` は `InvalidRequest`、`lists` も `devices` もなければ空の `Change`（`hasLists = hasDevices = false`）、片方だけなら `has…` がその方だけ true。
- `readLimited` は `limit` ちょうどは読め、`limit + 1` で `TooLargeException`。

- [ ] **Step 2: 失敗を確かめる**

Run: `./gradlew test --tests '*web.ConfigTest*' --tests '*web.RequestTest*'`
Expected: コンパイルエラー

- [ ] **Step 3: 実装する**

`config.go` の `FromEnv`・`parseBaseURL`・`localLoginAllowed` と、`request.go` の各関数を移す。`unstorable` は Go のように生の本文を走査せず、kotlinx が読んだ `lists`・`devices` の値をたどり、文字列とキーに U+0000 か対にならないサロゲートの文字があるかを見る（kotlinx は `\u0000`・`\ud800` のエスケープをその文字に解く。正しい JSON ではそれ以外の書き方ができないので、Go の判定と同じ結果になる。これを Step 1 のテストで確かめる）。Go と同じく `lists`・`devices` だけを見て、ほかの項目は見ない。

- [ ] **Step 4: 通ることを確かめる**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): 設定と /api/workspace の本文の読み取りを足す"
```

---

### Task 6: HTTP の土台・/api/health・/api/workspace

**Files:**
- Create: `…/web/App.kt`, `…/web/WorkspaceRoutes.kt`
- Test: `…/web/HeadersTest.kt`, `…/web/HealthTest.kt`, `…/web/WorkspaceRoutesTest.kt`, `…/web/Fakes.kt`（偽の Store・OAuth・GitHub、テストの `Config` を作る関数）

**Interfaces:**
- Consumes: Task 1〜5 のすべて
- Produces:
  - `fun Application.typleWeb(config: Config)`：Ktor のモジュール。1 つのインターセプター（`intercept(ApplicationCallPipeline.Call)`）で `when (call.request.path())` により振り分ける。知らないパスは `404 notFound`
  - `suspend fun ApplicationCall.reply(status: HttpStatusCode, json: String)`、`suspend fun ApplicationCall.refuse(status: HttpStatusCode, key: String)`、`suspend fun ApplicationCall.methods(vararg allow: HttpMethod): Boolean`
  - `fun ApplicationCall.setCookie(name: String, value: String, path: String, maxAge: Int, secure: Boolean)`（Go の `http.SetCookie` と同じ文字列：`name=value; Path=…; Max-Age=N; HttpOnly; Secure; SameSite=Lax`。`maxAge < 0` は `Max-Age=0`、`Secure` は secure のときだけ）
  - `fun Config.owner(call: ApplicationCall): Session?`、`fun Config.sameOrigin(call: ApplicationCall): Boolean`、`fun fromApp(call: ApplicationCall): Boolean`
  - `fun Application.unavailable(key: String)`：すべてのパスに `503 <key>`（Task 8 が使う）

- [ ] **Step 1: テストを書く**

Ktor の `testApplication { application { typleWeb(testConfig(…)) } }` で、`server/internal/web/headers_test.go`・`health_test.go`・`workspace_test.go` を移す。最低限：
- すべての応答（404・405・200・302・204）にセキュリティヘッダー 4 つ。JSON の応答に `Content-Type: application/json; charset=utf-8`・`Cache-Control: private, no-store`・`Vary: Cookie`。本文は Go の `json.Encoder` と同じく末尾に改行。
- `/api/health`：Store の `ping` が通れば `200 {"status":"ok"}`、例外なら `503 {"error":"databaseUnavailable"}`、`POST` は `405`・`Allow: GET, HEAD`、`HEAD` は本文なし。`/api/health/` と `/api/nope` は `404 notFound`。
- GET `/api/workspace`：未ログイン `401 loginRequired`、行なし `{"lists":[],"devices":[],"revision":0,"accountId":"github:1"}`、行あり（偽の Store が `Saved("[{\"id\": \"a\", \"name\": \"<b>\"}]", "[]", 4)` を返す）で本文が `{"lists":[{"id":"a","name":"\u003cb\u003e"}],"devices":[],"revision":4,"accountId":"github:1"}\n`、Store の例外は `503 databaseUnavailable`。
- PUT：spec の「`/api/workspace`」の順の各ステータス（`403 forbiddenRequest`（Origin 違い・`Content-Type: text/plain`）、`401 loginRequired`、`400 emptyRequest`、`413 workspaceTooLarge`（1MiB + 1 バイト）、`400 invalidWorkspace`（`[]`）、`401 loginAccountChanged`、`409 conflict`（`ConflictException` と `profileId: "x"`）、`400 listLimit`、`400 invalidWorkspace`（`WorkspaceError("invalidWorkspace")`）、`503 workspaceSaveFailed`（そのほかの例外））。`Content-Type: application/json; charset=utf-8` は通る。
- アプリのトークン：`Authorization: Bearer <sealApp の値>` なら Origin なしで PUT でき、Cookie があっても無視される。`Authorization: Bearer` に Cookie 用の値・`Basic x`・`Bearer a b` は `401`。
- Cookie：`typle-session` が 2 つあるときは最初のもの。値は URL デコードせずに読む。

- [ ] **Step 2: 失敗を確かめる**

Run: `./gradlew test --tests '*web*'`
Expected: コンパイルエラー

- [ ] **Step 3: 実装する**

`web.go`・`health.go`・`workspace.go` を移す。Store は Ktor の `withContext(Dispatchers.IO)` で呼ぶ。JSON の答えは `GoJson.string(…, escapeHtml = true)` と、DB の文字列は `GoJson.compact(…, escapeHtml = true)` で組み、末尾に `\n`。本文は `call.receiveStream()` を `readLimited(…, 1 shl 20)` で読み、UTF-8 として（不正なバイトは U+FFFD）文字列にする。`HEAD` は `GET` と同じ処理でヘッダーだけ返す（`AutoHeadResponse` は使わず、`reply` が `HEAD` なら本文を書かない）。

- [ ] **Step 4: 通ることを確かめる**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): /api/health と /api/workspace を足す"
```

---

### Task 7: ログイン（/api/auth/*）

**Files:**
- Create: `…/web/AuthRoutes.kt`
- Modify: `…/web/App.kt`（振り分けに 6 つのパスを足す）
- Test: `…/web/AuthRoutesTest.kt`

**Interfaces:**
- Consumes: Task 6 の `reply`・`refuse`・`methods`・`setCookie`・`owner`・`sameOrigin`・`fromApp`、Task 1 の `Keys` 他
- Produces: `fun safeReturnTo(value: String?): String`、`fun challengeOf(verifier: String): String`

- [ ] **Step 1: テストを書く**

`server/internal/web/auth_test.go` を移す（偽の OAuth・GitHub は Task 6 の `Fakes.kt`）。最低限：
- `safeReturnTo`：`/records?x=1` はそのまま、`null`・`""`・`records`・`//evil`・`/\evil`・`https://evil`・1025 バイト・`/a\u0007` は `/home`。
- signin：`302` で `Location` が偽の OAuth の URL（`redirect_uri` は `http://127.0.0.1:3101/api/auth/callback/github`）、`Set-Cookie: typle-oauth=…; Path=/api/auth; Max-Age=600; HttpOnly; SameSite=Lax`、`Cache-Control: no-store`。`client=ipad` で `code_challenge` が 42 文字なら `Location: typle-r://login?error=loginFailed`。OAuth なしは `503 authUnavailable`。`HEAD` は `405`。
- callback：成功で `typle-oauth` を `Max-Age=0` で消し、`typle-session` を `Max-Age=2592000` で発行し、`returnTo` へ。`state` 違い・`error=access_denied`・OAuth の Cookie なし・期限切れ・交換の失敗・ユーザーの失敗はどれもセッションなしで `returnTo`（Cookie がなければ `/home`）。アプリのログインは `typle-r://login?code=…`、失敗は `typle-r://login?error=loginFailed`。
- token：正しい verifier で `200 {"token":"…","user":{"id":"github:1","name":"…","image":null}}`、違う verifier・期限切れの code・4097 バイト・壊れた JSON は `400 loginFailed`、`GET` は `405`。
- session：未ログイン `{"user":null}`、ログイン中 `{"user":{…}}`、24 時間たった Cookie で Cookie を発行し直し、24 時間たったアプリのトークンで `"token"` を返す（Cookie は発行しない）。
- signout：Origin 違いは `403 forbiddenRequest`、同じなら `204` と `typle-session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax`。
- local：`localLogin = false` なら `POST` でも `GET` でも `404 notFound`、有効なら Origin を確かめて `github:0`・`ローカル練習用` で `204`。
- `AUTH_URL` が https なら Cookie の名前が `__Host-typle-session` で `Secure` が付く。

- [ ] **Step 2: 失敗を確かめる**

Run: `./gradlew test --tests '*AuthRoutesTest*'`
Expected: FAIL（`404 notFound`）

- [ ] **Step 3: 実装する**

`server/internal/web/auth.go` を移す。乱数は `SecureRandom` の 32 バイトの base64url（パディングなし）。`state` の比較は `MessageDigest.isEqual`。OAuth と GitHub の呼び出しは `Dispatchers.IO` で。失敗はログに `"<METHOD> <path>: auth.callback: <例外のメッセージ>"`。

- [ ] **Step 4: 通ることを確かめる**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): GitHub ログインとアプリのトークンを足す"
```

---

### Task 8: 起動（Main と Backend）

**Files:**
- Create: `…/Main.kt`, `…/Backend.kt`
- Test: `…/BackendTest.kt`

**Interfaces:**
- Consumes: `typleWeb`・`Config`・`fromEnv`（Task 5・6）、`PgStore.open`・`Store`（Task 3）
- Produces: `class Backend(config: Config, open: (String) -> Store, connection: String?, now: () -> Long = System::currentTimeMillis, retryAfterMillis: Long = 1000) : AutoCloseable { fun store(): Store?; fun ready(): Boolean }`、`fun Application.backend(backend: Backend, config: Config)`

- [ ] **Step 1: テストを書く**

`server/vercel/vercel_test.go` のうち作る範囲を移す。最低限：
- 開く関数が失敗する間：`/api/health`・`/api/workspace`・`/api/nope` は `503 databaseUnavailable`、`/api/auth/session` は `200 {"user":null}`。
- 失敗のあと 1 秒たたないうちのリクエストは開き直さない（開く関数の呼ばれた回数で確かめる）、1 秒たてば開き直し、成功したら以降は呼ばない。
- 同時の 5 つのリクエストで開く関数は 1 回だけ。
- `DATABASE_URL` も `POSTGRES_URL` もなければ開かずに `503`（ログに理由）。

- [ ] **Step 2: 失敗を確かめる**

Run: `./gradlew test --tests '*BackendTest*'`
Expected: コンパイルエラー

- [ ] **Step 3: 実装する**

- `Backend`：Go の `server/vercel/vercel.go` の `database()` と同じ（開いている途中のリクエストは同じ試行を待つ、開く時間は最大 10 秒、失敗は 1 秒覚える）。`Application.backend` はパスが `/api/auth/` で始まればそのまま `typleWeb` に、それ以外は `store()` が null なら `503 databaseUnavailable`。
- `Main.kt`：`-addr host:port`（既定 `127.0.0.1:8081`、IPv6 は `[::1]:9002`）を読み、`fromEnv(System::getenv, local = true)` が失敗すれば理由を出して `exitProcess(1)`。接続文字列は `DATABASE_URL`、なければ `POSTGRES_URL`。`embeddedServer(CIO, host, port)` を起動して `serving the app's backend on http://<addr>` をログに出し、裏で `ready()` を呼んで失敗なら Go と同じ警告を出す。`Runtime.addShutdownHook` で `stop(5000, 5000)` と `Backend.close()`。

- [ ] **Step 4: 通ることを確かめる**

Run: `./gradlew test installDist && AUTH_SECRET=x AUTH_URL=http://127.0.0.1:3000 build/install/typle-web/bin/typle-web -addr 127.0.0.1:8099 & sleep 3; curl -s http://127.0.0.1:8099/api/auth/session; curl -s http://127.0.0.1:8099/api/health; kill %1`
Expected: テストが PASS、`{"user":null}` と `{"error":"databaseUnavailable"}`、`kill` で 5 秒以内に終わる

- [ ] **Step 5: コミット**

```bash
git add server-kotlin
git commit -m "feat(kotlin): typle-web の起動と DB の遅れた接続を足す"
```

---

### Task 9: TYPLE_BACKEND で切り替える（dev と E2E）

**Files:**
- Modify: `scripts/dev.cjs`, `playwright.config.ts`
- Test: `tests/devAddress.test.cjs`

**Interfaces:**
- Produces: `scripts/dev.cjs` の `backendOf(value = process.env.TYPLE_BACKEND): 'go' | 'kotlin'`（不正なら `Error`）、`KOTLIN_BINARY = 'server-kotlin/build/install/typle-web/bin/typle-web'`

- [ ] **Step 1: テストを書く**

```js
const { webAddress, backendOf } = require('../scripts/dev.cjs')
test('TYPLE_BACKEND picks the backend', () => {
  assert.equal(backendOf(undefined), 'go')
  assert.equal(backendOf(''), 'go')
  assert.equal(backendOf('go'), 'go')
  assert.equal(backendOf('kotlin'), 'kotlin')
  for (const value of ['Kotlin', 'java', 'rust']) assert.throws(() => backendOf(value), /TYPLE_BACKEND/, value)
})
```

- [ ] **Step 2: 失敗を確かめる**

Run: `node --test tests/devAddress.test.cjs`
Expected: FAIL（`backendOf is not a function`）

- [ ] **Step 3: 実装する**

- `backendOf`：エラーの文言は「TYPLE_BACKEND (<値>) は go か kotlin にしてください。」。
- `startDev`：`kotlin` なら `java -version` が起動できなければ「java コマンドが見つかりません。Kotlin のバックエンドに JDK 21 以上が必要です。https://adoptium.net/ からインストールしてください。」で終了。次に `spawnSync('server-kotlin/gradlew', ['-p', 'server-kotlin', '--quiet', 'installDist'], { stdio: 'inherit' })` が 0 でなければ終了。`go run …` の代わりに `spawn(KOTLIN_BINARY, ['-addr', address], { stdio: 'inherit', detached: true })`。`error` の `ENOENT` の文言はバックエンドに合わせる。
- `playwright.config.ts`：`process.env.TYPLE_BACKEND === 'kotlin'` なら 1 つ目の `webServer.command` を `${KOTLIN_BINARY} -addr 127.0.0.1:8081`（事前に `installDist` が要ることをコメントに）。

- [ ] **Step 4: 通ることを確かめる**

Run: `npm test && npm run lint && npm run typecheck`
Expected: PASS

- [ ] **Step 5: Kotlin で E2E を通す**

PostgreSQL を起動して `npm run db:migrate`、`npm run build`、`(cd server-kotlin && ./gradlew installDist)`。
Run: `TYPLE_BACKEND=kotlin DATABASE_URL=… npm run test:e2e`
Expected: Go のとき（`DATABASE_URL=… npm run test:e2e`）と同じく全件 PASS。落ちたテストは Kotlin 側を直す（テストは変えない）。

- [ ] **Step 6: コミット**

```bash
git add scripts/dev.cjs playwright.config.ts tests/devAddress.test.cjs server-kotlin
git commit -m "feat: TYPLE_BACKEND=kotlin で Kotlin のバックエンドを使えるようにする"
```

---

### Task 10: CI・ドキュメント・版

**Files:**
- Create: `.github/workflows/kotlin-server.yml`, `server-kotlin/README.md`
- Modify: `README.md`（先頭に v0.58.0 の節、開発の節に `TYPLE_BACKEND`）、`package.json`・`package-lock.json`（`0.58.0`、`npm version 0.58.0 --no-git-tag-version`）

- [ ] **Step 1: ワークフローを書く**

`name: Kotlin backend`。`on: pull_request`、`push: branches: [main]`、`workflow_dispatch`（パスで絞らない）。`permissions: contents: read`、`concurrency: kotlin-server-${{ github.ref }}`（`cancel-in-progress: true`）。ジョブ `e2e`（`timeout-minutes: 30`）：`ci.yml` の `verify` と同じ PostgreSQL のサービスと `DATABASE_URL`、同じピン留めの `actions/checkout`・`actions/setup-node`（node 24、npm のキャッシュ）、`android.yml` と同じピン留めの `actions/setup-java`（temurin、`'21'`）と `gradle/actions/setup-gradle` → `npm ci` → `npm run db:migrate` → `cd server-kotlin && ./gradlew test installDist`（`TYPLE_TEST_DATABASE_URL: ${{ env.DATABASE_URL }}`）→ `npm run build` → `ci.yml` と同じ Playwright のキャッシュの手順 → `TYPLE_BACKEND=kotlin npm run test:e2e`。

- [ ] **Step 2: ドキュメントと版**

- `server-kotlin/README.md`：何か（Go の `typle-web` と同じ API の Kotlin 版、手元と CI だけ）、必要なもの（JDK 21）、使い方（`TYPLE_BACKEND=kotlin npm run dev`、単独の起動、テスト）、Go との対応表（spec の「パッケージ」の表）、Go を変えたら Kotlin も直すこと、範囲外。
- `README.md` の先頭（`# Typle` の説明の次）に `## v0.58.0 — Kotlin のバックエンドを手元で使えるようにしました`：何ができるか、`TYPLE_BACKEND=kotlin`、JDK 21、本番と既定は Go のまま、CI の `Kotlin backend` が E2E を Kotlin で回すこと、詳しくは `server-kotlin/README.md`。

- [ ] **Step 3: 確かめる**

Run: `npm test && npm run lint && npm run typecheck && (cd server-kotlin && ./gradlew test)`
Expected: PASS。`git diff --stat main -- api server src` が空（本番のコードを変えていない）。

- [ ] **Step 4: コミット**

```bash
git add .github/workflows/kotlin-server.yml server-kotlin/README.md README.md package.json package-lock.json
git commit -m "docs: Kotlin のバックエンドの使い方を書き、v0.58.0 にする"
```
