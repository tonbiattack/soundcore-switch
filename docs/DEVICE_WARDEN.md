# Device Warden の運用

Android 実機・エミュレーターを、人間と複数の AI エージェントで取り合わないための調整役。
このプロジェクトでは実機検証（APK の導入、画面確認、ログ採取）で使う。

## 前提

Broker が動いていること。動いていなければ CLI は失敗する。

```powershell
# 稼働確認
Get-NetTCPConnection -LocalPort 47631 -State Listen
# 何も出なければ起動する
& C:\apps\device-warden\dist\DeviceWarden.exe --broker
```

トレイ UI はスタートアップで自動起動する（`Device Warden.lnk`）。
Broker はトレイが無ければ自分で起動するが、明示的に起動してもよい。

## 最も重要なこと: リースは 120 秒で失効する

**リースを取得したまま heartbeat を送らないと、2分で「要確認」に移行する。**

```
recovery_required / heartbeat_timeout
```

実際にこのプロジェクトで3回発生した。原因は「claim したまま長時間作業した」こと。
「要確認」になると他のエージェントがその端末を使えなくなる。

### 対策1: `with-device` を使う（推奨）

確保・ADB実行・解放を1コマンドで行うため、heartbeat が不要になる。
**1回のコマンドで終わる作業はこれを使う。**

```powershell
python -m device_warden.cli with-device --tool dsh --session-id <session-id> -- shell getprop ro.product.model
```

### 対策2: 長時間保持するなら heartbeat を送る

画面確認やインストールなど、複数コマンドにまたがる作業では claim が必要になる。
その場合は **2分以内に heartbeat を送り続ける**。

```powershell
$lease = "<lease_id>"
# 30秒ごとに heartbeat を送る
while ($true) {
    python -m device_warden.cli heartbeat $lease
    Start-Sleep -Seconds 30
}
```

### 対策3: 作業が終わったら必ず release する

```powershell
python -m device_warden.cli release <lease_id>
```

セッション終了時の自動解放は無い。**自分で解放する。**

## 標準の作業フロー

### 短時間の作業（推奨）

```powershell
# 1コマンドで確保→実行→解放
python -m device_warden.cli with-device --tool dsh --session-id <id> -- shell pm list packages
```

### 長時間の作業

```powershell
# 1. 端末を確認
python -m device_warden.cli list

# 2. 確保
$claim = python -m device_warden.cli claim --tool dsh --session-id <id> `
    --title "作業名" --project soundcore-switch --workdir <dir> | ConvertFrom-Json
$lease = $claim.lease_id

# 3. heartbeat を送りながら作業する（別プロセスで）
#    2分以内に必ず1回送る
python -m device_warden.cli heartbeat $lease

# 4. 作業する
python -m device_warden.cli adb $lease -- install -r app-debug.apk

# 5. 解放
python -m device_warden.cli release $lease
```

### 要確認になったリースの復旧

自分が取得したリースが `recovery_required` になった場合、`--resume` で復旧できる。

```powershell
python -m device_warden.cli claim --tool dsh --session-id <id> --resume
```

他者のリースが残っている場合は、自分で解放せずユーザーに状況を伝える。

## 直接 adb を使ってよい場合

以下に当てはまる場合は、Device Warden を経由せず直接 `adb` を使ってよい。
使った理由をユーザーに短く伝える。

- ユーザーが他の用途で端末を使っておらず、衝突の心配がないと判断できる
- Broker が停止・応答不能で、復旧を待つと作業が止まる
- エミュレーターを操作する（Device Warden の管理対象外）

直接使う場合も `-s <serial>` で対象を明示する。
実機の個人データを消す操作（アンインストール、データ消去）は事前に確認する。

## このプロジェクトでの実績

| 日時 | 状況 |
|---|---|
| 2026-11-17 18:09 | claim したまま作業し、heartbeat 切れで要確認に |
| 2026-11-17 20:20 | 同上 |
| 2026-11-17 20:42 | 同上 |

いずれも `with-device` か heartbeat の継続で避けられた。
以後は「1コマンドで終わる作業は `with-device`」「長時間なら heartbeat」を守る。

## 参考

- リポジトリ: `C:\apps\device-warden`
- Broker: `127.0.0.1:47631`
- トークン: `%USERPROFILE%\.device-warden\broker.token`
- DB: `%USERPROFILE%\.device-warden\warden.db`
- リース TTL: 120 秒（`store.py` の `recover_stale(seconds=120)`）
