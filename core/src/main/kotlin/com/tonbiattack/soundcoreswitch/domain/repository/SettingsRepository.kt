package com.tonbiattack.soundcoreswitch.domain.repository

import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings

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

    /**
     * お気に入りの接続先 ID（MAC）の集合。
     *
     * **ID で保持する。** 表示名は端末側で変更でき、同名の端末もあり得るため、
     * 名前で保持すると設定が失われる。
     */
    suspend fun favoriteTargetIds(): Set<String>

    /** お気に入りを切り替える。 */
    suspend fun setFavorite(targetId: String, favorite: Boolean)

    /**
     * 接続先の保護設定。
     *
     * 接続上限に達したとき、切断してよい端末を制限する。
     */
    suspend fun protectionSettings(): ProtectionSettings

    /** 保護設定を保存する。 */
    suspend fun setProtectionSettings(settings: ProtectionSettings)
}
