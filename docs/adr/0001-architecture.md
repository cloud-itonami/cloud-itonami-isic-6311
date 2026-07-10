# ADR-0001: cloud-itonami-isic-6311 — MarketData-LLM を封じ込めた知能ノードとするマルチアセット市場データ・アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-8291`(Dossier-LLM を DisclosureGovernor で封じ込
  める構図の直接の手本)、`cloud-itonami-6310`(旧 `gftd-talent-actor`、HR-LLM
  を PolicyGovernor で封じ込めるパターンの原型)、robotaxi-actor ADR-0001
  (研究モデルを信頼境界に封じ込める actor 設計)、langgraph-clj ADR-0001
  (Pregel superstep + interrupt + Datomic checkpoint)
- 文脈: com-junkawasaki/root superproject ADR-2607111500(本 ADR の対、経緯・
  スコープ決定の全文はそちら)

## 課題

「実世界の株価・為替・コモディティ・暗号資産・不動産などの market 情報を
収集・保持・更新する repo/actor は設計されているか」が問われ、既存 actor
には該当するものが無いと判明した(`kotoba-lang/securities` は明示的に
「no real market data — operator supplies own licensed feed」とスコープ
除外しており、compat-catalog の `com-alpaca`/`com-coinbase` 等はスキーマ
のみのモックスタブで実装が無かった)。

`kotoba-lang/industry` registry の未着手 `:spec` スロットから、ISIC Rev.4
6311「Data processing, hosting and related activities」を選定した。この
コードはデータ処理・ホスティング業一般を指すが、既存の実装済み隣接コード
(6611 取引所運営、6612 証券/コモディティ仲介業)がいずれも「市場運営」
「仲介」であって「市場データそのものの収集・保持・配信」ではないため、
`cloud-itonami-isic-4610`(6619→Card Transaction Processing の narrowing と
同型)の前例に倣い、6311 を**マルチアセット市場データ集約・ホスティング
サービス**へ narrow した(単純な relabeling ではない)。

一方、フィードtickの正規化・OHLC系列の集計・開示列の提案には LLM が有効だが、
**LLM に価格の取込・公開・訂正確定を直接行わせるのは危険**である(出典なき
価格の断定=汚染データ伝播、桁間違い/誤発注価格のそのままの流通、取引停止中
銘柄への自動配信=市場混乱リスク、契約 tier を超えた開示)。したがって設計
課題は「LLM で市場データを回す」ことではなく、**「LLM を信頼境界の内側に
封じ込め、許容乖離・出典・ライセンス・停止状態・人間レビューの層をどう
被せるか」**である。これは `cloud-itonami-isic-8291` が Dossier-LLM を
DisclosureGovernor で封じ込めた構図の、価格データドメインへの写像である。

## 決定

### 1. MarketData-LLM は最下層の1ノードに封じ込め、直接取込/公開/訂正確定させない

OperationActor 内で MarketData-LLM は *proposal*(価格取込案・系列集計案・
開示列案・訂正解決案 ＋ 出典/根拠トレース)のみを返す**助言者**として扱う。
出力は必ず独立した `MarketDataGovernor` を通してから台帳に commit する。
**単一の不変条件**:

> **MarketData-LLM は、MarketDataGovernor が拒否する価格の取込・公開・
> 訂正確定を決して行わない。**

### 2. MarketDataGovernor は7チェック(HARD4 + SOFT3)

`cloud-itonami-isic-8291` の DisclosureGovernor(RBAC + 3 HARD + 3 SOFT)を
写像しつつ、このドメイン固有の HARD チェックを1つ新設した:

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | **tolerance-gate**(新規、market-data 固有) | HARD | `:quote/ingest` の新価格が直近既知良好値から `tolerance-pct`(15%)を超えて乖離したら拒否。確信度に関わらず — 桁間違い/フォーマット崩れの構造的防御。プリンシパル/エージェント型 trading actor 系列や dossier actor には存在しない、価格データという業態固有のリスク面 |
| 3 | source-provenance-gate | HARD | 出典クラスが `marketdata.facts/allowed-source-classes` に無ければ拒否。`:licensed-operator-feed` は加えてアクティブかつアセットクラス対応の `feed-license` を要求 |
| 4 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または列が tier を超えたら拒否 |
| 5 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 6 | **halted-instrument gate**(dossierのhigh-stakes gateの写像) | SOFT | 対象銘柄が取引停止/サーキットブレーカー中 → 必ず人間承認 |
| 7 | correction-request | SOFT(無条件) | データ品質紛争は確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**意図的に無い項目**: 与信/カウンターパーティチェックに相当するものは存在
しない — この actor は価格データの収集・保持・配信のみを行い、注文執行・
カストディ・約定を一切含まないため(trading 系 actor からの安易な流用を
避けた証跡として ADR に明記)。

### 3. Phase 0→3 + 恒久人間ゲート

`dossier`/`talent` と同型: `:disclosure/query` のみ phase 0 から governor
ゲート付きで許可、`:quote/ingest`/`:series/derive` は phase 3 で
governor-clean かつ高確信なら auto-commit 可能、`:correction/request` は
どの phase の `:auto` 集合にも入らない構造的恒久ゲート。

### 4. R0 の正直なスコープ(捏造禁止)

`dossier` の「6つの実在公開一次情報源のみ」に倣い、出典カタログ
(`src/marketdata/facts.cljc`)は実在する3つの自由・公式参照ソース(ECB
euro FX reference rates、US EIA Open Data、FRED Case-Shiller HPI)+ 1つの
構造的クラス `:licensed-operator-feed`(株式/暗号資産/大半のコモディティの
生きた気配値は、`kotoba-lang/securities` と同じ境界により、operator が
自前のライセンス済みフィードを登録して初めて取込可能— 無料の公式ソースを
偽装しない)。`facts/coverage` が常に正直に現状を報告する。

### 5. Robotics premise: false

配送・実物資産の移動を伴わない、価格データの収集・保持・配信のみのデジタル
サービスであり、actor の境界の外に物理的な作動は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 6311 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-J6311` URL)から実装へ昇格(`M6910`・`isic-8291`・
  `isic-4690`・`isic-4610` に続く5件目)。
- (+) `kotoba-lang/securities`(実データ・カストディ統合なし、operator が
  ライセンス済みフィードを供給する境界)が前提としていた「市場データ層」が
  初めて実装され、`:market-data` capability として他 blueprint(securities
  含む)から wholesale 消費できる形になった。
- (+) tolerance-gate という、他の cloud-itonami actor に存在しない
  market-data 固有の HARD チェックを新設し、単純な relabeling ではなく
  業態の構造的差異(桁間違い/fat-finger 耐性)を反映したことを ADR に明記
  した。
- (+) `clojure -M:dev:test` / `clojure -M:lint` をローカルで実行し合格を
  確認済み(詳細は superproject ADR の Consequences 節)。
- (-) R0 の自由公式ソースは3種のみ(FX/コモディティ/不動産指数の一部)。
  株式・暗号資産・大半のコモディティは operator の feed-license 登録が
  必須で、この actor 単体では取込できない。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の
  取引所/ベンダーとのフィード契約は operator の責任範囲。

## 代替案と不採用理由

- **6611(取引所運営)/6612(証券・コモディティ仲介業)のスロットを流用**:
  両者は既に実装済みで、かつ業態が「市場の運営」「仲介」であって「データ
  そのものの収集・保持・配信」ではない。市場データ集約は独立した業態
  (D&B が corporate intelligence と別業態であるのと同型)として 6311 を
  選ぶのが正確。
- **LLM に取込・公開権限を直接付与(エージェント自律)**: 速いが、出典なき
  断定・桁間違い価格の流通・停止銘柄への誤配信を構造的に防げない。単一
  不変条件(決定1)に反する。
- **tolerance-gate を SOFT(escalate)にとどめる**: 桁間違い/フォーマット
  崩れは確信度と無関係に起きるため、SOFT では低確信フィルタをすり抜ける
  高確信の誤値を止められない。HARD が必須と判断した。
