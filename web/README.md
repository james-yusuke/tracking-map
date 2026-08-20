# Family Orbit Web

保護者向けの閲覧専用Next.jsダッシュボードです。地図、現在地、30日履歴、安全エリア状態、電池、通知履歴を表示します。更新操作はUIに設けず、Go APIもWebセッションからの更新を拒否します。

```sh
bun install
bun run dev
```

環境変数はルートの `.env.example` を参照してください。デモデータは `NEXT_PUBLIC_DEMO_MODE=true` の場合だけ利用できます。本番ではMapTilerキーとHTTPSのAPI／WebSocket URLを設定します。
