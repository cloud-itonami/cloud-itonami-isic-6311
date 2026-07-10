# Market-Data Actor Design — MarketData-LLM as a contained intelligence node

Refinitiv Eikon / Bloomberg Terminal-feed / ICE Data Services 級の
マルチアセット market-data サービスを、collect-hold-serve(内部保持・契約者
限定開示)の運用で、SaaS課金に依存せず OSS の actor として自前運用するための
設計。`cloud-itonami-isic-8291`(Dossier-LLM を DisclosureGovernor で封じ込め
た構図)を、価格/系列データのドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか、そしてなぜスコープを絞るのか

フィードtickの正規化・OHLC系列の集計・開示列の提案は LLM で加速できる。
しかし LLM は次の理由で**取込・公開・訂正確定の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 出典なしに価格を「提案」で確定 | フィード欠落/汚染データの伝播 |
| 許容乖離を超えた価格(桁間違い等)をそのまま通す | 誤発注・下流システムの誤評価 |
| 取引停止/サーキットブレーカー中の銘柄へ高確信のまま自動配信 | 市場混乱・誤情報の拡散 |
| 契約 tier を超えた列を開示 | 過剰開示・契約違反 |

したがって設計課題は「LLM で市場データを回す」ことではなく、**「LLM を
信頼境界の内側に封じ込め、許容乖離・出典・ライセンス・停止状態・人間レビュー
の層をどう被せるか」**である。オーナーの確認により、対象は**価格データの
収集・保持・更新・開示のみ**(注文執行・カストディ・約定は一切含まない)に
絞られ、`kotoba-lang/securities` の「実データ・カストディ統合なし、operator
が自前のライセンス済みフィードを供給する」境界をそのまま踏襲する。

## 2. アクター・トポロジ(監督ツリー)

```
MarketDataSystem (root supervisor)
│
├── IngestActor ……… フィードtickの正規化・取込(:quote/ingest)
├── SeriesActor ……… 既存quoteからのOHLC系列集計投影(:series/derive)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; MarketData-LLM 封じ込め ★
│     ├── MarketData-LLM (sealed)  proposal only(src/marketdata/llm.cljc)
│     ├── MarketDataGovernor       INDEPENDENT ゲート(src/marketdata/policy.cljc)
│     ├── Committer                SSoT/台帳への書き込み(src/marketdata/store.cljc)
│     └── Recorder                  監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(取引停止銘柄への配信・訂正申立ての interrupt を受ける)
└── DisclosureActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **MarketData-LLM は最下層ノードで、台帳・開示経路に直接触れない。** 出力は
   常に MarketDataGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(取込/開示しない)**
   に倒す。robotaxi の MRC(安全停止)に相当する既定。
3. **すべてが台帳に積まれる。** 「誰が・何を・どの契約/出典で取込/開示したか」
   は監査台帳への Datalog クエリ — 監査・データ品質紛争が同一ファクトログから
   出る。

## 3. OperationActor 内部(MarketData-LLM ラッパー)

`src/marketdata/operation.cljc` の langgraph-clj StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

チャネル: `:request :context :proposal :verdict :disposition :record :approval :audit`

- **`:context` は外部注入**(`{:actor-id .. :actor-role .. :tenant .. :phase ..}`)。
  MarketData-LLM はこれを持たない。
- **`:govern` は MarketData-LLM と別系統**(許容乖離表 + 出典クラス表 + 契約
  tier 表 + 停止状態チェック)。LLM 提案を*拒否*して hold に substitute できる。
- **`interrupt-before #{:request-approval}`** で実際の人間レビューへ。
  レビュアーは resume 時に `{:approval {:status :approved}}` を注入する。

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`marketdata.store/Store` プロトコル): `MemStore`(既定)/
  `DatomicStore`(`langchain.db` = Datomic-API 互換 EAV)。両者は同一契約
  テストで等価性を保証。
- **Advisor**(`marketdata.llm/Advisor` プロトコル): `mock-advisor`(既定)/
  `llm-advisor`(`langchain.model` の ChatModel)。応答破損時は confidence 0
  の noop に落ち、LLM 不調が auto-commit/公開にならない。
- **Phase**(`marketdata.phase`、context の `:phase 0..3`): 段階導入。read-only
  → assisted → supervised-auto。governor より保守的にしか働かない。
  **`:correction/request` はどの phase の `:auto` にも入らない**(恒久ゲート)。

## 4. MarketDataGovernor(独立検閲層)

`src/marketdata/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate に
判定する。

```clojure
(policy/check request context proposal store)
;; => {:ok? bool :violations [..] :confidence c :escalate? bool :halted? bool :correction? bool}
```

判定の優先順位(上が強い、HARD は人間承認でも上書き不可):

1. **RBAC** — `permissions` 表で `actor-role × operation` を引く。
2. **tolerance-gate** — `:quote/ingest` の新価格が直近の既知良好値に対し
   `tolerance-pct`(15%)を超えて乖離したら HARD violation(確信度に関わらず
   拒否 — 桁間違い/フォーマット崩れの構造的防御)。
3. **source-provenance-gate** — `:quote/ingest`/`:series/derive` の
   `:source` が `marketdata.facts/allowed-source-classes` に無ければ HARD
   violation。`:licensed-operator-feed` は加えて `:license-id` が
   store 上のアクティブかつ当該アセットクラス対応の `feed-license` に
   解決しなければ同じく HARD violation。
4. **licensed-disclosure** — `:disclosure/query` は Store 登録済みの有効な
   契約(tenant×tier)を要求し、提案列が契約 tier を超えたら HARD violation。
5. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
6. **halted-instrument gate** — 対象銘柄が `:status :halted` または
   `:circuit-breaker? true` → 必ず人間承認(soft)。
7. **correction-request** — `:correction/request` は常に escalate(soft だが
   confidence に関わらず無条件)。

## 5. SSoT と監査台帳

`src/marketdata/store.cljc`。dev は in-mem の EDN 事実層(本番は Datomic)。

- **entities**: `instruments`(asset-class=equity|fx|commodity|crypto|
  real-estate-index) `quotes`(最新価格) `series`(派生系列) `feed-licenses`
  (取込ライセンス) `contracts`(subscriber licensing)。
- **commit-record!**: 操作結果を SSoT に反映(`:disclosure-serve` は SSoT
  変更なし — 台帳のみ)。
- **append-ledger!**: 全 commit/reject/開示を**不変台帳**に積む。

「誰が・何を・どの契約/出典で取込/開示したか」を台帳の述語で問えることが、
この業態の監査要件(データ品質紛争の追跡含む)そのもの。

## 6. 開示(governed read)

`src/marketdata/report.cljc`。`render-quote` は MarketDataGovernor が承認
した列のみを出力する。列ポリシーはコードで固定される。

## 7. デモ(`clojure -M:dev:run`)

`src/marketdata/sim.cljc` が6操作を actor に通す(§sim.cljc docstring 参照):
正当なECB参照レート更新 → commit、出典なしtick → hold、tier超過/未契約の開示
→ hold、取引停止銘柄への取込 → 人間承認 → commit、データ品質訂正申立て →
常に人間承認 → commit、許容乖離を大幅超過した価格 → hold。

## 8. テスト(`clojure -M:dev:test`)

`test/marketdata/policy_contract_test.clj` が**ガバナンス契約を実行可能**
にする。`test/marketdata/phase_test.clj` が段階導入と「訂正は恒久的に人間
専用」を保証。`test/marketdata/facts_test.clj` が出典カタログ自体の正直さ
(捏造禁止)を保証。

## 9. 実装と業態の対応(Bloomberg/Refinitiv/ICE → market-data actor)

| 実在業態の機能 | market-data actor での実体 |
|---|---|
| 銘柄マスタ | `store` instruments + `:asset-class`/`:status` |
| リアルタイム/参照レート | `store` quotes + `:quote/ingest` |
| OHLC/ヒストリカル系列 | `store` series + `:series/derive` |
| フィード・ライセンス管理 | `store` feed-licenses + source-provenance-gate |
| 取引停止/サーキットブレーカー連動の配信制御 | halted-instrument gate |
| 誤発注/桁間違いの検知 | tolerance-gate |
| データ品質紛争(erroneous print訂正) | `:correction/request`(恒久 human-only) |
| アクセス権限・契約 | MarketDataGovernor RBAC 表 + `contracts` |
| (SaaS/従来ベンダーと同型)監査台帳 | `store` append-only ledger |
| データ主権 | SSoT = 自分の Datomic |
