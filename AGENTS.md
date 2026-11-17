# AGENTS.md

AI エージェント・開発者向けの作業方針。

## このプロジェクトについて

Soundcore イヤホンのマルチポイント接続先を、純正アプリを開かずに確認・切り替えるアプリ。
Android と Windows の両方で動く。

- **個人利用前提**。ストア公開・課金・多機種対応を前提とした作業はしない
- **非公開プロジェクト**。外部への公開は行わない
- Anker / Soundcore とは無関係の非公式ツール

## 構成

```
core/      純 Kotlin。ドメイン層・プロトコル・アダプタ・ViewModel（Android 非依存）
app/       Android（Jetpack Compose + BluetoothSocket）
desktop/   Windows（Compose Desktop + Winsock AF_BTH）
```

**プロトコル処理は `:core` に集約する。** トランスポート（RFCOMM ソケット）だけが
プラットフォーム依存になる。この分離を崩さないこと。

## 守るべき原則

### 成功の判定

**コマンドの応答有無で成否を判定しない。** 実機で「応答なしで成功」「応答ありで失敗」の
両方を観測している。必ず一覧を再取得して状態で確認する。

`SwitchTargetUseCase` が「要求を送れたこと」と「切り替えが成立したこと」を区別している。
この区別を壊さないこと。

### 未検証機種の扱い

`ModelCatalog.supportLevels` が唯一の正。`SUPPORTED` 以外では切り替えを無効化する。
未検証の機種を有効化すると「切り替えていないのに成功と表示する」最悪の誤動作になる。

機種を追加するときは、次の3つを**同時に**更新する。

1. `ModelCatalog.supportLevels`
2. `DefaultMultipointAdapterRegistry` の登録
3. `docs/SUPPORT_MATRIX.md`

### 取得不可と0件の区別

接続先が取得できなかった場合、空リストで返さない。`TargetListResult.Unavailable` を使う。
空リストを「接続先が無い」と誤表示しない。

### プライバシー

- `INTERNET` 権限を要求しない
- Bluetooth アドレスをログ・例外メッセージに含めない
- ペアリング鍵・認証情報を扱わない
- 設定とキャッシュは端末内にのみ保存する

## 実機を使うとき

Device Warden でリースを取得してから操作する。詳細は
[docs/DEVICE_WARDEN.md](docs/DEVICE_WARDEN.md) を参照。

**最重要: リースは 120 秒で失効する。** claim したまま heartbeat を送らないと
「要確認」に移行し、他のエージェントがその端末を使えなくなる。
このプロジェクトでは実際に3回発生した。

```powershell
# 1コマンドで終わる作業（推奨）: 確保→実行→解放を自動で行う
python -m device_warden.cli with-device --tool dsh --session-id <id> -- shell getprop ro.product.model

# 長時間の作業: heartbeat を自動で送るヘルパーを使う
$lease = .\scripts\device-lease.ps1 -Action claim -Title "APK 導入"
python -m device_warden.cli adb $lease -- install -r app-debug.apk
.\scripts\device-lease.ps1 -Action release -LeaseId $lease
```

作業が終わったら必ず解放する。セッション終了時の自動解放は無い。

## ブランチ運用

- `main` を唯一の長期ブランチとする
- 変更は `feature/<topic>` / `fix/<topic>` の短命ブランチを切って PR 経由でマージする
- マージ後は作業ブランチを削除する
- `main` への force push、履歴改変、無関係な変更の混在は行わない

## 検証

変更したら次を通すこと。

```powershell
.\scripts\gradle.ps1 :core:test          # 共有ロジックのテスト
.\scripts\gradle.ps1 :desktop:test       # Windows 側のテスト
.\scripts\gradle.ps1 :app:assembleDebug  # Android のビルド
.\scripts\gradle.ps1 :app:lintDebug      # lint
```

Windows で PATH に括弧を含むディレクトリがあるとテストが落ちる。
`scripts/gradle.ps1` はそれを回避するラッパー。詳細は README を参照。

### 実機でしか確認できないこと

エミュレータでは再現しない問題が実機で見つかっている（プロファイル API の可否、
RFCOMM の応答挙動など）。実機で確認していない項目を「完了」と断定しない。
未確認のまま残す場合は、該当ドキュメントの「未確認点」に理由付きで記録する。

## ドキュメント

| ドキュメント | 役割 |
|---|---|
| [docs/SPEC.md](docs/SPEC.md) | 要件の正 |
| [docs/RESEARCH.md](docs/RESEARCH.md) | プロトコルの正 |
| [docs/SUPPORT_MATRIX.md](docs/SUPPORT_MATRIX.md) | 機種対応の正 |
| [docs/README.md](docs/README.md) | 索引と役割分担 |

ドキュメントとコードが食い違ったら**コードを正**としてドキュメントを直す。
ただし機種対応だけは例外で、`ModelCatalog` と `SUPPORT_MATRIX.md` を同時に更新する。

## 言語

- コメント・ドキュメント・コミットメッセージ・PR 本文は日本語
- コード・コマンド・ログ・API 名・ファイルパスは原文のまま
