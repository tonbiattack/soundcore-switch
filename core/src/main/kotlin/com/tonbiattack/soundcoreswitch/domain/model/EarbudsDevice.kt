package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 検出済み・登録済みのイヤホン1台。
 *
 * 注意: [address] は端末識別子にあたる。ログ・外部送信・例外メッセージに含めてはならない
 * （仕様書 §6「端末識別子・ペアリング情報を外部送信しない」）。
 *
 * @property supportLevel この機種の対応レベル。UI の有効/無効はこれだけで決める。
 * @property connection イヤホン自体の接続状態。取得不可なら [ConnectionStatus.UNKNOWN]。
 */
data class EarbudsDevice(
    val address: String,
    val displayName: String,
    val model: EarbudsModel,
    val supportLevel: SupportLevel,
    val connection: ConnectionStatus,
)
