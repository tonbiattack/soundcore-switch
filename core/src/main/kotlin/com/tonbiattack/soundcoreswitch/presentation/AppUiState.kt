package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.SwitchUiState
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState

/**
 * 1画面ぶんの表示状態。
 *
 * 仕様書 §9 の想定画面をそのまま表現する。画面遷移を持たないため、
 * すべての情報をこの1つの状態に集約する。
 *
 * @property bluetooth Bluetooth の利用可否。オフ・権限不足はここで案内する。
 * @property devices 検出済み・登録済みのイヤホン。空でも「未検出」と断定しない。
 * @property selectedDevice 現在選択中のイヤホン。未選択なら null。
 * @property targets 接続先一覧の状態。キャッシュ由来かどうかは snapshot が持つ。
 * @property switch 切り替え操作の進行状態。
 * @property isRefreshing バックグラウンド更新中かどうか。UI は控えめに示す。
 * @property favoriteTargetIds お気に入りの接続先 ID（MAC）。一覧の先頭に表示する。
 * @property protectedTargetIds 保護された接続先 ID（MAC）。自動切断の対象にしない。
 */
data class AppUiState(
    val bluetooth: BluetoothAvailability = BluetoothAvailability.Ready,
    val devices: List<EarbudsDevice> = emptyList(),
    val selectedDevice: EarbudsDevice? = null,
    val targets: TargetsUiState = TargetsUiState.Loading,
    val switch: SwitchUiState = SwitchUiState.Idle,
    val isRefreshing: Boolean = false,
    val favoriteTargetIds: Set<String> = emptySet(),
    val protectedTargetIds: Set<String> = emptySet(),
) {
    /**
     * 切り替え操作を受け付けてよいか。
     *
     * 送信中は二重送信を防ぐため false。加えて、機種が対応済みで、
     * Bluetooth が使え、接続先の最新一覧を取得できていることを要求する。
     * キャッシュや更新失敗後の一覧からは切り替えない。
     *
     * イヤホンの接続状態は「切断中と確定している」場合だけ止める。
     * 「不明」は取得できなかっただけで切断とは限らないため、操作は許可する
     * （仕様書 F-02「取得不可は不明」）。端末によってはプロファイル API が
     * 使えず常に不明になるので、不明を理由に塞ぐと何もできなくなる。
     * 到達できない場合は通信が失敗し、具体的な理由で利用者に伝わる。
     */
    val canSwitch: Boolean
        get() = switch !is SwitchUiState.InFlight &&
            selectedDevice?.supportLevel?.allowsSwitching == true &&
            selectedDevice?.connection != ConnectionStatus.DISCONNECTED &&
            (targets as? TargetsUiState.Ready)?.snapshot?.fromCache == false &&
            bluetooth == BluetoothAvailability.Ready

    /** 接続先一覧を表示できる状態か。 */
    val hasTargets: Boolean
        get() = targets is TargetsUiState.Ready

    /** お気に入りか。 */
    fun isFavorite(targetId: String): Boolean = targetId in favoriteTargetIds

    /** 保護対象か。 */
    fun isProtected(targetId: String): Boolean = targetId in protectedTargetIds
}
