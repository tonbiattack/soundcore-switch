# 対応機種の追加手順

最終更新: 2026-11-20

## 前提: 未検証の機種を有効化してはならない

`ModelCatalog.supportLevels` が唯一の正。`SUPPORTED` 以外では切り替えを無効化する。
未検証の機種を有効化すると「切り替えていないのに成功と表示する」最悪の誤動作になる。

**検証が済むまで `UNKNOWN` のままにする。** 一覧取得だけ確認できた段階でも `UNKNOWN`。

## 1. プロトコルの差分を確認する

機種ごとに次の点が異なり得る。実機で確認する。

| 項目 | 確認方法 | 差分が出やすい箇所 |
|---|---|---|
| ベンダー UUID | 型番の数字部分（A3947 → 3947） | `ModelProfile.modelCode` |
| 一覧取得コマンド | `0B 01` が通るか | `SoundcoreProtocol.Cmd.GET_DEVICES` |
| 分割パケットの形 | `total`/`index` の付け方 | `DeviceListAssembler` |
| 切断コマンド | `0B 81` が通るか | `Cmd.DISCONNECT_DEVICE` |
| 切断の拒否 | 対象によって拒否されないか | `awaitDisconnected` |
| 接続コマンド | `0B 82` が通るか | `Cmd.CONNECT_DEVICE` |
| 応答の有無と実効果 | 応答なしで成功するか | `docs/RESEARCH.md` §1.1 |
| 同時接続の上限 | 2台か | `maxConnections` |

### 応答の有無で成否を判定しない

実機で「応答なしで成功」「応答ありで失敗」の両方を観測している。
**必ず一覧を再取得して状態で確認する。**

## 2. 検証チェックリスト

実機で1つずつ確認する。すべて通るまで `SUPPORTED` にしない。

### 一覧取得

- [ ] 接続先の一覧が取得できる
- [ ] 件数が純正アプリと一致する
- [ ] 接続中/切断中の表示が純正アプリと一致する
- [ ] 分割パケットが欠けたとき「取得不可」になる（不完全な一覧を出さない）

### 切り替え

- [ ] 切断中の端末へ切り替えられる
- [ ] 切り替え後に一覧を再取得すると、対象が接続中になっている
- [ ] 既に接続中の端末を選んでも壊れない

### 接続上限

- [ ] 2台接続中に3台目へ切り替えると、1台が自動で切断される
- [ ] 操作元の端末は切断されない
- [ ] 保護した端末は切断されない
- [ ] 切断が拒否される場合、別の候補を試す
- [ ] 1台も切れなければ接続コマンドを送らずに失敗する

### 異常系

- [ ] Bluetooth オフで理由が表示される
- [ ] 権限不足で理由が表示される
- [ ] イヤホンの電源が切れているとき理由が表示される
- [ ] タイムアウトしても画面が更新不能にならない
- [ ] 失敗時に Bluetooth の状態が表示される

### ログ

- [ ] ログに MAC アドレスが出ていない
- [ ] ログに端末名が出ていない
- [ ] ログに生の応答 body が出ていない

## 3. コードの更新

次の4つを**同時に**更新する。1つでも漏れると不整合になる。

### 3.1 `ModelProfiles` に追加

`core/src/main/kotlin/.../domain/model/ModelProfile.kt`

```kotlin
val NEW_MODEL = ModelProfile(
    modelId = "new-model",
    modelCode = "1234",          // 型番の数字部分
    displayName = "New Model",
    supportLevel = SupportLevel.UNKNOWN,  // 検証が済むまで UNKNOWN
    verifiedOn = null,
    notes = "",
)
```

`ALL` に加える。

### 3.2 `ModelCatalog` に追加

`core/src/main/kotlin/.../domain/model/ModelCatalog.kt`

```kotlin
val NEW_MODEL = EarbudsModel(id = "new-model", displayName = "New Model")
```

`KNOWN_MODELS` と `supportLevels` に加える。

### 3.3 アダプタを登録

`app/.../platform/DefaultMultipointAdapterRegistry.kt` と
`desktop/.../platform/DesktopContainer.kt` の両方。

**片方だけ登録すると、そのプラットフォームでだけ動かない。**
実際に Windows 側の登録漏れで A40 が使えなかった事例がある。

### 3.4 `docs/SUPPORT_MATRIX.md` を更新

検証結果を記録する。`SUPPORTED` にしたら検証日も書く。

## 4. 検証を通す

```powershell
.\scripts\gradle.ps1 :core:test          # 整合性テストを含む
.\scripts\gradle.ps1 :desktop:test
.\scripts\gradle.ps1 :app:assembleDebug
.\scripts\gradle.ps1 :app:lintDebug
```

`ModelProfileConsistencyTest` が次を検証する。

- `ModelProfiles` と `ModelCatalog` の対応レベルが一致する
- 既知機種がすべて `ModelProfiles` にある
- 機種 ID・機種コードが重複しない
- 機種コードが4桁の数字である
- `SUPPORTED` の機種は検証日を持つ
- 未検証の機種は切り替えを許可しない

## 5. 実機で最終確認

`SUPPORTED` に上げたら、実際に切り替えて成功することを確認する。
**確認していない項目を「完了」と断定しない。**

## 6. テスト用 fixture

実機のパケットをそのまま使うと MAC・端末名が残る。
テストに使うときは匿名化する。

```kotlin
// 実機のフレームを模した fixture。MAC は架空の値にする。
private fun deviceFrame(mac: String = "AA:BB:CC:DD:EE:01", name: String = "端末A") = ...
```

既存の `SoundcoreProtocolTest` は実機で採取したフレームを使っているが、
MAC は架空の値に置き換えている。

## 参考

- [RESEARCH.md](./RESEARCH.md) — プロトコルの正
- [SUPPORT_MATRIX.md](./SUPPORT_MATRIX.md) — 機種対応の正
- [VERIFICATION_A40.md](./VERIFICATION_A40.md) — A40 で分かった注意点
