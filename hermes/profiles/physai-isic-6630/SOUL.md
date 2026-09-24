# physai-isic-6630 — ファンド運用（ISIC 6630）の運用委任書・目論見書の保管ロボット の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-6630`、ISIC 6630 ファンド運用業）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 文書保管ロボットが、運用委任契約書と目論見書の物理的な保管を担う（FundManagementGovernor の下）。印刷された目論見書の箱を搬入口から保管室へ運び、移動棚の最上段から委任書類を取り出し、耐火ファイルキャビネットで紙を炭化温度より下に保つ。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:prospectus-boxes-dock-to-custody` | transport | 印刷された目論見書のカートンを搬入口から保管室へ運ぶ（70 m） | 1 区間の所要時間 | 90 s（estimate） |
| `:mandate-file-from-top-shelf` | manipulator | 移動棚の最上段から委任書類（束）をスキャン台へ下ろす | 肩関節ピークトルク | 60 N·m（estimate） |
| `:fire-rated-file-cabinet-30min` | thermal | 耐火ファイルキャビネットの壁（断熱充填材）を 30 分の標準火災 + 冷却にさらし、内面温度を見る | 内面ピーク温度 | 177 °C（UL 72 Class 350。曝露は ISO 834-1 で近似、充填材物性は estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/fundmgmt/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。test/ の既存 test も kbb の runner で一緒に走る）。
`:physai-test` は test/ のうち kbb で読めない 2 namespace を外している（deps.edn のコメント）: `fundmgmt.portable-cljs-test-runner`（cljs.main の入口）と `wasm.fee-accrual-test`（chicory の JVM wasm runtime、設計上 JVM 専用）。全体は `:test`（fleet の JVM gate）。現在 kbb で 63 test / 590 assertion。

## 測って分かったこと・限界（成長の第一候補）

1. **目論見書の搬入**: 積荷 15〜90 kg で 71.63 s、150 kg で 71.69 s（ここから drive-limited）、250 kg で 72.58 s。限界 90 s を超えるのは積荷 **約 571 kg** —— 加速度上限 0.5 m/s² と最高速度 1.0 m/s が効き、積荷はほぼ効かない。エネルギーは 1058 J → 4374 J。
2. **最上段からの取り出し**: 肩トルクは積荷 0.5 kg で 33.5 N·m、2 kg で 45.3、4 kg で 61.1、6 kg で 76.9 N·m。限界 60 N·m に達するのは **3.87 kg**。書類の束を一度に下ろす量はこれで決まる。関節仕事は負（−37 J 〜 −78 J、下ろす動作）。
3. **耐火キャビネット（30 分）**: 内面ピーク温度は充填材 15 mm で 465 °C、25 mm で 304 °C、35 mm で 202 °C、45 mm で 141 °C、60 mm で 92 °C。177 °C を下回る厚さは **約 38.4 mm**。
   ピークは加熱終了（1800 s）の後（35 mm で 2416 s、60 mm で 3903 s）。冷却は炉温 20 °C への即時切替で近似しており UL 72 の炉内冷却より楽観的。
4. **estimate のままの値（置き換え候補）**:
   - 区間所要時間 90 s → 搬入の受け渡し手順
   - 肩トルク上限 60 N·m → 協働ロボットのメーカー仕様書
   - 耐火充填材の物性 → 実際の耐火キャビネットの材料データ。UL 72 の ASTM E119 曲線は solver に無い（solver 側の成長候補）
   - AMR の駆動力 140 N・転がり抵抗係数・寸法、アームの寸法・質量

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-6630 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-6630 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
