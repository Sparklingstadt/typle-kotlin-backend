# Typle のバックエンド（Kotlin）

[typle-r](https://github.com/Sparklingstadt/typle-r) の Go のバックエンド `server/cmd/typle-web` と同じ API を、Kotlin（Ktor）で作った並行実装です。typle-r の手元の開発（`npm run dev`）と E2E で、Go の代わりに選んで使えます。**本番（Vercel）は Go のままで、この Kotlin 版は本番に載せません**（Vercel には JVM のランタイムがありません）。設計は `docs/specs/2026-10-21-kotlin-backend-design.md` にあります。

typle-r v0.89.0 で、typle-r の `server-kotlin/` からこのリポジトリに移しました（`server-kotlin/` の履歴も引き継いでいます）。コミットの `(Sparklingstadt/typle-r#N)` は typle-r の PR です。このリポジトリの `server/…` のパスは、どれも typle-r のものです。

## 必要なもの

- JDK 21（ちょうど 21。Gradle 8.14 は Java 25 では動かず、toolchain の 21 も自動では取ってきません。`JAVA_HOME` で指定できます。Gradle 本体は同梱の `gradlew` が取ってきます）
- PostgreSQL（Go のときと同じ DB。typle-r で `npm run db:migrate` 済みのもの）
- typle-r と一緒に使うときは typle-r の手元（既定では、このリポジトリの隣の `../typle-r` から使います）

## 使い方

typle-r の手元で次のように起動します。typle-r はこのリポジトリを `TYPLE_KOTLIN_BACKEND` の場所から使い、指定しなければ typle-r の隣の `../typle-kotlin-backend` を使います。

```sh
cd ../typle-r
TYPLE_BACKEND=kotlin npm run dev        # dev:local・dev:lan も同じ
TYPLE_BACKEND=kotlin TYPLE_KOTLIN_BACKEND=/path/to/typle-kotlin-backend npm run dev
```

typle-r の `scripts/dev.cjs` が先にこのリポジトリを `gradlew installDist` でビルドし、`go run` の代わりに `build/install/typle-web/bin/typle-web` を `next dev` と一緒に起動します。待受（既定 `127.0.0.1:8081`、`TYPLE_WEB_URL` で変更）、環境変数（`.env.local` の `AUTH_SECRET`・`AUTH_URL`・`AUTH_GITHUB_ID`・`AUTH_GITHUB_SECRET`・`DATABASE_URL` など）、どちらかが止まればもう片方も止める動きは Go のときと同じです。`TYPLE_BACKEND` を指定しないか `go` なら Go を使います。

単独で起動するときは次のとおりです。

```sh
./gradlew installDist
AUTH_SECRET=… AUTH_URL=http://127.0.0.1:3000 DATABASE_URL=… build/install/typle-web/bin/typle-web -addr 127.0.0.1:8081
```

- ログインの設定（`AUTH_SECRET`・`AUTH_URL`）がなければ、理由を出して起動しません。
- DB につながらなくても起動し、その間 `/api/health`・`/api/workspace` は `503 databaseUnavailable` を返し、次のリクエストでつなぎ直します（失敗から 1 秒はつなぎ直しません）。`/api/auth/*` は DB なしで動きます。
- SIGINT・SIGTERM では、処理中のリクエストを最大 5 秒待ってから止まります。

## Go との互換

- パス・メソッド・ステータス・エラーのキー・ヘッダー（`Cache-Control`・`Vary`・4 つのセキュリティヘッダー）・Cookie の書き方は Go と同じです。
- 同じ `AUTH_SECRET` なら、Go が発行したセッションの Cookie・アプリ（iPad・Android）のトークンを Kotlin が開け、その逆もできます。JSON の項目の順とエスケープまで Go の `encoding/json` と同じに書きます（`GoJson`）。
- 同じ DB（`typle_workspaces`）を同じ SQL で読み書きするので、Go と Kotlin を入れ替えてもデータの形は変わりません。
- 保存の検証（`workspace`）は Go の `workspace.Decode` と同じ規則です。JSON は Go の `encoding/json` と同じ厳しさで読む専用のパーサー（`json/JsonValue.kt`）で読みます。

## 構成

Go の `server/internal/*` と 1 対 1 に対応します。Go を変えたら、ここも同じように直してください（typle-r の `TYPLE_BACKEND=kotlin npm run test:e2e` で確かめます）。

| Kotlin（`src/main/kotlin/…/server/`） | Go |
|---|---|
| `Main.kt`・`Backend.kt` | `server/cmd/typle-web`・`server/vercel` |
| `web/` | `server/internal/web` |
| `session/` | `server/internal/session` |
| `auth/` | `server/internal/auth`（`OAuth` と `GitHub.User`） |
| `store/` | `server/internal/store`（`ConnString`・`Ping`・`ReadRevision`・`WriteRevision`） |
| `workspace/` | `server/internal/workspace`（`Decode`・`Encode`・`CheckLimits`・`ParseTime`・`trimJS`） |
| `json/` | Go の `encoding/json` の書き方と読み方 |

## 確認

```sh
TYPLE_TEST_DATABASE_URL=postgresql://… ./gradlew test    # DB のテストは URL がなければ飛ばします
./gradlew installDist && cd ../typle-r && TYPLE_BACKEND=kotlin npm run test:e2e
```

- 単体テストは、Go のテストの入力と期待をそのまま移したものです。セッションは Go が作った値を固定し、同じ文字列になることを確かめます。HTTP のテストはポート 0 で実際のサーバーを起動して確かめます。
- CI のワークフローはありません（typle-r v0.70.0 で削除）。`./gradlew test` と、typle-r の `TYPLE_BACKEND=kotlin npm run test:e2e` は手元で行います。E2E は typle-r にあり、`TYPLE_KOTLIN_BACKEND`（既定は typle-r の隣の `../typle-kotlin-backend`）の `build/install/typle-web/bin/typle-web` を起動します。

## 範囲外

`/api/v1`（`server/cmd/typle-api`）、Sentry への送信（失敗はログに出すだけ）、本番への配置、Vercel の関数の入口。
