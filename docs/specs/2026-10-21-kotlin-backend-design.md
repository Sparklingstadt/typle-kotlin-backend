# Kotlin のバックエンド（手元で動く並行実装） 設計

- 日付: 2026-10-21
- 対象バージョン: v0.58.0

## 目的

アプリ本体のバックエンド（Go の `server/cmd/typle-web`）と同じ API を Kotlin（Ktor）でもう 1 つ作り、手元の開発（`npm run dev`）と E2E で、Go の代わりに選んで動かせるようにする。本番（Vercel）は Go のまま変えない。

## 成功の条件

- `TYPLE_BACKEND=kotlin npm run dev` で、Web 版のログイン・ログアウト・保存・読み込みが Go のときと同じように動く。
- 今の Playwright の E2E が、テストを変えずに、バックエンドを Kotlin にしても全部通る。CI で毎回確かめる。
- 同じ `AUTH_SECRET` と同じ DB なら、Go と Kotlin を入れ替えても、ログインしたまま同じデータを読み書きできる。
- 本番のデプロイ物と、Web 版のリリース・デプロイの流れ（`ci.yml`）は変わらない。

## 決定事項

| 項目 | 決定 |
|---|---|
| 作る範囲 | `typle-web` 相当：`/api/health`・`/api/workspace`・`/api/auth/{signin,callback/github,session,token,signout,local}` |
| 作らない | `/api/v1`（`server/cmd/typle-api`・`server/internal/api`）、Sentry への送信、Vercel の入口 |
| 動かす場所 | 手元と CI だけ。本番は Go |
| 言語・枠組み | Kotlin（JVM 21）、Ktor（CIO エンジン）、JDBC（PostgreSQL ドライバー）＋ HikariCP、kotlinx.serialization |
| 置き場所 | リポジトリ直下の `server-kotlin/`。独立した Gradle ビルド（Wrapper 同梱）。`android/` とはコードを共有しない |
| 切り替え | 環境変数 `TYPLE_BACKEND`（`go` が既定、`kotlin`） |
| CI | 新しいワークフロー `kotlin-server.yml`（PR と手動の実行）。`ci.yml` のリリースの条件にはしない |

### `android/core` を共有しない理由

`android/core` は画面のための読み込みで、知らない項目を捨て、保存の上限も確かめない。サーバーの検証（`src/core/workspaceRequest.ts`・Go の `workspace.Decode`）とは規則が違うため、共有すると片方の変更がもう片方を壊しやすい。

## 範囲外

- `/api/v1` と、その読み取り系（ランキング・ダッシュボード・CSV・端末の使用状況・リストの複製など）
- 本番への配置（Vercel には JVM のランタイムがない）
- Sentry（失敗はログに出すだけ）
- 画面・文言・DB のスキーマの変更
- `android/`・`ios/` の変更（アプリはサーバーの URL を変えれば Kotlin にもつながるが、確かめる対象にはしない）

## 構成

### Gradle のビルド

- `server-kotlin/settings.gradle.kts`・`build.gradle.kts`・`gradle/libs.versions.toml`・`gradlew`（Wrapper）。
- `application` プラグインで、`installDist` が `server-kotlin/build/install/typle-web/bin/typle-web` を作る。
- JVM のターゲットは 21。テストは JUnit 5（`kotlin("test")`）。

### パッケージ（`io.github.sparklingstadt.typle.server.*`）

Go の `server/internal/*` と 1 対 1 に対応させ、移すときに比べやすくする。

| Kotlin | 対応する Go | 内容 |
|---|---|---|
| `Main.kt` | `cmd/typle-web/main.go`・`vercel/vercel.go` | `-addr`（既定 `127.0.0.1:8081`）で待ち受け、ログインの設定を起動時に確かめ（足りなければ理由を出して終了）、DB は裏で開く。DB が開くまで `/api/health`・`/api/workspace` は `503 databaseUnavailable`、失敗後は 1 秒あけて次のリクエストで開き直す。SIGINT・SIGTERM で 5 秒待って止める |
| `web/` | `internal/web` | ルーティング、セキュリティヘッダー、`reply`・`refuse`、各エンドポイント、`FromEnv`、リクエストの読み取り（`request.go`） |
| `session/` | `internal/session` | 鍵の導出、4 種類（session・oauth・app・code）の発行と検証 |
| `auth/` | `internal/auth` の `oauth.go` と `github.go` の `User` | GitHub の authorize URL・コードの交換・`GET /user`。JDK の `HttpClient`（タイムアウト 10 秒、リダイレクトは追わない） |
| `store/` | `internal/store` の `ConnString`・`Ping`・`ReadRevision`・`WriteRevision` | JDBC と HikariCP（最大 5 接続、30 秒使わない接続は閉じる） |
| `workspace/` | `internal/workspace` の `Decode`・`Encode`・`EncodeDevices`・`CheckLimits`・`ParseTime`・`trimJS`・知らない項目の保持 | 保存データの検証と書き出し |

## Go と同じにすること

### HTTP の応答

- パスとメソッド、ステータス、エラーのキー（`{"error":"…"}`）は Go と同じ。知らないパスは `404 notFound`、許さないメソッドは `405 methodNotAllowed` と `Allow`。
- JSON の応答には `Content-Type: application/json; charset=utf-8`・`Cache-Control: private, no-store`・`Vary: Cookie`。
- すべての応答にセキュリティヘッダー（`Content-Security-Policy: frame-ancestors 'none'; base-uri 'self'; object-src 'none'`・`X-Frame-Options: DENY`・`X-Content-Type-Options: nosniff`・`Referrer-Policy: strict-origin-when-cross-origin`）。
- リダイレクトは `302`・`Location`・`Cache-Control: no-store`。
- `HEAD` は Go と同じく `GET` と同じ処理で本文なし（`/api/health`・`/api/workspace`・`/api/auth/session`）。

### セッション・トークン（`session/`）

- 鍵：`AUTH_SECRET` から HKDF-SHA256（salt なし、32 バイト）で、info `typle session v1`・`typle oauth v1`・`typle app session v1`・`typle app code v1` の 4 つ。HKDF は JDK の `Mac`（HmacSHA256）で自前に書く（RFC 5869 の extract と expand）。
- 形式：`base64url(JSON)` + `.` + `base64url(HMAC-SHA256)`（パディングなし）。
- JSON は Go の構造体と同じ項目の順で、`& < >` をエスケープせず、U+2028・U+2029 は `\u2028`・`\u2029` と書く（Go の `encoding/json` と同じ）。そのため文字列の書き出しは自前の小さな関数にする。
  - session・app：`sub`・`name`・`image`・`iat`・`exp`
  - oauth：`state`・`verifier`・`returnTo`・`exp`、`challenge` は空なら出さない
  - code：`sub`・`name`・`image`・`challenge`・`exp`
- 検証：署名は受け取った payload の文字列に対して比べ（定数時間）、base64url は厳密に読む（パディングや余分なビットを断る）。期限切れ、`sub` が `^github:\d+$` に合わないもの（session・app・code）、`challenge` が空の code は断る。
- 期限：session と app は 30 日、24 時間たったら更新、oauth は 10 分、code は 2 分。時刻は秒で切り捨てる。
- Cookie の名前：`AUTH_URL` が https なら `__Host-typle-session`、http なら `typle-session`。OAuth は `typle-oauth`（`Path=/api/auth`）。属性は `HttpOnly`・`SameSite=Lax`・https なら `Secure`、`Max-Age` は Go と同じ。

### ログイン（`web/` の auth）

`server/internal/web/auth.go` と同じ流れと判定にする。

- `signin`：`GET` だけ。`client=ipad`／`android` なら `code_challenge`（`^[A-Za-z0-9_-]{43}$`）を確かめ、違えば `typle-r://login?error=loginFailed`。`state` と verifier は 32 バイトの乱数の base64url。`returnTo` は `safeReturnTo`（`/` で始まり `//`・`/\` で始まらない、1024 バイト以下、制御文字なし、スキームやホストなし）でなければ `/home`。
- `callback/github`：OAuth の Cookie を必ず消し、`state` を定数時間で比べ、コードを交換して `/user` を読み、Web ならセッションの Cookie を発行して `returnTo` へ、アプリなら code を `typle-r://login?code=…` で返す。失敗はどれも Cookie なしで `returnTo`（アプリは `loginFailed`）へ。
- `token`：`POST` だけ、本文 4096 バイトまで。`verifier` が `^[A-Za-z0-9._~-]{43,128}$` で、その S256 が code の `challenge` と一致すれば `{ token, user }`、それ以外は `400 loginFailed`。
- `session`：`Authorization` があればそれだけで判定（`Bearer ` のあとに空白のないトークン）、なければ Cookie。24 時間たっていれば、Web は Cookie を発行し直し、アプリは `token` を返す。`image` が空なら `null`。
- `signout`：`POST`、Origin が `AUTH_URL` のオリジンと同じでなければ `403 forbiddenRequest`、Cookie を消して `204`。
- `local`：有効でなければ `404 notFound`。有効の条件は Go の `localLoginAllowed`（`LOCAL_DEVELOPMENT_LOGIN=1`、`AUTH_URL` が http で loopback か `LOCAL_DEVELOPMENT_LAN_HOST` と同じ私設 IPv4）。`github:0`「ローカル練習用」で発行して `204`。
- `AUTH_GITHUB_ID`・`AUTH_GITHUB_SECRET` のどちらかがなければ、警告を 1 回ログに出し、`signin`・`callback` だけ `503 authUnavailable`。
- オリジンの比較は Go の `sameOrigin` と同じ（小文字、既定のポートは書かない、IPv6 は `[]`）。

### `/api/workspace`

`server/internal/web/workspace.go`・`request.go` と同じ順で確かめる。

- GET：未ログインは `401 loginRequired`、行がなければ `{ "lists": [], "devices": [], "revision": 0, "accountId" }`、あれば保存されている jsonb の文字列をそのまま `lists`・`devices` に入れて返す。保存データが `Decode` で読めない・DB の失敗は `503 databaseUnavailable`。
- PUT：Origin（アプリのトークンなら不要）と `Content-Type`（`;` の前が `application/json`）→ ログイン → 本文なし `400 emptyRequest` → 1MiB を超えたら読むのをやめて `413 workspaceTooLarge` → JSON のオブジェクトでなければ `400 invalidWorkspace` → `accountId` が違えば `401 loginAccountChanged` → `revision`（JavaScript の安全な整数として読める 0 以上の数。`1.0`・`1e2` は可）→ `profileId` が `default` 以外か `profiles` があれば `409 conflict` → `lists`・`devices` の `null` は不可、PostgreSQL の jsonb に入らない `\u0000` と対になっていないサロゲートのエスケープは `400 invalidWorkspace` → `Decode` → 保存。
- 保存（`WriteRevision`）の判定と SQL は Go と同じ文：`revision == 0` は `INSERT … ON CONFLICT (owner_id) DO NOTHING RETURNING`、それ以外は `SELECT revision, jsonb_array_length(lists)` のあと `UPDATE … WHERE owner_id = $1 AND revision = $2 RETURNING`。`listLimit`（100 を超え、かつ保存済みより多い）、`invalidWorkspace`（上限）、`409 conflict`、そのほかは `503 workspaceSaveFailed`。

### 保存データの検証と書き出し（`workspace/`）

- `Decode` の規則は Go と同じ：リストの必須項目、名前・単語の空白（`trimJS`：JavaScript の `String.prototype.trim` と同じ空白の集合）、id の重複、記録の `time`・`date`（`ParseTime` の形式）・`accuracy`・`mistakes`・`wordCount`・`device`、端末の数と長さ（UTF-16 の単位）と `default` の禁止。任意の項目の `null` は省略として読む。
- 知らない項目は残す。Go と同じく、キーを並べ替えて詰めた JSON として持ち、書き出すときに既知の項目の後ろに足す。リストの `shared`・`shareId` は捨てる。
- 書き出しは JSON.stringify と同じく `& < >` をエスケープしない。数値は Go と同じ形（整数は整数、`time`・`accuracy` は JavaScript の数の文字列と同じ最短表記）。DB に入れるのは jsonb なので空白や項目の順の違いは保存に影響しないが、テストでは Go と同じ文字列になることを確かめる。
- `ParseTime` の「タイムゾーンなし」の形式は、Go と同じくサーバーのタイムゾーン（JVM の既定）で読む。

### DB（`store/`）

- `DATABASE_URL`（なければ `POSTGRES_URL`）を Go の `ConnString` と同じ規則で直す（`sslmode` の `prefer`・`require`・`verify-ca` は `verify-full`、`uselibpqcompat=true` なら直さずに取り除く。`sslmode` のない URL も、接続先がどれもこの機械（`localhost`・`127.0.0.0/8`・`::1`・Unix ソケット）でなければ `verify-full`）。そのあと JDBC の URL（`jdbc:postgresql://host:port/db`）とユーザー・パスワードに分ける。
- `sslmode=verify-full` のときは `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` で JVM の信頼するルート証明書を使う（pgjdbc の既定は `~/.postgresql/root.crt` を探すため）。
- `Ping` は `SELECT 1`（5 秒で打ち切り）。

## 切り替え方

### `npm run dev`（`scripts/dev.cjs`）

- `TYPLE_BACKEND` が未設定か `go`：今と同じ（`go run ./server/cmd/typle-web`）。
- `kotlin`：先に `server-kotlin/gradlew -p server-kotlin installDist`（同期、失敗したら終了）、次に `server-kotlin/build/install/typle-web/bin/typle-web -addr <アドレス>` を `next dev` と一緒に起動する。止め方（両方を同じように止める）は今と同じ。
- それ以外の値は「`TYPLE_BACKEND` は go か kotlin」と日本語で出して終了。`kotlin` で `java` がなければ「JDK 21 が必要」（Gradle 8.14 は Java 25 では動かないため 21 ちょうど。`JAVA_HOME` があればその java で確かめる）と入れ方を出して終了。
- `dev:local`・`dev:lan` は `startDev` を使うので同じく切り替わる。
- `next.config.mjs` の rewrites は変えない（転送先のアドレスが同じ）。

### E2E（`playwright.config.ts`）

- `TYPLE_BACKEND=kotlin` のとき、1 つ目の `webServer` の `command` を `server-kotlin/build/install/typle-web/bin/typle-web -addr 127.0.0.1:8081` にする（環境変数と `url` は同じ）。ビルドは事前に `installDist` しておく（CI では手順で、手元では README に書く）。
- テスト（`tests/e2e/*`）は変えない。

## テスト

- Kotlin の単体テスト（`cd server-kotlin && ./gradlew test`）。Go のテストのうち作る範囲のものを移す。
  - `session`：発行・検証・改ざん・期限切れ・更新・種類の取り違え。**Go との互換**：決まった secret と時刻で Go が作った値（Go のテストで出力して固定）と、`tests/e2e/sessionCookie.ts` のテスト用の値を、Kotlin が同じ文字列で作り、開けること。
  - `workspace`：Go の `workspace_test.go`・`encode_test.go` の入力（受け付ける・断る・知らない項目の保持・書き出しの文字列）。
  - リクエストの読み取り：`safeInteger`・`unstorable`・`profileId` などの Go の `request_test.go` の入力。
  - ハンドラー：Ktor の `testApplication` と偽の Store・OAuth・GitHub で、Go の `auth_test.go`・`workspace_test.go`・`headers_test.go`・`health_test.go` が確かめているステータス・ヘッダー・Cookie。
  - `store`：`TYPLE_TEST_DATABASE_URL` があるときだけ、実際の PostgreSQL で `ReadRevision`・`WriteRevision`（作成・更新・競合・`listLimit`・省略した方が残ること・同時の保存）。
  - `ConnString` と JDBC への変換。
- E2E：全テストを `TYPLE_BACKEND=kotlin` で通す。
- `npm test`：`tests/devAddress.test.cjs` に `TYPLE_BACKEND` の読み取り（既定・go・kotlin・不正な値）を足す。

## CI（`.github/workflows/kotlin-server.yml`）

- きっかけ：すべての PR と `workflow_dispatch`。パスで絞らない（`src/`・`server/` の変更で Go と Kotlin がずれたら気づけるように）。iPad・Android のワークフローと同じく、GitHub Actions の利用枠を節約するため main への push では動かさない（PR で通ったものを再び流さない）。
- 手順：PostgreSQL（`ci.yml` と同じ）→ `npm ci` → `npm run db:migrate` → `setup-java`（temurin 21）・`setup-gradle` → `server-kotlin` の `./gradlew test installDist`（`TYPLE_TEST_DATABASE_URL` あり）→ `npm run build` → Playwright の準備（`ci.yml` と同じキャッシュ）→ `TYPLE_BACKEND=kotlin npm run test:e2e`。
- `ci.yml` の `release`・`deploy` はこのワークフローを待たない。失敗したときは次の PR で直す（`android.yml` と同じ扱い）。

## 設定ファイルとドキュメント

- `.vercelignore` に `server-kotlin` を足す（本番のビルドに入れない）。`.gitignore` に `server-kotlin/build`・`server-kotlin/.gradle`。
- `package.json` の版を `0.58.0` に上げる。
- `README.md` の先頭に v0.58.0 の節（何ができるか、`TYPLE_BACKEND=kotlin`、JDK 21、本番は Go のまま）。
- `server-kotlin/README.md`：起動・テスト・Go との対応・範囲外。

## リスク

- Go と Kotlin で判定がずれると、手元では動くのに本番で動かない（またはその逆）。互換のテストと、E2E を両方で回すことで防ぐ。Go を変えたときに Kotlin も直す必要があり、CI の `kotlin-server.yml` が落ちて気づく。
- JSON の数値の書き出し（Go と JavaScript の浮動小数点の表記）の違い。DB は jsonb なので保存の結果は変わらないが、テストで Go と同じ文字列になることを確かめる。
- Gradle の初回のビルドは時間がかかる（`npm run dev` の起動が遅くなる）。既定は Go のままにする。
