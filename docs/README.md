# ドキュメント索引

Soundcore Switch の設計・調査・検証の記録。

## 読む順序

初めて読む場合は、この順で追うと全体像がつかめる。

| # | ドキュメント | 内容 |
|---|---|---|
| 1 | [SPEC.md](./SPEC.md) | 何を作るか（要件・スコープ・UX原則） |
| 2 | [RESEARCH.md](./RESEARCH.md) | なぜ作れるのか（プロトコル解読の記録） |
| 3 | [SUPPORT_MATRIX.md](./SUPPORT_MATRIX.md) | どの機種で動くか |
| 4 | [VERIFICATION_ANDROID.md](./VERIFICATION_ANDROID.md) | Android 実機で何を確認したか |
| 5 | [VERIFICATION_A40.md](./VERIFICATION_A40.md) | Space A40 で何が分かったか |
| 6 | [MEASUREMENT.md](./MEASUREMENT.md) | 速いのか、異常系は大丈夫か |

## ドキュメントの役割

各ドキュメントの守備範囲を明確にしておく。内容が重複したら、正となる場所を決めて他は参照する。

| ドキュメント | 役割 | 更新のきっかけ |
|---|---|---|
| [SPEC.md](./SPEC.md) | **要件の正**。機能要件・非機能要件・スコープ | 仕様を変えるとき |
| [RESEARCH.md](./RESEARCH.md) | **プロトコルの正**。コマンド体系・パケット形式・実測ログ | 新しいコマンドを解読したとき |
| [SUPPORT_MATRIX.md](./SUPPORT_MATRIX.md) | **機種対応の正**。`ModelCatalog` と必ず一致させる | 機種を追加・昇格するとき |
| [VERIFICATION_ANDROID.md](./VERIFICATION_ANDROID.md) | Android 実機での検証記録と、そこで直した不具合 | Android 実機で検証したとき |
| [VERIFICATION_A40.md](./VERIFICATION_A40.md) | Space A40 固有の知見（応答と実効果の食い違い等） | A40 で検証したとき |
| [MEASUREMENT.md](./MEASUREMENT.md) | 計測値・異常系テスト・ログの安全性 | 計測したとき |
| [PROTECTION_AND_FAVORITES.md](./PROTECTION_AND_FAVORITES.md) | 接続先の保護とお気に入り（誤切断の防止） | 保護・お気に入りを変えるとき |
| [QUICK_ACCESS.md](./QUICK_ACCESS.md) | クイック設定タイルとシステムトレイ | 常駐機能を変えるとき |
| [DEVICE_WARDEN.md](./DEVICE_WARDEN.md) | 実機のリース運用（heartbeat の落とし穴） | 実機運用を変えるとき |

## コードとの対応

ドキュメントとコードが食い違ったら、**コードを正**としてドキュメントを直す。
ただし機種対応だけは例外で、`ModelCatalog` と `SUPPORT_MATRIX.md` を同時に更新する。

| ドキュメントの記述 | 対応するコード |
|---|---|
| プロトコルのコマンド・パケット形式 | `core/.../protocol/SoundcoreProtocol.kt` |
| 機種ごとの対応レベル | `core/.../domain/model/ModelCatalog.kt` |
| 切り替えの手順（切断→接続→確認） | `core/.../adapter/SoundcoreMultipointAdapter.kt` |
| 成功の確定条件 | `core/.../domain/usecase/SwitchTargetUseCase.kt` |
| 失敗理由の文言 | `core/.../presentation/FailureMessages.kt` |

## 未確認事項の集約

各ドキュメントの末尾に「未確認点」を置いている。現時点で残っているものは次のとおり。

| 項目 | 記録場所 |
|---|---|
| Space A40 の `dualEnabledOffset`（状態 body の有効フラグ位置） | [VERIFICATION_A40.md](./VERIFICATION_A40.md) §6 |
| DESKTOP-A の切断が拒否される理由 | [VERIFICATION_A40.md](./VERIFICATION_A40.md) §6 |
| 他メーカー端末でのプロファイル API の挙動 | [VERIFICATION_ANDROID.md](./VERIFICATION_ANDROID.md) §6 |
| 3台以上の同時接続に対応する機種での挙動 | [SUPPORT_MATRIX.md](./SUPPORT_MATRIX.md) |
| 実機での起動時間・タップ数の計測 | [MEASUREMENT.md](./MEASUREMENT.md) §7.4 |
