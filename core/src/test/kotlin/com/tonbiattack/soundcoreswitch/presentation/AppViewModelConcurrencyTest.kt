package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState
import com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import com.tonbiattack.soundcoreswitch.testing.FakeBluetoothChecker
import com.tonbiattack.soundcoreswitch.testing.FakeMultipointAdapter
import com.tonbiattack.soundcoreswitch.testing.FakeRegistry
import com.tonbiattack.soundcoreswitch.testing.FakeScanner
import com.tonbiattack.soundcoreswitch.testing.InMemorySettings
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 並行取得で古い状態が新しい状態を上書きしないことを検証する。
 *
 * 実機では「切り替えたのに一覧が古いまま」「機種を切り替えたのに前の一覧が出る」
 * という現象が起きていた。応答の遅い取得が後から完了して上書きするため。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelConcurrencyTest {

    private val modelId = "space-a40"
    private val device = earbuds()

    private val targetA = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val targetB = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")

    /** 呼ばれるたびに遅延時間と結果を変えるリポジトリ。 */
    private class ScriptedRepository(
        private val responses: MutableList<Pair<Long, TargetListResult>>,
    ) : TargetRepository {
        var fetchCount: Int = 0
            private set

        override suspend fun readCached(device: EarbudsDevice): TargetSnapshot? = null

        override suspend fun fetch(device: EarbudsDevice): TargetListResult {
            val index = fetchCount.coerceAtMost(responses.lastIndex)
            fetchCount++
            val (delayMillis, result) = responses[index]
            delay(delayMillis)
            return result
        }
    }

    /**
     * 指定した回数目以降の取得だけを遅くするリポジトリ。
     *
     * `start()` が先に1回取得するため、単純な呼び出し順では狙った競合を作れない。
     * 「何回目の取得か」を明示して制御する。
     */
    private class StagedRepository(
        /** 取得回数（1始まり）ごとの遅延と結果。範囲外は最後の設定を使う。 */
        private val stages: List<Pair<Long, TargetListResult>>,
    ) : TargetRepository {
        var fetchCount: Int = 0
            private set

        override suspend fun readCached(device: EarbudsDevice): TargetSnapshot? = null

        override suspend fun fetch(device: EarbudsDevice): TargetListResult {
            fetchCount++
            val stage = stages[(fetchCount - 1).coerceAtMost(stages.lastIndex)]
            delay(stage.first)
            return stage.second
        }
    }

    private fun viewModel(
        scope: kotlinx.coroutines.test.TestScope,
        repository: TargetRepository,
        devices: List<EarbudsDevice> = listOf(device),
    ): AppViewModel {
        val adapter = FakeMultipointAdapter(modelId = modelId)
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        return AppViewModel(
            scanner = FakeScanner(ScanResult.Found(devices)),
            targetRepository = repository,
            settings = InMemorySettings(),
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = scope,
            now = { 1_000L },
        )
    }

    @Test
    fun `遅い古い取得が後から完了しても新しい状態を上書きしない`() = runTest {
        // 同じ機種を選び直したとき、1回目の取得が遅く、2回目が速い場合。
        // 世代管理が無いと、1回目の古い結果が後から届いて2回目の結果を上書きする。
        val before = listOf(targetA.copy(status = ConnectionStatus.CONNECTED))
        val after = listOf(targetB.copy(status = ConnectionStatus.CONNECTED))

        // 1回目（start）は即座、2回目（selectDevice 1回目）は非常に遅く、
        // 3回目（selectDevice 2回目）は即座。
        val repository = StagedRepository(
            listOf(
                0L to TargetListResult.Available(before),
                2_000L to TargetListResult.Available(before),
                0L to TargetListResult.Available(after),
            ),
        )
        val vm = viewModel(this, repository)

        vm.start()
        advanceUntilIdle()

        // 同じ機種を2回選び直す。1回目は遅く、2回目は速い。
        vm.selectDevice(device)
        vm.selectDevice(device)
        advanceUntilIdle()

        val ready = vm.state.value.targets as TargetsUiState.Ready
        assertEquals(
            "最後に開始した取得の結果が表示されること",
            listOf(targetB.id),
            ready.snapshot.targets.map { it.id },
        )
    }

    @Test
    fun `機種切替中に古い機種の取得が完了しても上書きしない`() = runTest {
        // 1台目の取得が非常に遅く、2台目の取得が速い場合。
        // 世代管理が無いと、1台目の結果が後から届いて2台目の一覧を上書きする。
        val other = earbuds(address = "11:22:33:44:55:66", displayName = "Space A40 2台目")
        val firstDeviceTargets = listOf(targetA)
        val secondDeviceTargets = listOf(targetB)

        val repository = ScriptedRepository(
            mutableListOf(
                2_000L to TargetListResult.Available(firstDeviceTargets),
                0L to TargetListResult.Available(secondDeviceTargets),
            ),
        )
        val vm = viewModel(this, repository, devices = listOf(device, other))

        vm.start()
        advanceUntilIdle()

        // 1台目を選び直す（遅い取得が始まる）。その直後に2台目へ切り替える。
        vm.selectDevice(device)
        vm.selectDevice(other)
        advanceUntilIdle()

        val ready = vm.state.value.targets as TargetsUiState.Ready
        assertEquals(
            "選択中の機種（2台目）の接続先が表示されること",
            listOf(targetB.id),
            ready.snapshot.targets.map { it.id },
        )
    }

    @Test
    fun `取得が失敗しても画面が更新不能にならない`() = runTest {
        val repository = ScriptedRepository(
            mutableListOf(
                0L to TargetListResult.Unavailable(
                    com.tonbiattack.soundcoreswitch.domain.model.FailureReason.TargetListUnavailable,
                ),
                0L to TargetListResult.Available(listOf(targetA)),
            ),
        )
        val vm = viewModel(this, repository)

        vm.start()
        advanceUntilIdle()

        // 失敗後にもう一度取得すれば復帰できること。
        vm.refresh()
        advanceUntilIdle()

        val ready = vm.state.value.targets as TargetsUiState.Ready
        assertEquals(listOf(targetA.id), ready.snapshot.targets.map { it.id })
    }

    @Test
    fun `連続で機種を切り替えても最後の選択が反映される`() = runTest {
        val second = earbuds(address = "11:22:33:44:55:66", displayName = "2台目")
        val third = earbuds(address = "22:33:44:55:66:77", displayName = "3台目")
        val repository = ScriptedRepository(
            mutableListOf(0L to TargetListResult.Available(listOf(targetA))),
        )
        val vm = viewModel(this, repository, devices = listOf(device, second, third))

        vm.start()
        advanceUntilIdle()

        vm.selectDevice(second)
        vm.selectDevice(third)
        advanceUntilIdle()

        assertEquals(
            "最後に選んだ機種が選択中であること",
            third.address,
            vm.state.value.selectedDevice?.address,
        )
    }
}
