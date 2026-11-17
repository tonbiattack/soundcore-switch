package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 切り替え操作の進行状態。
 *
 * 二重送信防止のため、[InFlight] の間は UI の切り替え操作を無効化する（仕様書 §5）。
 */
sealed interface SwitchUiState {
    /** 待機中。 */
    data object Idle : SwitchUiState

    /** 送信中。この間は追加の切り替え要求を受け付けない。 */
    data class InFlight(val targetId: String) : SwitchUiState

    /** 成功を確認できた。 */
    data class Succeeded(val targetName: String) : SwitchUiState

    /** 失敗した。理由と対象名を保持する。 */
    data class Failed(val targetName: String, val reason: FailureReason) : SwitchUiState
}
