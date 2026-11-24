package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 接続先一覧のスナップショット。
 *
 * キャッシュ由来かどうかを必ず保持する。UI は [fromCache] が true のとき
 * 「古い可能性がある」ことを明示しなければならない（仕様書 §5）。
 *
 * @property observedAt この情報を取得した時刻（epoch millis）。キャッシュなら取得当時の時刻。
 * @property fromCache 現在の接続状態として保証できない過去の一覧かどうか。
 */
data class TargetSnapshot(
    val targets: List<MultipointTarget>,
    val observedAt: Long,
    val fromCache: Boolean,
) {
    /** 以前の一覧は接続状態を保証できないため、表示用に重複を除き状態を不明にする。 */
    fun asHistorical(): TargetSnapshot = copy(
        targets = targets.distinctBy { it.id.uppercase() }
            .map { it.copy(status = ConnectionStatus.UNKNOWN) },
        fromCache = true,
    )
}
