package com.tonbiattack.soundcoreswitch.domain.model

/**
 * イヤホンの機種。
 *
 * @property id 機種を安定して識別するスラッグ（例: `space-a40`）。対応表のキーに使う。
 * @property displayName 画面に出す機種名。
 */
data class EarbudsModel(
    val id: String,
    val displayName: String,
    val vendor: String = "Soundcore",
)
