package com.tonbiattack.soundcoreswitch.domain.model

/**
 * イヤホン内部のマルチポイント登録端末（接続先）。
 *
 * これは Android 側から見える Bluetooth 端末一覧とは別物である（仕様書 F-03）。
 * Android 標準 API では列挙できないため、取得できた場合のみこの型で表現する。
 *
 * @property id イヤホン側プロトコルが返す識別子。表示名の変更に影響されない。
 * @property displayName 画面に出す名前。
 * @property status この接続先の現在の接続状態。不明なら [ConnectionStatus.UNKNOWN]。
 * @property isThisDevice この Android 端末自身が接続先かどうか。
 */
data class MultipointTarget(
    val id: String,
    val displayName: String,
    val kind: TargetKind = TargetKind.UNKNOWN,
    val status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val isThisDevice: Boolean = false,
)
