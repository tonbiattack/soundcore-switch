package com.tonbiattack.soundcoreswitch.desktop.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason

/**
 * Win32 API で Bluetooth の利用可否を判定する（Windows 版）。
 *
 * 仕様書 F-05「Bluetoothオフ、権限不足、イヤホン未接続、タイムアウト、非対応を区別して通知する」
 * のうち、Bluetooth 非対応・オフの2つをここで切り分ける。
 *
 * 方針:
 * - Windows には Android のような実行時権限の概念が無いため、
 *   [BluetoothAvailability.PermissionDenied] は返さない。
 * - 例外は投げない。判定に失敗したら [BluetoothAvailability.Unknown] に落とす
 *   （仕様書 §8「権限不足でクラッシュしない」）。
 * - ラジオが見つからない理由（アダプタ無し／オフ）は Win32 からは区別できない。
 *   ここでは「見つからなければ [BluetoothAvailability.Off]」とし、
 *   呼び出しに失敗した場合だけ [BluetoothAvailability.Unknown] にする。
 */
class WindowsBluetoothAvailabilityChecker : BluetoothAvailabilityChecker {

    override fun check(): BluetoothAvailability =
        runCatching { resolve() }
            .getOrElse { BluetoothAvailability.Unknown(FailureReason.Unknown) }

    private fun resolve(): BluetoothAvailability {
        val params = BluetoothFindRadioParams().apply { dwSize = size() }
        // `BluetoothFindFirstRadio` の第2引数は `HANDLE*`（出力）。ハンドル1つぶんの領域でよい。
        val radio = Memory(Native.POINTER_SIZE.toLong())

        val find = BthProps.INSTANCE.BluetoothFindFirstRadio(params, radio)
            ?: return BluetoothAvailability.Off

        // 見つかったことだけ確認すれば十分なので、すぐ閉じる。
        BthProps.INSTANCE.BluetoothFindRadioClose(find)
        return BluetoothAvailability.Ready
    }
}
