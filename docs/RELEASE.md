# リリース手順

最終更新: 2026-11-24

## 1. 前提

| 項目 | 内容 |
|---|---|
| 配布先 | GitHub Releases（無償） |
| Android | 署名済み APK |
| Windows | MSI インストーラ |
| ストア | Google Play / Microsoft Store は未対応（規約・商標確認後に判断） |

**署名鍵はリポジトリに置かない。** `.gitignore` で `*.keystore` / `*.jks` / `*.p12` を除外している。

## 2. Android の署名

### なぜ CI で署名しないのか

GitHub Actions が生成する debug 鍵は**実行ごとに異なる**。
別の鍵で署名した APK は、既にインストール済みのアプリを上書きできない
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

そのため、リリース用 APK は**同じ鍵を持つローカル環境でビルド**する。

### 手順

```powershell
# 1. versionCode を上げる（インストール済みより必ず大きくする）
.\scripts\gradle.ps1 :app:assembleDebug '-PappVersionCode=3' '-PappVersionName=0.1.2'

# 2. 署名とバージョンを確認する
$tools = "$env:ANDROID_HOME\build-tools\37.0.0"
& "$tools\aapt.exe" dump badging app\build\outputs\apk\debug\app-debug.apk |
    Select-String '^package:'
& "$tools\apksigner.bat" verify --print-certs app\build\outputs\apk\debug\app-debug.apk

# 3. ハッシュを記録する
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

**確認すること**:

- `versionCode` が前回より大きい
- 署名の SHA-256 が前回と同じ（鍵を変えていない）
- ハッシュをリリースノートに記載する

### 鍵のバックアップ

`%USERPROFILE%\.android\debug.keystore` を安全な場所に保管する。
**紛失すると同じ署名で更新できなくなる。** その場合は利用者に
アンインストール→再インストールを求めることになる。

## 3. Windows のビルド

```powershell
.\scripts\gradle.ps1 :desktop:packageMsi
```

出力: `desktop/build/compose/binaries/main/msi/*.msi`

### インストール

MSI を実行する。`perUserInstall = true` のため管理者権限は不要。

### アンインストール

「設定 → アプリ → Soundcore Switch → アンインストール」。
手動で入れた場合は次を削除する。

| 対象 | パス |
|---|---|
| 実行ファイル | `%LOCALAPPDATA%\SoundcoreSwitch\` |
| 設定・キャッシュ | `%APPDATA%\SoundcoreSwitch\` |
| ショートカット | スタートメニュー / デスクトップ |

## 4. リリース作成

```powershell
# タグを打って push する（CI がテストを実行する）
git tag v0.1.2
git push origin v0.1.2

# リリースを作成し、APK と MSI を添付する
gh release create v0.1.2 `
    --title "Soundcore Switch 0.1.2" `
    --notes-file release-notes.md `
    "app\build\outputs\apk\debug\app-debug.apk#Soundcore Switch Android APK" `
    "desktop\build\compose\binaries\main\msi\SoundcoreSwitch-1.0.0.msi#Windows Installer"
```

### リリースノートに必ず書くこと

- バージョン（`versionCode` / `versionName`）
- APK の SHA-256
- 対応機種と制約
- 非公式であることの明記
- 既知の問題

## 5. CI の権限

`.github/workflows/android.yml` は次だけを行う。

| ジョブ | 内容 | 権限 |
|---|---|---|
| Core tests | 共有ロジックのテスト | 読み取り |
| Android APK | lint と debug APK のビルド（成果物として保存） | 読み取り |
| Windows installer | テストと MSI ビルド（成果物として保存） | 読み取り |

**CI はリリースを作成しない。** 署名鍵を持たないため、CI が作った APK は
配布に使えない。リリースは手動で行う。

`permissions: contents: write` は不要になったため削除済み。

## 6. Obtainium での更新

利用者が Obtainium にリポジトリ URL を登録すると、新しいリリースを検出して更新できる。

- 公開リポジトリ: URL を登録するだけでよい
- 非公開リポジトリ: GitHub のアクセストークンが必要（Contents の読み取り権限）

## 7. リリース前チェックリスト

- [ ] `versionCode` を上げた
- [ ] **生成した APK の `versionCode` を実際に確認した**（`aapt dump badging`）
- [ ] 署名の SHA-256 が前回と同じ
- [ ] core / desktop / Android のテストが通る
- [ ] lint が 0 errors
- [ ] 実機で起動と切り替えを確認した
- [ ] APK のハッシュを記録した
- [ ] リリースノートに非公式である旨を書いた
- [ ] 個人情報・秘密情報が含まれていない（`git log -S` で確認）

### リリース後にアセットを差し替えない

**一度公開したリリースのアセットは削除・差し替えしない。**
Obtainium はアセットの ID をキャッシュするため、差し替えると
`404` になり更新できなくなる（実測）。

誤った APK を公開した場合は、リリースごと削除して作り直す。
その場合も Obtainium 側のキャッシュが残るため、利用者には
アプリの再登録を案内する。

## 8. 検証済みの実績（2026-11-24）

| 項目 | 結果 |
|---|---|
| Obtainium での検出 | ✅ 0.1.0 → v0.1.1 を検出 |
| Obtainium 経由の更新 | ✅ versionCode=2 / 0.1.1 に更新 |
| 更新後の起動 | ✅ 接続中のイヤホンを自動選択 |
| MSI インストール | ✅ 終了コード 0、ショートカット作成 |
| MSI アンインストール | ✅ 製品コード指定で削除 |
| MSI 再インストール | ✅ 起動と画面表示を確認 |

**同じバージョンの MSI を上書きインストールすると 1638 で拒否される。**
これは Windows Installer の正常な動作。アンインストールしてから入れる。

## 9. 未確認事項

- [ ] Google Play / Microsoft Store の規約確認
- [ ] 英語版 README の要否
