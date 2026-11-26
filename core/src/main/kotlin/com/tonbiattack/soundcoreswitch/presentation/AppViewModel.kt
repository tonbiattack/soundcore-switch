package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.EarbudsScanner
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.SwitchUiState
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState
import com.tonbiattack.soundcoreswitch.domain.repository.ProtectionCache
import com.tonbiattack.soundcoreswitch.domain.repository.SettingsRepository
import com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository
import com.tonbiattack.soundcoreswitch.domain.usecase.OrderTargetsUseCase
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 画面の状態を組み立てる。
 *
 * 設計方針:
 * - 起動直後はキャッシュだけで即描画し、更新は非同期に行う（仕様書 F-06 / §8）。
 * - 切り替えは [SwitchTargetUseCase] に委譲し、成功の確定はそこで行う。
 * - 送信中は [AppUiState.canSwitch] が false になり、二重送信を防ぐ（仕様書 §5）。
 *
 * 選択の解決は必ず「イヤホン検出の後」に1か所で行う。検出と前回選択の復元を
 * 並行して走らせると、復元が空の一覧を見て失敗する競合が起きるため。
 *
 * @param scope 画面のライフサイクルに紐づくスコープ。
 * @param now 現在時刻の供給元。テストで固定するために注入する。
 */
class AppViewModel(
    private val scanner: EarbudsScanner,
    private val targetRepository: TargetRepository,
    private val settings: SettingsRepository,
    private val switchTarget: SwitchTargetUseCase,
    private val bluetooth: BluetoothAvailabilityChecker,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 保護設定のメモリキャッシュ。
     *
     * アダプタは切断候補を選ぶ時点で保護設定を**同期的に**参照する。
     * そのため、ここでの変更をキャッシュにも反映しないと、
     * 画面で保護してもアダプタには伝わらず保護が効かない。
     */
    private val protectionCache: ProtectionCache? = null,
    /**
     * 切り替え成功後に、接続が維持されているかを再確認するまでの時間。
     *
     * イヤホンは接続先の端末と確立できない場合でも、一覧上はいったん
     * 「接続中」と報告し、十数秒後に切断中へ戻すことがある（実機で確認）。
     * 切り替え直後だけを見て成功と表示すると、利用者からは
     * 「一瞬つながって、すぐ切れた」と見える。
     *
     * 少し待ってから再確認し、切れていれば理由を表示する。
     * 0 以下にすると再確認しない（テストや即応性を優先する場合）。
     */
    private val retentionWatchMillis: Long = 15_000,
) {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var switchJob: Job? = null

    /** 切り替え後に接続の維持を確認するジョブ。 */
    private var retentionJob: Job? = null

    /** 設定を復元済みか。画面復帰のたびに復元し直さないためのフラグ。 */
    private var preferencesRestored = false

    /**
     * 接続先取得の世代番号。
     *
     * 取得を開始するたびに進める。応答が遅い取得が後から完了しても、
     * 世代が古ければ UI を書き換えない（古い状態で上書きさせない）。
     */
    private var targetsGeneration = 0L

    /** 接続先の並べ替え（お気に入りを先頭に出す）。 */
    private val orderTargets = OrderTargetsUseCase()

    /**
     * 画面表示時に呼ぶ。
     *
     * Bluetooth の状態を確認してから、検出と接続先の読み込みを非同期に開始する。
     * UI をブロックしない。
     *
     * **画面が前面に戻るたびに呼ばれる。** 設定の復元は初回だけ行い、
     * 2回目以降は状態の取り直しだけにする（毎回復元すると、
     * 画面で変更した内容が保存前の値に戻る恐れがある）。
     */
    fun start() {
        _state.update { it.copy(bluetooth = bluetooth.check()) }
        if (!preferencesRestored) {
            preferencesRestored = true
            scope.launch { restorePreferences() }
        }
        refresh()
    }

    /**
     * 保存済みの設定（お気に入り・保護）を復元する。
     *
     * 失敗しても起動を止めない。設定は無くても動作する。
     *
     * 保護設定は**アダプタが参照するキャッシュにも反映する**。
     * これを忘れると、再起動後に保護が効かなくなる。
     */
    private suspend fun restorePreferences() {
        val favorites = runCatching { settings.favoriteTargetIds() }.getOrDefault(emptySet())
        val protection = runCatching { settings.protectionSettings() }
            .getOrDefault(ProtectionSettings())
        _state.update {
            it.copy(
                favoriteTargetIds = favorites,
                protectedTargetIds = protection.protectedTargetIds,
            )
        }
        // アダプタが参照するキャッシュを復元した内容に合わせる。
        protectionCache?.update(protection)
    }

    /**
     * お気に入りを切り替える。
     *
     * 表示順に即座に反映し、保存は非同期に行う。
     */
    fun toggleFavorite(targetId: String) {
        val current = _state.value
        val next = if (targetId in current.favoriteTargetIds) {
            current.favoriteTargetIds - targetId
        } else {
            current.favoriteTargetIds + targetId
        }
        _state.update { it.copy(favoriteTargetIds = next) }
        scope.launch {
            runCatching { settings.setFavorite(targetId, targetId in next) }
        }
        // 並び順を反映するため、表示中の一覧を組み直す。
        reorderTargets()
    }

    /**
     * 保護（自動切断の禁止）を切り替える。
     *
     * 保護した端末は、接続上限に達しても自動で切断されない。
     *
     * **キャッシュにも同時に反映する。** アダプタは切断候補を選ぶ時点で
     * キャッシュを同期的に参照するため、保存だけでは保護が効かない。
     */
    fun toggleProtection(targetId: String) {
        val current = _state.value
        val next = if (targetId in current.protectedTargetIds) {
            current.protectedTargetIds - targetId
        } else {
            current.protectedTargetIds + targetId
        }
        _state.update { it.copy(protectedTargetIds = next) }

        val settingsValue = ProtectionSettings(protectedTargetIds = next)
        // アダプタが参照するキャッシュを先に更新する（保存の完了を待たない）。
        protectionCache?.update(settingsValue)
        scope.launch {
            runCatching { settings.setProtectionSettings(settingsValue) }
        }
    }

    /** 表示中の接続先を、現在のお気に入り設定で並べ直す。 */
    private fun reorderTargets() {
        val current = _state.value
        val ready = current.targets as? TargetsUiState.Ready ?: return
        val ordered = orderTargets.order(ready.snapshot.targets, current.favoriteTargetIds)
        _state.update {
            it.copy(targets = TargetsUiState.Ready(ready.snapshot.copy(targets = ordered)))
        }
    }

    /** 権限要求の結果を受けて状態を更新する。 */
    fun onPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                bluetooth = if (granted) bluetooth.check() else BluetoothAvailability.PermissionDenied,
            )
        }
        if (granted) refresh()
    }

    /**
     * イヤホンを選び直す。
     *
     * 選択を保存し、そのイヤホンの接続先をキャッシュから即表示してから非同期更新する。
     */
    fun selectDevice(device: EarbudsDevice) {
        // 選択が変わったら、進行中の取得結果はすべて古い。捨てる。
        targetsGeneration++
        _state.update {
            it.copy(
                selectedDevice = device,
                targets = TargetsUiState.Loading,
                switch = SwitchUiState.Idle,
            )
        }
        scope.launch { settings.setLastSelectedAddress(device.address) }
        loadTargets(device)
    }

    /** 接続先一覧を再取得する。失敗しても既存の表示は消さない。 */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val availability = bluetooth.check()
                _state.update { it.copy(bluetooth = availability) }
                if (availability != BluetoothAvailability.Ready) {
                    targetsGeneration++
                    _state.update { state ->
                        val shown = state.targets as? TargetsUiState.Ready
                        if (shown == null) state else state.copy(
                            targets = TargetsUiState.Ready(shown.snapshot.asHistorical()),
                        )
                    }
                    return@launch
                }

                when (val scanned = scanner.scan()) {
                    is ScanResult.Unavailable -> {
                        targetsGeneration++
                        _state.update {
                            it.copy(
                                devices = emptyList(),
                                selectedDevice = null,
                                targets = TargetsUiState.Unavailable(scanned.reason),
                            )
                        }
                    }
                    is ScanResult.Found -> {
                        val devices = scanned.devices
                        // 検出が終わってから選択を解決する。ここが唯一の解決点。
                        val selected = resolveSelection(devices)
                        if (_state.value.selectedDevice?.address != selected?.address) {
                            targetsGeneration++
                        }
                        _state.update {
                            val sameDevice = it.selectedDevice?.address == selected?.address
                            it.copy(
                                devices = devices,
                                selectedDevice = selected,
                                targets = if (sameDevice) it.targets else TargetsUiState.Loading,
                            )
                        }
                        if (selected != null) {
                            // 自動選択された機種も「前回選択」として記憶する（仕様書 F-07）。
                            settings.setLastSelectedAddress(selected.address)
                            loadTargets(selected)
                        } else {
                            _state.update { it.copy(targets = TargetsUiState.Loading) }
                        }
                    }
                }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * 接続先へ切り替える。
     *
     * 送信中は受け付けない（二重送信防止）。結果は [SwitchUiState] に反映する。
     */
    fun switchTo(target: MultipointTarget) {
        val current = _state.value
        val device = current.selectedDevice ?: return
        if (!current.canSwitch) return
        if (switchJob?.isActive == true) return

        _state.update { it.copy(switch = SwitchUiState.InFlight(target.id)) }
        switchJob = scope.launch {
            val result = switchTarget.execute(device, target)
            _state.update { state ->
                when (result) {
                    is SwitchResult.Confirmed -> state.copy(
                        switch = SwitchUiState.Succeeded(result.target.displayName),
                    )
                    is SwitchResult.Failed -> state.copy(
                        switch = SwitchUiState.Failed(target.displayName, result.reason),
                    )
                }
            }
            // 成功・失敗にかかわらず、表示中の一覧を実際の状態に合わせ直す。
            loadTargets(device)

            // 成功と表示した場合でも、接続先の端末と確立できずに
            // 十数秒で切れることがある。少し待って確認し、
            // 切れていれば「接続が続かなかった」と伝える。
            if (result is SwitchResult.Confirmed) {
                watchRetention(device, result.target)
            }
        }
    }

    /**
     * 切り替え後に接続が維持されているかを再確認する。
     *
     * イヤホンは接続先と確立できなくても、一覧上はいったん接続中と報告し、
     * しばらくして切断中へ戻すことがある（実機で確認）。
     * 成功表示のままにすると「一瞬つながって切れた」理由が分からないため、
     * 待ってから確認し、切れていれば理由を表示する。
     *
     * **確認は2段階にする。** 実機では数秒で切れる場合と十数秒で切れる場合がある。
     * 最終確認だけで見ると、数秒で切れたケースの訂正が遅れて
     * 「成功」のまま誤解させる時間が長くなる。
     */
    private fun watchRetention(device: EarbudsDevice, target: MultipointTarget) {
        if (retentionWatchMillis <= 0) return
        retentionJob?.cancel()
        retentionJob = scope.launch {
            // 中間と最終の2回確認する。短命な切断を早めに拾う。
            val checkpoints = listOf(retentionWatchMillis / 2, retentionWatchMillis)
            var waited = 0L
            for (checkpoint in checkpoints) {
                delay(checkpoint - waited)
                waited = checkpoint

                val stillConnected = switchTarget.isStillConnected(device, target)
                if (stillConnected == false) {
                    // 表示が既に別の結果へ変わっていたら、
                    // 古い確認結果で上書きしない。
                    val shown = _state.value.switch
                    if (shown !is SwitchUiState.Succeeded) return@launch

                    _state.update {
                        it.copy(
                            switch = SwitchUiState.Failed(
                                target.displayName,
                                FailureReason.SwitchNotRetained,
                            ),
                        )
                    }
                    loadTargets(device)
                    return@launch
                }
            }
        }
    }

    /** 切り替え結果の表示を閉じる。 */
    fun dismissSwitchResult() {
        _state.update { it.copy(switch = SwitchUiState.Idle) }
    }

    /**
     * キャッシュを即表示してから、最新を非同期取得する。
     *
     * キャッシュ表示中は [TargetSnapshot.fromCache] が true のままにし、
     * UI が「古い可能性がある」ことを示せるようにする。
     *
     * **世代番号で古い結果を捨てる。** この関数は呼び出しごとに独立した
     * コルーチンを起動するため、応答が遅い取得が後から完了して
     * 新しい状態を上書きし得る（実機で「切り替えたのに古い一覧に戻る」）。
     * 開始時点の世代を記録し、最新でなければ UI を書き換えない。
     */
    private fun loadTargets(device: EarbudsDevice) {
        val generation = ++targetsGeneration
        scope.launch {
            val cached = targetRepository.readCached(device)
            // キャッシュの即時描画も、より新しい要求が出ていれば捨てる。
            if (cached != null &&
                generation == targetsGeneration &&
                _state.value.selectedDevice?.address == device.address
            ) {
                _state.update { it.copy(targets = TargetsUiState.Ready(cached.asHistorical())) }
            }

            if (device.connection == ConnectionStatus.DISCONNECTED) {
                _state.update { state ->
                    if (generation != targetsGeneration) return@update state
                    when (val shown = state.targets) {
                        is TargetsUiState.Ready -> state.copy(
                            targets = TargetsUiState.Ready(shown.snapshot.asHistorical()),
                        )
                        else -> state.copy(targets = TargetsUiState.Unavailable(FailureReason.EarbudsNotConnected))
                    }
                }
                return@launch
            }

            when (val fresh = targetRepository.fetch(device)) {
                is TargetListResult.Unavailable -> {
                    // より新しい取得が始まっていれば、この結果は古い。捨てる。
                    if (generation != targetsGeneration) return@launch
                    // キャッシュを表示できているなら、それを残したまま失敗を伝える。
                    val shown = _state.value.targets
                    _state.update {
                        it.copy(
                            targets = if (shown is TargetsUiState.Ready) {
                                TargetsUiState.Ready(shown.snapshot.asHistorical())
                            } else {
                                TargetsUiState.Unavailable(fresh.reason)
                            },
                        )
                    }
                }
                is TargetListResult.Available -> {
                    if (generation != targetsGeneration) return@launch
                    if (_state.value.selectedDevice?.address != device.address) return@launch
                    // お気に入りを先頭に出す。並び順は安定させる（毎回変わらない）。
                    val ordered = orderTargets.order(
                        fresh.targets,
                        _state.value.favoriteTargetIds,
                    )
                    _state.update {
                        it.copy(
                            targets = TargetsUiState.Ready(
                                TargetSnapshot(
                                    targets = ordered,
                                    observedAt = now(),
                                    fromCache = false,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 選択を決める。優先順位は「現在の選択 → 前回選択 → 先頭」。
     *
     * 前回選択の復元をここに置くことで、検出結果が揃う前に復元を試みる競合を避ける。
     */
    /**
     * 選択を決める。
     *
     * 優先順位:
     * 1. **接続中のイヤホン**（今つながっているものを最初に選ぶ）
     * 2. 現在の選択（接続中でなくても、ユーザーが選んだものは維持する）
     * 3. 前回選択（仕様書 F-07）
     * 4. 先頭
     *
     * 接続中を最優先にするのは、**ユーザーが最も操作したいのは今つながっている
     * イヤホン**だから。複数ペアリングしていると、前回選んだ別の機種が
     * 選ばれたままで「なぜか切り替えられない」状態になっていた。
     *
     * 前回選択の復元をここに置くことで、検出結果が揃う前に復元を試みる競合を避ける。
     */
    private suspend fun resolveSelection(devices: List<EarbudsDevice>): EarbudsDevice? {
        if (devices.isEmpty()) return null

        // 1. 接続中のイヤホンを最優先。複数あれば先頭（検出順）を使う。
        val connected = devices.firstOrNull { it.connection == ConnectionStatus.CONNECTED }
        if (connected != null) return connected

        // 2. 現在の選択を維持する。
        val current = _state.value.selectedDevice
        val kept = current?.let { selected -> devices.firstOrNull { it.address == selected.address } }
        if (kept != null) return kept

        // 3. 前回選択を復元する。
        val remembered = settings.lastSelectedAddress()
        if (remembered != null) {
            val restored = devices.firstOrNull { it.address == remembered }
            if (restored != null) return restored
        }

        // 4. 先頭。
        return devices.first()
    }
}
