package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 接続先一覧の表示状態。
 *
 * 「まだ読んでいない」「読込中」「取得できた」「取得できなかった」を区別する。
 * 取得できなかったときに空リストを出して「接続先が無い」と誤認させてはならない。
 */
sealed interface TargetsUiState {
    /** まだ一度も取得していない。 */
    data object Loading : TargetsUiState

    /** 表示できる情報がある。[snapshot] がキャッシュ由来なら古い可能性がある。 */
    data class Ready(val snapshot: TargetSnapshot) : TargetsUiState

    /** 取得できなかった。理由を表示する。 */
    data class Unavailable(val reason: FailureReason) : TargetsUiState
}
