package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 接続先一覧の取得結果。
 *
 * 「取得できなかった」と「取得できたが0件」を区別する。前者を空リストで表すと
 * 「接続先が無い」と誤認させるため、必ず [Unavailable] を使う。
 */
sealed interface TargetListResult {
    /** 取得成功。0件もあり得る。 */
    data class Available(val targets: List<MultipointTarget>) : TargetListResult

    /** 取得失敗。理由を保持する。 */
    data class Unavailable(val reason: FailureReason) : TargetListResult
}
