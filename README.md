# Soundcore Switch

Soundcore 純正アプリを開かずに、マルチポイント接続先の状態確認と切り替えを行うアプリ。
**Android と Windows の両方で動作します。**

## 目的

純正アプリで接続先を切り替えるには「起動 → イヤホン読込 → 設定画面 → 接続先変更」という
待ち時間と操作が必要になる。本アプリはこの経路を「起動 → 接続先を選ぶ → 完了」に短縮する。
仕様は [docs/SPEC.md](docs/SPEC.md) を参照。

## できること

| 機能 | Android | Windows | 備考 |
|---|---|---|---|
| 接続先（マルチポイント登録端末）一覧の表示 | ✅ | ✅ | イヤホン内部の一覧を取得 |
| 各接続先の接続状態表示 | ✅ | ✅ | 取得不可なら「不明」 |
| ワンタップで接続先を切り替え | ✅ | ✅ | 対応機種のみ |
| 前回選択した機種の復元 | ✅ | ✅ | 端末内にのみ保存 |
| キャッシュからの即時表示 | ✅ | ✅ | 古い可能性を明示 |
| ノイズキャンセリング・EQ・FW更新・再生 | ❌ | ❌ | 仕様書 §2 で対象外 |

### 対応機種

| 機種 | 型番 | 状態 |
|---|---|---|
| soundcore Liberty 4 NC | A3947 | ✅ 実機検証済み（Windows / Android 両方） |
| Soundcore Space A40 | A3936 | ✅ 実機検証済み（Windows） |

詳細は [docs/SUPPORT_MATRIX.md](docs/SUPPORT_MATRIX.md) を参照。

### 既知の制約

- **Soundcore 純正アプリが起動していると接続に失敗します。** 純正アプリが
  RFCOMM チャネルを占有するため、本アプリを使う際は純正アプリを終了してください。
- 同時接続は機種の上限（2台）に従います。上限に達している場合、
  アプリが自動的に1台を切断してから接続します。切断は対象によって
  拒否されることがあり、その場合は別の端末を試します。
- 端末によっては Bluetooth のプロファイル API が使えず、イヤホンの接続状態が
  「不明」と表示されます（OPPO CPH2699 で確認）。その場合も切り替え操作は可能で、
  実際に到達できたかは通信結果で判断します。
- 同名のイヤホンを複数ペアリングしている場合、一覧に同名で複数表示されます。
  接続状態のドットで判別してください。

### なぜ実現できたのか

Android 標準の Bluetooth API には、イヤホン内部の接続先一覧を取得する手段も、
他端末へ切り替えを指示する手段も存在しない。本アプリは **Soundcore の機種固有
RFCOMM プロトコル**を実機で解読して実装している。

```
ベンダー UUID: 0cf12d31-fac3-4553-bd80-d6832e7b + 機種コード4桁
例: Liberty 4 NC (A3947) → 0cf12d31-fac3-4553-bd80-d6832e7b3947
```

プロトコルの詳細と実測ログは [docs/RESEARCH.md](docs/RESEARCH.md) を参照。

> **注意**: 同時接続は機種の上限（Liberty 4 NC は2台）に従う。上限に達している場合、
> アプリは自動的に1台を切断してから接続する。この手順を省くと切り替えが効かない
> ことを実機で確認している。

## 動作環境

### Android
- Android 8.0 (API 26) 以上
- Bluetooth を搭載した端末

### Windows
- Windows 10 / 11
- Bluetooth アダプタ
- Java 17 以上（配布版は同梱）

## ビルド

### 前提

- JDK 17 以上
- Android SDK（`compileSdk 36`）
- `local.properties` に `sdk.dir` を設定（Android Studio なら自動生成）

```properties
# local.properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

### Android

```powershell
# デバッグ APK
.\gradlew.bat :app:assembleDebug

# ユニットテスト（共有ロジック）
.\gradlew.bat :core:test

# lint
.\gradlew.bat :app:lintDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` に生成される。

### Windows

```powershell
# 起動（開発時）
.\gradlew.bat :desktop:run

# 配布用インストーラ（MSI / EXE）
.\gradlew.bat :desktop:packageMsi
.\gradlew.bat :desktop:packageExe
```

出力は `desktop/build/compose/binaries/main/` に生成される。

### Windows でテストが落ちる場合

PATH に括弧を含むディレクトリ（例: `C:\Program Files (x86)\PowerShell\7`）や
二重引用符が混入していると、Gradle がテストワーカーの `-Djava.library.path` を
組み立てる際に引数が分断され、次のエラーで落ちる。

```
java.lang.ClassNotFoundException: Files\PowerShell\7;C:\windows\system32;...
```

これはプロジェクトのコードとは無関係な環境要因である。同梱のラッパーを使うと回避できる。

```powershell
.\scripts\gradle.ps1 :core:test
```

## インストール

### Android

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

MIUI / HyperOS で `INSTALL_FAILED_USER_RESTRICTED` になる場合は、端末の「ファイル」アプリで
APK を直接開き、「提供元不明のアプリ」を許可すると以降は `adb install` も通るようになる。

### Windows

`desktop/build/compose/binaries/main/msi/` の MSI を実行する。

## リリース

`v*` 形式のタグを push すると、GitHub Actions が APK と Windows インストーラを
ビルドして GitHub Releases に添付する。

```powershell
git tag v0.1.0
git push origin v0.1.0
```

## アーキテクチャ

```
core/                          # 純 Kotlin。Android にも Compose にも依存しない
└── src/main/kotlin/com/tonbiattack/soundcoreswitch/
    ├── domain/                # モデル・アダプタ契約・リポジトリ契約・ユースケース
    ├── protocol/              # Soundcore プロトコル（パケット生成・解析）
    ├── adapter/               # SoundcoreMultipointAdapter（切り替え手順）
    └── presentation/          # AppUiState / AppViewModel / 表示文言

app/                           # Android
└── src/main/java/com/tonbiattack/soundcoreswitch/
    ├── platform/              # BluetoothSocket トランスポート・DataStore・DI
    └── ui/                    # Jetpack Compose

desktop/                       # Windows
└── src/main/kotlin/com/tonbiattack/soundcoreswitch/desktop/
    ├── platform/              # Winsock AF_BTH トランスポート・Win32 API・DI
    └── ui/                    # Compose Desktop
```

設計上の要点:

- **プロトコル処理は完全に共通。** トランスポート（RFCOMM ソケット）だけが
  プラットフォーム依存なので、Android と Windows で同じ切り替えロジックを使う。
- **ドメイン層は Android に依存しない。** そのためユニットテストは Robolectric 無しの
  純 JVM で高速に回る。
- **成功の確定は状態の再取得で行う。** 切り替え要求を送れたことは成功ではない。
  `SwitchTargetUseCase` が再取得して接続中を確認できた場合のみ成功を返す。
- **「取得不可」と「0件」を区別する。** 空リストを「接続先が無い」と誤表示しない。
- **未検証機種では切り替えを無効化する。** `ModelCatalog.supportLevels` が唯一の正。

## プライバシー

- 外部サーバー・ログイン不要。オフラインで動作する。
- `INTERNET` 権限を要求しない。
- 端末識別子・ペアリング情報を外部送信しない。
- Bluetooth アドレスをログに出力しない。
- 設定とキャッシュは端末内にのみ保存する。
- ペアリング鍵・認証情報は扱わない（OS のペアリングをそのまま利用）。

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/SPEC.md](docs/SPEC.md) | 仕様書 |
| [docs/RESEARCH.md](docs/RESEARCH.md) | 技術調査（プロトコル仕様・実測ログ） |
| [docs/SUPPORT_MATRIX.md](docs/SUPPORT_MATRIX.md) | 機種別の対応状況 |
| [docs/VERIFICATION_ANDROID.md](docs/VERIFICATION_ANDROID.md) | Android 実機検証レポート |
| [docs/VERIFICATION_A40.md](docs/VERIFICATION_A40.md) | Space A40 実機検証レポート |
| [docs/MEASUREMENT.md](docs/MEASUREMENT.md) | 操作数・待ち時間の計測 |

## ライセンス

個人利用を想定した非公開プロジェクト。

プロトコルの解読にあたり [BudsLink](https://github.com/maniacx/BudsLink)（GPL-3.0）を
参考にしたが、コードは取り込んでいない。実機で取得した応答を独自に解析したものである。
