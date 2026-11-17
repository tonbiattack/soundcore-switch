package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult

/**
 * 接続先の切り替えを実行し、結果を確定する。
 *
 * 処理の流れ:
 * 1. 前提条件を確認する（機種対応・Bluetooth・イヤホン接続）。
 * 2. 機種アダプタへ切り替え要求を送る。
 * 3. **状態を再取得して**、対象が接続中になったことを確認する。
 * 4. 確認できた場合のみ [SwitchResult.Confirmed] を返す。
 *
 * 3 を独立して行うのは、アダプタの応答だけを信用しないためである。
 * 要求を送れたことと切り替えが成立したことは別であり、後者だけを成功として扱う
 * （仕様書 F-04、Issue #4「失敗を成功として扱わない」）。
 */
class SwitchTargetUseCase(
    private val registry: MultipointAdapterRegistry,
    private val bluetooth: BluetoothAvailabilityChecker,
) {
    suspend fun execute(device: EarbudsDevice, target: MultipointTarget): SwitchResult {
        // 1. 前提条件。未対応機種では決して要求を送らない。
        if (!device.supportLevel.allowsSwitching) {
            return SwitchResult.Failed(FailureReason.UnsupportedModel)
        }
        when (bluetooth.check()) {
            BluetoothAvailability.Ready -> Unit
            BluetoothAvailability.Off -> return SwitchResult.Failed(FailureReason.BluetoothOff)
            BluetoothAvailability.PermissionDenied ->
                return SwitchResult.Failed(FailureReason.PermissionDenied)
            BluetoothAvailability.NotSupported ->
                return SwitchResult.Failed(FailureReason.UnsupportedModel)
            is BluetoothAvailability.Unknown ->
                return SwitchResult.Failed(FailureReason.Unknown)
        }
        // 接続状態が「切断中」と確定している場合だけ止める。
        // 「不明」は取得できなかっただけで、切断とは限らない（仕様書 F-02）。
        // 端末によってはプロファイル API が使えず常に不明になるため、
        // 不明を理由に操作を塞ぐと、実際には接続中でも何もできなくなる。
        // 到達できない場合はアダプタ側の通信が失敗し、具体的な理由で返る。
        if (device.connection == ConnectionStatus.DISCONNECTED) {
            return SwitchResult.Failed(FailureReason.EarbudsNotConnected)
        }

        val adapter = registry.adapterFor(device.model.id)
            ?: return SwitchResult.Failed(FailureReason.UnsupportedModel)

        // 2. 切り替え要求。アダプタ自身も確認するが、ここでは結果を鵜呑みにしない。
        when (val sent = adapter.switchTo(device, target)) {
            is SwitchResult.Failed -> return sent
            is SwitchResult.Confirmed -> Unit
        }

        // 3. 状態を再取得して確定する。
        return when (val reread = adapter.readTargets(device)) {
            is TargetListResult.Unavailable -> SwitchResult.Failed(reread.reason)
            is TargetListResult.Available -> {
                val confirmed = reread.targets.firstOrNull { it.id == target.id }
                when {
                    confirmed == null -> SwitchResult.Failed(FailureReason.TargetNotFound)
                    confirmed.status == ConnectionStatus.CONNECTED ->
                        SwitchResult.Confirmed(confirmed)
                    // 再取得できたが接続中になっていない。成功として扱わない。
                    else -> SwitchResult.Failed(FailureReason.SwitchUnconfirmed)
                }
            }
        }
    }
}
