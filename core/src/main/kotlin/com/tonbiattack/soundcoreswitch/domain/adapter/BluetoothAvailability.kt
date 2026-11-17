package com.tonbiattack.soundcoreswitch.domain.adapter

import com.tonbiattack.soundcoreswitch.domain.model.FailureReason

/**
 * Bluetooth の利用可否。
 *
 * 権限不足と Bluetooth オフを区別する。UI はそれぞれ別の案内を出す（仕様書 F-05）。
 */
sealed interface BluetoothAvailability {
    data object Ready : BluetoothAvailability

    data object Off : BluetoothAvailability

    data object PermissionDenied : BluetoothAvailability

    /** 端末に Bluetooth 自体が無い。 */
    data object NotSupported : BluetoothAvailability

    /** 判定できなかった。 */
    data class Unknown(val reason: FailureReason) : BluetoothAvailability
}

/** Bluetooth の状態と権限を確認する。 */
interface BluetoothAvailabilityChecker {
    fun check(): BluetoothAvailability
}
