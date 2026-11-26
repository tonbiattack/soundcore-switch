package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.SwitchUiState
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import com.tonbiattack.soundcoreswitch.testing.FakeBluetoothChecker
import com.tonbiattack.soundcoreswitch.testing.FakeRegistry
import com.tonbiattack.soundcoreswitch.testing.FakeScanner
import com.tonbiattack.soundcoreswitch.testing.FakeTargetRepository
import com.tonbiattack.soundcoreswitch.testing.InMemorySettings
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切り替え成功後に接続が切れた場合の表示を検証する。
 *
 * 実機では、接続先の端末が応答できない場合でもイヤホンが一覧上いったん
 * 「接続中」と報告し、十数秒後に切断中へ戻ることがある。
 * 成功表示のままだと「一瞬つながって切れた」理由が利用者に分からない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelRetentionTest {

    private val modelId = "space-a40"
    private val device = earbuds()
    private val targetA = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")

    /**
     * 指定した回数まで「接続中」を返し、その後は「切断中」を返すアダプタ。
     *
     * 実機の「いったん接続中と報告し、あとで切断中へ戻る」挙動を模す。
     */
    private class DropsAfterAdapter(
        override val modelId: String,
        private val connectedReads: Int,
        private val item: MultipointTarget,
    ) : MultipointAdapter {
        var readCalls: Int = 0
            private set

        override suspend fun readTargets(device: EarbudsDevice): TargetListResult {
            readCalls++
            val connected = readCalls <= connectedReads
            return TargetListResult.Available(
                listOf(
                    item.copy(
                        status = if (connected) {
                            ConnectionStatus.CONNECTED
                        } else {
                            ConnectionStatus.DISCONNECTED
                        },
                    ),
                ),
            )
        }

        override suspend fun switchTo(
            device: EarbudsDevice,
            target: MultipointTarget,
        ): SwitchResult = SwitchResult.Confirmed(target)
    }

    private fun viewModel(
        scope: TestScope,
        adapter: MultipointAdapter,
        retentionWatchMillis: Long = 15_000,
    ): AppViewModel {
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        return AppViewModel(
            scanner = FakeScanner(ScanResult.Found(listOf(device))),
            targetRepository = FakeTargetRepository(
                fetchResult = TargetListResult.Available(
                    listOf(targetA.copy(status = ConnectionStatus.CONNECTED)),
                ),
            ),
            settings = InMemorySettings(),
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = scope,
            now = { 1_000L },
            retentionWatchMillis = retentionWatchMillis,
        )
    }

    @Test
    fun `切り替え後に切れたら成功表示を失敗へ訂正する`() = runTest {
        // 切り替え直後の確認では接続中、その後の再確認では切断中になる。
        val adapter = DropsAfterAdapter(modelId, connectedReads = 1, item = targetA)
        val vm = viewModel(this, adapter)

        vm.start()
        advanceUntilIdle()
        vm.switchTo(targetA)
        // 再確認（15秒後）まで進めず、切り替え自体の完了だけを待つ。
        advanceTimeBy(2_000)
        runCurrent()

        // 直後は成功として表示される。
        assertTrue(
            "直後は成功表示であること（実際: ${vm.state.value.switch}）",
            vm.state.value.switch is SwitchUiState.Succeeded,
        )

        // 再確認の時間が過ぎると失敗へ訂正される。
        advanceTimeBy(15_001)
        advanceUntilIdle()

        val shown = vm.state.value.switch
        assertTrue("失敗表示へ変わること（実際: $shown）", shown is SwitchUiState.Failed)
        assertEquals(
            FailureReason.SwitchNotRetained,
            (shown as SwitchUiState.Failed).reason,
        )
    }

    @Test
    fun `数秒で切れた場合は中間の確認で早めに訂正する`() = runTest {
        // CX のように約5秒で切れる場合、最終確認（15秒）まで待つと
        // 成功表示のまま誤解させる時間が長くなる。
        val adapter = DropsAfterAdapter(modelId, connectedReads = 1, item = targetA)
        val vm = viewModel(this, adapter)

        vm.start()
        advanceUntilIdle()
        vm.switchTo(targetA)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.state.value.switch is SwitchUiState.Succeeded)

        // 中間確認（全体の半分 = 7.5秒）で訂正されること。
        advanceTimeBy(6_000)
        advanceUntilIdle()

        val shown = vm.state.value.switch
        assertTrue("中間確認で訂正されること（実際: $shown）", shown is SwitchUiState.Failed)
    }

    @Test
    fun `切り替え後に維持されていれば成功のままにする`() = runTest {
        val adapter = DropsAfterAdapter(modelId, connectedReads = 99, item = targetA)
        val vm = viewModel(this, adapter)

        vm.start()
        advanceUntilIdle()
        vm.switchTo(targetA)
        advanceUntilIdle()
        advanceTimeBy(15_001)
        advanceUntilIdle()

        assertTrue(
            "成功のままであること（実際: ${vm.state.value.switch}）",
            vm.state.value.switch is SwitchUiState.Succeeded,
        )
    }

    @Test
    fun `再確認を無効にできる`() = runTest {
        // 即応性を優先する設定では、後からの訂正を行わない。
        val adapter = DropsAfterAdapter(modelId, connectedReads = 1, item = targetA)
        val vm = viewModel(this, adapter, retentionWatchMillis = 0)

        vm.start()
        advanceUntilIdle()
        vm.switchTo(targetA)
        advanceUntilIdle()
        advanceTimeBy(30_000)
        advanceUntilIdle()

        assertTrue(
            "成功のままであること（実際: ${vm.state.value.switch}）",
            vm.state.value.switch is SwitchUiState.Succeeded,
        )
    }
}
