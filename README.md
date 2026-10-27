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
- DB につながらなくても起動し、その間 `/api/health`・`/api/workspace`・`/api/shares`・`/api/shared` は `503 databaseUnavailable` を返し、次のリクエストでつなぎ直します（失敗から 1 秒はつなぎ直しません）。`/api/auth/*` は DB なしで動きます。
- SIGINT・SIGTERM では、処理中のリクエストを最大 5 秒待ってから止まります。

## Go との互換

- パス・メソッド・ステータス・エラーのキー・ヘッダー（`Cache-Control`・`Vary`・4 つのセキュリティヘッダー）・Cookie の書き方は Go と同じです。
- 同じ `AUTH_SECRET` なら、Go が発行したセッションの Cookie・アプリ（iPad・Android）のトークンを Kotlin が開け、その逆もできます。JSON の項目の順とエスケープまで Go の `encoding/json` と同じに書きます（`GoJson`）。
- 同じ DB（`typle_workspaces`）を同じ SQL で読み書きするので、Go と Kotlin を入れ替えてもデータの形は変わりません。
- 保存の検証（`workspace`）は Go の `workspace.Decode` と同じ規則です。JSON は Go の `encoding/json` と同じ厳しさで読む専用のパーサー（`json/JsonValue.kt`）で読みます。
- 単語パック（typle-r v0.91.0）: `/api/workspace` の GET・PUT は Go と同じく `packs` 列を読み書きします（応答の項目の順は `lists`・`packs`・`devices`・`revision`・`accountId`、列が `NULL` か行がなければ `"packs":null`）。`lists` と `packs` を一緒に送る保存は Go の `CheckLibrary` と同じ検証をし、`lists` だけの保存は `group`・`category`・`tags` を送られたまま保存して `packs` 列を変えません。保存済みのリストのパックへの変換と補正は Go と違って行わず、アプリに任せます（応答は保存されたままなので、結果は同じです）。
- リンクで共有（typle-r v0.96.0）: `/api/shares`（`GET`・`HEAD`・`PUT`・`DELETE`）と `/api/shared?id=`（`GET`・`HEAD`、ログインなし）は Go と同じパス・ステータス・エラーのキー・ヘッダーで答えます。スナップショット（`typle_shared_packs.data`）は Go の `EncodePackFile` と同じバイト列で書き、`/api/shared` は保存されたものを読み直して同じバイト列で返します（`workspace/PackFile.kt`。typle-r の `tests/fixtures/pack-file` の写しを `src/test/resources/pack-file` に置いて確かめます）。`packs` を送る保存は、同じトランザクションで保存したパックにない共有を消し、最初の保存（revision 0）は Go と同じ `pg_advisory_xact_lock` を取ります。`packs` を送らない `lists` だけの保存も、保存後に残るパック（どれかのリストが入っている保存済みのパックと、パックのないリストの `pack-<id>`）にない共有を同じトランザクションで消し、`devices` だけの保存は共有に触りません。共有の JSON は `{"id","packId","name","updatedAt"}`（`name` はスナップショットの `pack.name`、なければ `""`）で、`/api/shared` の答えはすべて `X-Robots-Tag: noindex` を付けます。
  - Go との違い: 一度も保存していないアカウント（`typle_workspaces` に行がない）は、Go では最初のパック（`workspace.Initial()`）を共有できますが、Kotlin は最初のワークスペースを作らないので `404 packNotFound` を返します。パックができる前に保存したリスト（`packs` が `NULL`）も、Go と違ってパックに変換しないので共有できず `404 packNotFound` です。保存済みのパックの補正（Go の `Decode` の修復）も、次の難易度の分け方のほかはしないので、ほかの補正が要る保存データでは共有の中身が Go と違うことがあります（アプリが保存するデータでは起きません）。
- 難易度の重なり（typle-r v0.96.2）: `lists` と `packs` を一緒に送る保存で、同じパックのリストの難易度のパス（`levels`、なければ `[name]`）が重なると、Go と同じく `400 levelsConflict` で拒否し、何も保存しません（送られたままの値で確かめます）。`lists` だけの保存は拒否しません。保存済みの重なりは、Go の `separateLevels`（`Decode` の補正の最後）と同じ規則で、後のリストの難易度（なければ名前）に「（2）」「（3）」…を付けて分けます（難易度は 50、名前は 200 の UTF-16 単位に収まるよう元の文字列を切り、2 単位の文字は分けません。名前はそのパックの別のリストが使っていないものにします）。分けるのは、`packs` 列のある行からサーバーが新しいデータを作るところ、つまり共有のスナップショット（`PUT /api/shares`）だけで（`workspace.readSaved`）、`/api/workspace` の GET は Go と同じく保存されたままを返し、`packs` が `NULL` の行は分けません。typle-r の `tests/fixtures/packs` のうち、パックのある行で分けるほかの補正が要らないものの写しを `src/test/resources/packs` に置いて、Go と同じ結果になることを確かめます。
- 難易度は 1 つまで（typle-r v0.97.0）: `lists` と `packs` を一緒に送る保存で、v0.97.0 より前のアプリが送る 2 〜 5 個の難易度は Go と同じく拒否せず（6 個以上は `400 invalidWorkspace`）、重なりも確かめずに、Go の `Decode` と同じく 1 つにまとめ（`collapseLevels`）、同じものを分けた（`separateLevels`）リストを保存して返します（`/api/workspace` の GET も保存されたもの）。まとめ方は、難易度ごとに前後の空白と「・」を除き、空を除いて「・」でつなぎ、50 UTF-16 単位に切って、末尾の空白と「・」をまた除きます（何も残らなければ難易度なし）。保存済みの行は共有のスナップショットを作るところで同じに読みます。`tooManyLevels` は Go の `/api/v1` などだけで、Kotlin にはありません。`distinctLevels`・`levelDuplicate` は Go と同じくなくなりました。
- 公式パックのフォロー（typle-r v0.98.0）: パックの `origin.followed`（`true` か省略。`false` などは Go と同じく `400 invalidWorkspace`）とリストの `origin`（文字列の `listId` を持つオブジェクトのときだけ残し、ほかの形は Go と同じく捨てます）を読み書きします。`lists` と `packs` を一緒に送る保存は、Go の `Decode` と同じく保存の前に公式のカタログに合わせます（`followOfficial`）。v0.98.0 より前の公式パックのコピー（`followed` のない `origin` を持つパックと、`origin` がなく `id` が公式の `catalogId` と同じパック）はリスト・記録ごと消し、フォロー中のパックは公式と同じ説明・カテゴリー・タグ・リスト（名前・難易度・単語）にして、名前・記録・最初のリストの位置を保ちます（カタログにないパックは消します）。カタログは Go が埋め込むのと同じく typle-r の `src/plugins/packs/{samples,countries,commands}.json` の写しを `src/main/resources/packs` に置いて、Go の `OfficialPacks`（`convertLibrary`）と同じに作ります（30 パック・67 リスト）。共有のスナップショット（`PUT /api/shares`）と、`lists` だけの保存のあとで消す共有を決めるところも、`packs` 列のある行を同じく合わせて読みます（`workspace.readSaved`）。`/api/workspace` の GET は Go と同じく保存されたままを返します。typle-r の `tests/fixtures/follow` の写しを `src/test/resources/follow` に置いて、Go と同じ結果になることを確かめます（`packs` が `NULL` の fixture は `convertLibrary` を直接使います。保存済みの行の変換は Kotlin ではしません）。
- 興味（typle-r v0.99.0）: `/api/workspace` の GET・PUT は Go と同じく `interests` 列（jsonb、`NULL` は一度も保存していない）を読み書きします。応答の項目の順は `lists`・`packs`・`devices`・`interests`・`revision`・`accountId` です。PUT は `interests` だけの保存も受け付け（`lists`・`devices` と一緒でも構いません）、Go の `DecodeInterests` と同じく、50 個まで・それぞれ空でなく 50 UTF-16 単位まで・重なりなしの文字列のリストでなければ `400 invalidWorkspace` で拒否します（`null` は空、知らない id はそのまま保存します。`U+0000` と対になっていないサロゲートも拒否します）。確かめる順は Go と同じく `lists`・`packs`・`devices` の後で、難易度の重なり（`levelsConflict`）が先に答えられます。`interests` を送らない保存は保存済みの興味を変えません。読むときは Go と同じく保存済みの興味も確かめ、読めなければエラーにします（`workspace/Interests.kt`）。
- 興味の既定（typle-r v0.101.1）: `interests` 列が `NULL` か行がなければ、Go の `DefaultInterests`（すべての選択肢、22 個）を答えます。保存した `[]` は `[]` のままです。typle-r の `tests/fixtures/default-interests.json` の写しを `src/test/resources/default-interests.json` に置いて、同じ一覧であることを確かめます。
- JSON のオブジェクトのキーは、Go（`encoding/json`）が大文字と小文字を区別せずに照合するのに対し、Kotlin は完全に一致するものだけを読みます。そのため大文字と小文字だけが違うキー（例: `"Category"`）を持つ保存は、Go と Kotlin で受け付けるかどうかが変わることがあります。アプリはそのようなキーを送りません。

## 構成

Go の `server/internal/*` と 1 対 1 に対応します。Go を変えたら、ここも同じように直してください（typle-r の `TYPLE_BACKEND=kotlin npm run test:e2e` で確かめます）。

| Kotlin（`src/main/kotlin/…/server/`） | Go |
|---|---|
| `Main.kt`・`Backend.kt` | `server/cmd/typle-web`・`server/vercel` |
| `web/` | `server/internal/web` |
| `session/` | `server/internal/session` |
| `auth/` | `server/internal/auth`（`OAuth` と `GitHub.User`） |
| `store/` | `server/internal/store`（`ConnString`・`Ping`・`ReadRevision`・`WriteRevision`・`PutShare`・`DeleteShare`・`ListShares`・`SharedPack`） |
| `workspace/` | `server/internal/workspace`（`Decode`・`DecodeInterests`・`CheckInterests`・`EncodeInterests`・`DefaultInterests`・`Encode`・`EncodeWithLabels`・`EncodePacks`・`CheckLimits`・`CheckPacks`・`CheckLibrary`・`EncodePackFile`・`PackFile.Encode`・`DecodePackFile`（共有の読み直しに要る分）・`separateLevels`・`followOfficial`・`OfficialPacks`・`convertLibrary`（カタログを作るため）・`fit`・`ParseTime`・`trimJS`・`trimEndJS`） |
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
