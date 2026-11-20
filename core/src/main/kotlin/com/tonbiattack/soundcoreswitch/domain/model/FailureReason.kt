package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 失敗理由。仕様書 F-05「Bluetoothオフ、権限不足、イヤホン未接続、タイムアウト、非対応を区別して通知する」
 * に対応する。UI はこの型で文言と対処方法を出し分ける。
 *
 * 例外メッセージをそのまま画面に出さない。端末識別子や内部情報が混ざる恐れがあるため、
 * 表示文言は [FailureReason] から組み立てる。
 */
sealed interface FailureReason {
    /** Bluetooth がオフ。 */
    data object BluetoothOff : FailureReason

    /** Bluetooth の権限がない、または取り消された。 */
    data object PermissionDenied : FailureReason

    /** 対象のイヤホンが見つからない、または接続されていない。 */
    data object EarbudsNotConnected : FailureReason

    /** この機種では操作が未対応。 */
    data object UnsupportedModel : FailureReason

    /** 応答が時間内に返らなかった。 */
    data object Timeout : FailureReason

    /** 接続先の一覧を取得できなかった。 */
    data object TargetListUnavailable : FailureReason

    /**
     * 接続先の一覧が**一部しか**取得できなかった。
     *
     * 分割パケットの欠落・重複・順序不正などで、すべてが揃っていない状態。
     * 不完全な一覧を「接続先はこれだけ」と扱うと、存在する端末を隠したり、
     * 誤った切断対象を選んだりする。取得不可として扱う。
     */
    data object TargetListIncomplete : FailureReason

    /** 切り替え要求は送れたが、結果を確認できなかった。成功として扱ってはならない。 */
    data object SwitchUnconfirmed : FailureReason

    /** 指定した接続先がイヤホン側に存在しない。 */
    data object TargetNotFound : FailureReason

    /**
     * 接続上限に達しているが、切断してよい端末が無い。
     *
     * 保護設定や操作元の特定失敗により、安全に切断できない状態。
     * 誤切断を避けるため、切り替えを行わずに失敗として返す。
     */
    data class CannotFreeConnectionSlot(val reason: DisconnectBlockReason) : FailureReason

    /** 上記以外。詳細はログに残さず、汎用文言を出す。 */
    data object Unknown : FailureReason
}
