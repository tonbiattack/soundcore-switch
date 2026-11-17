package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 機種ごとの対応レベル。仕様書 §7-6 の4区分に対応する。
 *
 * - [SUPPORTED]: 接続先の取得と切り替えの両方が実機で確認済み。
 * - [PARTIAL]: 接続状態の表示のみ実機で確認済み。切り替えは未対応。
 * - [UNSUPPORTED]: 対象外と確認済み。
 * - [UNKNOWN]: 未検証。機能を有効化してはならない。
 *
 * 重要: 実機検証が完了するまで、どの機種も [SUPPORTED] にしてはならない。
 * 誤った成功表示を避けるため、切り替えの有効化は [allowsSwitching] のみで判定する。
 */
enum class SupportLevel {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN,
    ;

    /** 接続先の切り替え操作を有効化してよいか。未検証の機種では決して true にしない。 */
    val allowsSwitching: Boolean
        get() = this == SUPPORTED

    /** 接続先一覧の取得を試みてよいか。 */
    val allowsTargetRead: Boolean
        get() = this == SUPPORTED || this == PARTIAL
}
