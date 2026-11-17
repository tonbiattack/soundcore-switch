package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 接続先一覧のスナップショット。
 *
 * キャッシュ由来かどうかを必ず保持する。UI は [fromCache] が true のとき
 * 「古い可能性がある」ことを明示しなければならない（仕様書 §5）。
 *
 * @property observedAt この情報を取得した時刻（epoch millis）。キャッシュなら取得当時の時刻。
 * @property fromCache ローカルキャッシュから復元した値かどうか。
 */
data class TargetSnapshot(
    val targets: List<MultipointTarget>,
    val observedAt: Long,
    val fromCache: Boolean,
)
