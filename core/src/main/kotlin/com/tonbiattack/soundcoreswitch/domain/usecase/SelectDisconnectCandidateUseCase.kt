package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.model.DisconnectBlockReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings

/**
 * 接続上限に達したとき、どの接続先を切断するかを決める。
 *
 * **誤切断を避けることを最優先にする。** 切断してよいと確信できない場合は
 * 候補を返さず、理由を返す。切り替えできないことより、必要な端末を切ることのほうが
 * 損失が大きいため。
 *
 * 判定の原則:
 * - 同一性は MAC アドレス（[MultipointTarget.id]）で判定する。表示名は使わない。
 *   端末名は変更でき、同名の端末も存在し得るため。
 * - 保護された端末は決して候補にしない。
 * - 操作元の端末は候補にしない（切ると通信が途切れて切り替えが成立しない）。
 * - 操作元を特定できない場合、[ProtectionSettings.neverDisconnectUnknown] が true なら
 *   自動切断しない。
 */
class SelectDisconnectCandidateUseCase {

    /**
     * 切断候補を優先順に返す。
     *
     * @param connected 現在接続中の接続先。
     * @param protection 保護設定。
     * @param thisDeviceId 操作元の端末 ID（MAC）。特定できなければ null。
     * @param thisDeviceName 操作元の端末名。[thisDeviceId] が null のときの代替。
     * @return 候補のリスト。空なら [blockReason] に理由が入る。
     */
    fun select(
        connected: List<MultipointTarget>,
        protection: ProtectionSettings,
        thisDeviceId: String?,
        thisDeviceName: String? = null,
    ): DisconnectSelection {
        if (connected.isEmpty()) {
            return DisconnectSelection(emptyList(), DisconnectBlockReason.NoConnectedTargets)
        }

        // 保護された端末を除く。ID（MAC）で判定する。
        val unprotected = connected.filter { protection.allowsDisconnect(it.id) }
        if (unprotected.isEmpty()) {
            return DisconnectSelection(
                emptyList(),
                DisconnectBlockReason.AllProtected(connected.map { it.displayName }),
            )
        }

        // 操作元を特定できているなら、それを除く。
        if (thisDeviceId != null) {
            val others = unprotected.filter { !it.id.equals(thisDeviceId, ignoreCase = true) }
            if (others.isEmpty()) {
                return DisconnectSelection(emptyList(), DisconnectBlockReason.OnlyThisDevice)
            }
            return DisconnectSelection(others, null)
        }

        // MAC は取れないが名前は分かる場合、名前で照合する。
        // Android 10 以降はローカル MAC を取得できないため、これが実質的な手段になる。
        if (thisDeviceName != null) {
            val normalized = thisDeviceName.trim().lowercase()
            val others = unprotected.filter { it.displayName.trim().lowercase() != normalized }
            if (others.isEmpty()) {
                return DisconnectSelection(emptyList(), DisconnectBlockReason.OnlyThisDevice)
            }
            return DisconnectSelection(others, null)
        }

        // 操作元を特定できない。
        //
        // Android 10 以降、アプリからローカル Bluetooth アドレスは取得できない
        // （`BluetoothAdapter.getAddress()` は `02:00:00:00:00:00` を返す）。
        // そのため「特定できない＝中止」にすると、Android では切り替えが一切できない。
        //
        // 代わりに**接続中の端末を1台ずつ試す**。切断して通信が途切れた場合、
        // その端末が操作元だったことになる。呼び出し側は切断後の一覧取得が
        // 失敗したら「操作元を切った」と判断して中止する。
        // 保護された端末は候補から除外済みなので、誤って保護対象を切ることはない。
        if (protection.neverDisconnectUnknown) {
            return DisconnectSelection(
                emptyList(),
                DisconnectBlockReason.CannotIdentifyThisDevice,
            )
        }

        // 自動切断を許容する設定なら、保護対象以外を候補にする。
        return DisconnectSelection(unprotected, null)
    }
}

/**
 * 切断候補の選択結果。
 *
 * @property candidates 切断を試みる順の候補。空なら [blockReason] が入る。
 * @property blockReason 候補を選べなかった理由。候補がある場合は null。
 */
data class DisconnectSelection(
    val candidates: List<MultipointTarget>,
    val blockReason: DisconnectBlockReason?,
) {
    /** 切断を試みられるか。 */
    val hasCandidates: Boolean
        get() = candidates.isNotEmpty()
}
