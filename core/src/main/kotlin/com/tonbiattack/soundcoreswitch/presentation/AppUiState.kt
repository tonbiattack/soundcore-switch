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
 */
data class AppUiState(
    val bluetooth: BluetoothAvailability = BluetoothAvailability.Ready,
    val devices: List<EarbudsDevice> = emptyList(),
    val selectedDevice: EarbudsDevice? = null,
    val targets: TargetsUiState = TargetsUiState.Loading,
    val switch: SwitchUiState = SwitchUiState.Idle,
    val isRefreshing: Boolean = false,
) {
    /**
     * 切り替え操作を受け付けてよいか。
     *
     * 送信中は二重送信を防ぐため false。加えて、機種が対応済みで、
     * Bluetooth が使え、イヤホン自体が接続中であることを要求する。
     * イヤホンが繋がっていなければ切り替え要求は必ず失敗するため、
     * 押せるのに必ず失敗するボタンにしない。
     */
    val canSwitch: Boolean
        get() = switch !is SwitchUiState.InFlight &&
            selectedDevice?.supportLevel?.allowsSwitching == true &&
            selectedDevice?.connection == ConnectionStatus.CONNECTED &&
            bluetooth == BluetoothAvailability.Ready

    /** 接続先一覧を表示できる状態か。 */
    val hasTargets: Boolean
        get() = targets is TargetsUiState.Ready
}
