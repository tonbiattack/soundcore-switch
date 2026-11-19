# 最新の状態が反映されない問題の調査

調査日: 2026-11-19

## 症状

「最新の状況が反映されていないことがある」との指摘を受けて調査した。

## 結論: 3つの不具合があった

| # | 不具合 | 影響 | 深刻度 |
|---|---|---|---|
| 1 | 保護設定がアダプタに伝わらない | **保護機能が実質無効**。保護しても自動切断される | 高 |
| 2 | 画面復帰時に状態を取り直さない | バックグラウンドから戻ると古い一覧のまま | 高 |
| 3 | 設定復元の多重実行 | 復帰のたびに復元すると変更が巻き戻る恐れ | 中 |

## 不具合1: 保護設定がアダプタに伝わらない

### 原因

`ProtectionCache` を作ったが、`refresh()` も `update()` も**一度も呼ばれていなかった**。

```
ProtectionCache の利用箇所:
  ServiceLocator.kt:118  val protectionCache = ProtectionCache(settings)   ← 生成のみ
  ServiceLocator.kt:124  protectionProvider = { protectionCache.current() } ← 常に空を返す
```

ViewModel は `settings.setProtectionSettings()` で DataStore に直接保存していたが、
アダプタが参照する `ProtectionCache` には反映していなかった。
そのため `protectionCache.current()` は常に既定値（保護なし）を返していた。

### 影響

画面で 🔒 を押しても、接続上限時の自動切断で**保護した端末が切られる**。
「保護したのに切られた」という最悪の裏切りになる。

### 修正

- `AppViewModel` に `protectionCache` を注入
- `toggleProtection` でキャッシュを先に更新してから保存
- `restorePreferences` でもキャッシュを復元内容に合わせる
- Android / Windows の両方で同じキャッシュを共有

### 検証

`AppViewModelFreshnessTest > 保護を切り替えるとアダプタに伝わる`

修正前は失敗（保護が伝わらない）、修正後は成功。

## 不具合2: 画面復帰時に状態を取り直さない

### 原因

```kotlin
LaunchedEffect(Unit) { viewModel.start() }
```

`LaunchedEffect(Unit)` は**初回しか実行されない**。
アプリをバックグラウンドに送って戻しても、`start()` は呼ばれない。

### 影響

- 純正アプリで接続先を変えても、戻ったときに古い一覧が残る
- イヤホンを再接続しても「切断中」のまま
- 長時間開いたままにすると、表示が実態とずれていく

### 修正

`repeatOnLifecycle(Lifecycle.State.RESUMED)` で、前面に戻るたびに `start()` を呼ぶ。

```kotlin
LaunchedEffect(lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        viewModel.start()
    }
}
```

### 検証

`AppViewModelFreshnessTest > 画面復帰で状態を取り直す`

## 不具合3: 設定復元の多重実行

### 原因

不具合2の修正により `start()` が毎回呼ばれるようになる。
`start()` は毎回 `restorePreferences()` を実行していた。

### 影響

画面で保護を切り替えた直後に復帰すると、保存が完了する前に復元が走り、
**変更が保存前の値に戻る**恐れがある。

### 修正

`preferencesRestored` フラグを追加し、復元は初回だけにする。

### 検証

`AppViewModelFreshnessTest > 画面復帰で設定を復元し直さない`

## 調査したが問題なかったもの

| 項目 | 確認結果 |
|---|---|
| 切り替え後の一覧更新 | `loadTargets` が走り、実状態に更新される（テストで確認） |
| 取得の競合（古い取得が後から完了） | 順序が保たれ、上書きされない（4シナリオで確認） |
| 更新の重複実行 | `refreshJob` で抑止されている |
| キャッシュの読み書き | 取得成功時のみ書き込み、`fromCache` も正しく設定 |
| トレイと画面の状態共有 | 同じ ViewModel を共有しており一致する |
| お気に入りの永続化 | ID ベースで保存・復元される |

## 追加したテスト

| テスト | 検証内容 |
|---|---|
| `AppViewModelFreshnessTest`（7件） | 切り替え後の反映、保護の伝播、復元、画面復帰 |
| `AppViewModelRaceTest`（4件） | 取得の競合、更新の重複抑止 |

## 未確認点

- 実機での画面復帰時の更新（`repeatOnLifecycle` の挙動）
- 実機での保護設定の永続化
- Windows でのウィンドウ再表示時の更新

## 教訓

**「作ったが配線していない」は検出しにくい。**
`ProtectionCache` は単体では正しく動くが、呼ばれなければ意味がない。
今回のように「生成した依存が実際に使われているか」をテストで確認する必要がある。
