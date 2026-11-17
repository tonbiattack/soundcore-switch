# 操作数・待ち時間の計測

対応 Issue: [#5 最小操作・高速化・実機テストとAPK配布](https://github.com/tonbiattack/soundcore-switch/issues/5)

最終更新: 2026-11-17

> このドキュメントは**計測値と異常系テストの記録**である。
> 計測方法・実測値・テストの網羅状況を扱う。
> 実機で見つけた不具合そのものは [VERIFICATION_ANDROID.md](./VERIFICATION_ANDROID.md) /
> [VERIFICATION_A40.md](./VERIFICATION_A40.md) を参照。

## 1. 計測の目的

仕様書 §5 の理想経路「起動 → 接続先を選ぶ → 完了」が、純正アプリの
「起動 → イヤホン読込 → 設定画面 → 接続先変更」より少ない操作と短い待ち時間で
達成できているかを確認する。

## 2. 計測方法

### 2.1 タップ数の数え方

「画面に触れる回数」を数える。スクロールは含めない。アプリ起動のための
ランチャータップは両者とも 1 回なので、比較では除外する。

### 2.2 待ち時間の測り方

`adb shell am start -W` の `TotalTime` を起動時間として使う。
画面が操作可能になってから接続先が表示されるまでの時間は、画面録画から
フレームを確認して測る（`adb shell screenrecord`）。

```powershell
# 起動時間の計測（5回測って中央値を使う）
1..5 | ForEach-Object {
    adb -s <serial> shell am force-stop com.tonbiattack.soundcoreswitch
    adb -s <serial> shell am start -W -n com.tonbiattack.soundcoreswitch/.MainActivity |
        Select-String "TotalTime"
}
```

## 3. 計測結果

### 3.1 起動時間（実測）

Android エミュレータ（API 36）での `am start -W` の `TotalTime`:

| 条件 | 実測値 |
|---|---|
| コールドスタート（権限なし） | 3,214 ms |
| コールドスタート（権限あり） | 2,724 ms |
| コールドスタート（Bluetooth オフ） | 1,512 ms |
| コールドスタート（ダークモード） | 856 ms |
| コールドスタート（再インストール後） | 2,238 ms |

> エミュレータは実機より遅い。実機での計測は未実施。

### 3.2 タップ数

| 操作経路 | タップ数 | 備考 |
|---|---|---|
| 純正アプリで接続先を変更 | 未計測 | 起動→イヤホン選択→設定→接続先→（確認） |
| 本アプリで接続先を変更 | **2** | 起動→接続先タップ（対応機種のみ） |
| 本アプリで状態確認のみ | **0** | 起動のみ |

本アプリは画面遷移も確認ダイアログも無いため、接続先のタップ1回で切り替えが完了する。

## 4. 高速化のための実装（計測前から実施済み）

仕様書 F-06 / §8「起動時はブロッキング通信なしでUI表示」に対応する。

| 施策 | 実装 | 効果 |
|---|---|---|
| キャッシュからの即描画 | `TargetCache` + `AppViewModel.loadTargets` | 前回の接続先を通信前に表示 |
| 非同期更新 | `refresh()` をコルーチンで実行 | UI をブロックしない |
| 画面遷移なし | 1画面のみ（`AppScreen` / `DesktopApp`） | 遷移待ちがゼロ |
| 確認ダイアログなし | タップ即実行 | 確認のタップが不要 |
| ローディング全画面表示なし | 部分的なインジケータのみ | 体感待ち時間を短縮 |
| ソケットの都度開閉 | `SoundcoreRfcommTransport` | 状態を持ち越さず切断検知が単純 |

## 5. 異常系テスト

仕様書 F-05 の5区分を、ユニットテストで検証済み。

| 状況 | 期待する挙動 | 検証 |
|---|---|---|
| Bluetooth オフ | 検出せず「Bluetoothがオフです」+ 対処方法 | ✅ `AppViewModelTest.Bluetoothオフなら検出せず理由を表示する` |
| 権限不足 | 検出せず「権限が必要です」+ 許可ボタン | ✅ `AppViewModelTest.権限不足なら検出せず理由を表示する` |
| 権限の取り消し | クラッシュせず権限不足として扱う | ✅ 同上（`onPermissionResult(false)`） |
| イヤホン未接続 | 切り替えを無効化 | ✅ `AppViewModelTest.イヤホン未接続では切り替えを無効にする` |
| タイムアウト | 失敗表示 + 再試行可能 | ✅ `AppViewModelTest.タイムアウト後は再試行できる` |
| 非対応機種 | 切り替えを無効化し理由を表示 | ✅ `AppViewModelTest.未対応機種では切り替えを受け付けない` |
| 通信失敗（取得） | キャッシュがあれば残す。無ければ理由表示 | ✅ `AppViewModelTest.取得に失敗してもキャッシュ表示は消さない` |
| 二重送信 | 2回目以降を無視 | ✅ `AppViewModelTest.送信中は二重送信を受け付けない` |
| 切り替え未確認 | 成功と表示しない | ✅ `SwitchTargetUseCaseTest.要求は成功したが再取得で切断中のままなら成功にしない` |
| 接続上限に達している | 先に1台切断してから接続 | ✅ `SoundcoreMultipointAdapterTest.接続上限に達しているときは先に1台切断してから接続する` |
| 対象が一覧に無い | 対象不在として失敗 | ✅ `SoundcoreMultipointAdapterTest.対象が一覧に無ければ対象不在として失敗する` |
| プロトコルの応答が壊れている | フレームを捨てて再同期 | ✅ `SoundcoreProtocolTest.チェックサムが合わないフレームは捨てる` |

## 6. ログの安全性確認

仕様書 §6「通信ログには秘密情報を記録しない」に対応する。

| 確認項目 | 状態 |
|---|---|
| Bluetooth アドレスをログに出していない | ✅ 件数のみ出力。例外メッセージにも含めない |
| 例外メッセージをそのまま画面に出していない | ✅ `FailureReason` から文言を組み立て |
| `INTERNET` 権限を要求していない | ✅ マニフェストに無し（`aapt2 dump permissions` で確認済み） |
| 外部送信処理が無い | ✅ ネットワークコード無し |
| ペアリング鍵を扱っていない | ✅ OS のペアリングをそのまま利用 |

確認コマンド:

```powershell
# マニフェストに INTERNET 権限が無いこと
Select-String -Path app\src\main\AndroidManifest.xml -Pattern "INTERNET"

# 実際の APK に INTERNET が含まれないこと
aapt2 dump permissions app\build\outputs\apk\debug\app-debug.apk

# ソースにネットワーク API が無いこと
Get-ChildItem -Recurse app\src\main\java,core\src\main\kotlin,desktop\src\main\kotlin -Filter *.kt |
    Select-String -Pattern "HttpURLConnection|OkHttp|URL\(|Socket\("
```

## 7. 実機テスト

### 7.1 実施済み（Windows 11 + Liberty 4 NC）

| 項目 | 結果 |
|---|---|
| アプリ起動・画面表示 | ✅ 正常 |
| イヤホン検出（Win32 API） | ✅ Space A40 / Liberty 4 NC ×2 を検出 |
| 接続先一覧の取得（RFCOMM） | ✅ 7台の登録端末と接続状態を取得 |
| 切り替えコマンドの送信 | ✅ 応答あり |
| イヤホン未接続時の挙動 | ✅ クラッシュせず「取得できませんでした」+ 再試行 |

### 7.2 実施済み（Android 実機 OPPO CPH2699 / Android 16）

| 項目 | 結果 |
|---|---|
| アプリ起動・画面表示 | ✅ 正常（クラッシュなし） |
| 権限なしで起動 | ✅ クラッシュせず案内が出る |
| 権限を許可 | ✅ 一覧が更新される |
| イヤホン検出 | ✅ 5台の Soundcore 製品を検出 |
| 接続先一覧の取得 | ✅ 7台の登録端末と接続状態を取得 |
| 接続先の切り替え | ✅ 成功（DELLTOWER へ切り替え） |
| 失敗時の表示 | ✅ 成功と偽装しない |
| ダークモード | ✅ 正常 |
| 画面回転 | ✅ 状態が壊れない |

実機検証で発見・修正した不具合は [VERIFICATION_ANDROID.md](./VERIFICATION_ANDROID.md) を参照。

### 7.3 実施済み（Android エミュレータ API 36）

| 項目 | 結果 |
|---|---|
| 起動・画面表示 | ✅ 正常 |
| 権限なしで起動 | ✅ クラッシュせず案内が出る |
| Bluetooth オフで起動 | ✅ クラッシュせず案内が出る |
| ダークモード | ✅ 正常 |
| 画面回転 | ✅ 状態が壊れない |
| イヤホン未検出 | ✅ 「見つかりません」+ 再検出ボタン |

### 7.4 未実施

- [ ] Space A40 でのプロトコル動作
- [ ] 3台以上の同時接続に対応する機種での挙動
- [ ] 他メーカー端末（Samsung / Pixel 等）でのプロファイル API の挙動
- [ ] 実機での起動時間・タップ数の計測（エミュレータ値のみ）

