package com.tonbiattack.soundcoreswitch.domain.repository

/**
 * ユーザー設定の永続化。
 *
 * 仕様書 F-07「前回選択機種を復元する」に対応する。
 * 端末識別子（Bluetooth アドレス）は端末内にのみ保存し、外部送信しない。
 */
interface SettingsRepository {
    /** 最後に選んだイヤホンのアドレス。未設定なら null。 */
    suspend fun lastSelectedAddress(): String?

    /** 最後に選んだイヤホンを記録する。 */
    suspend fun setLastSelectedAddress(address: String)
}
