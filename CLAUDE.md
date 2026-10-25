# typle-kotlin-backend

Typle のバックエンドの Kotlin（Ktor）版です。本番と既定のバックエンドは [typle-r](https://github.com/Sparklingstadt/typle-r) の Go（`server/`）で、これは手元の開発と E2E で Go の代わりに使う並行実装です。構成と確認の手順は README.md を読んでください。

- Go の `server/internal/*` と 1 対 1 に対応します。typle-r の Go を変えたら、ここも同じように直します。
- 変更したら `./gradlew test` と、typle-r の手元で `TYPLE_BACKEND=kotlin npm run test:e2e` を実行します（README の「確認」）。

## 出力の言語

出力は必ず日本語で行うこと。
