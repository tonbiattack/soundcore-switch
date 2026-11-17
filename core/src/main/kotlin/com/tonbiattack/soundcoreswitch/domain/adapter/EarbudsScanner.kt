package com.tonbiattack.soundcoreswitch.domain.adapter

import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason

/**
 * イヤホン検出の結果。
 *
 * 「Bluetooth が使えない」と「使えるが1台も見つからない」を区別する。
 */
sealed interface ScanResult {
    data class Found(val devices: List<EarbudsDevice>) : ScanResult

    data class Unavailable(val reason: FailureReason) : ScanResult
}

/**
 * 検出済み・登録済みの Soundcore イヤホンを列挙する。
 *
 * 実装は Android 標準 Bluetooth API を使う。Android 側から見える端末一覧を
 * イヤホン内部のマルチポイント登録一覧と混同してはならない（仕様書 F-03）。
 */
interface EarbudsScanner {
    suspend fun scan(): ScanResult
}
