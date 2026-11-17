package com.tonbiattack.soundcoreswitch.domain.model

/** 接続先の種別。判別できない場合は [UNKNOWN]。 */
enum class TargetKind {
    PHONE,
    TABLET,
    PC,
    OTHER,
    UNKNOWN,
}
