package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 切り替え要求の結果。
 *
 * 重要: [Confirmed] は「状態を再取得して、対象が接続中になったことを確認できた」場合のみ返す。
 * 要求を送れただけでは成功ではない（仕様書 F-04 / Issue #4「失敗を成功として扱わない」）。
 */
sealed interface SwitchResult {
    /** 切り替えを確認できた。 */
    data class Confirmed(val target: MultipointTarget) : SwitchResult

    /** 切り替えを確認できなかった。理由を保持する。 */
    data class Failed(val reason: FailureReason) : SwitchResult
}
