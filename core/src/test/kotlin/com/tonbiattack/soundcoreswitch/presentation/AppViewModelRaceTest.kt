package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取得の競合によって古い状態が表示されないことを検証する。
 *
 * 実機では「切り替えた直後に手動更新すると、古い一覧に戻る」現象が起こり得る。
 * 遅い取得が後から完了して、新しい状態を上書きするため。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelRaceTest {

    private val modelId = "space-a40"
    private val device = earbuds()

    private val targetA = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val targetB = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")

    /** 呼ばれるたびに遅延し、指定した一覧を返すリポジトリ。 */
    private class SlowRepository(
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

    @Test
    fun `遅い取得が後から完了しても新しい状態を上書きしない`() = runTest {
        val before = listOf(
            targetA.copy(status = ConnectionStatus.CONNECTED),
            targetB.copy(status = ConnectionStatus.DISCONNECTED),
        )
        val after = listOf(
            targetA.copy(status = ConnectionStatus.DISCONNECTED),
            targetB.copy(status = ConnectionStatus.CONNECTED),
        )

        // 1回目は速く古い一覧、2回目は遅く新しい一覧を返す。
        // 遅い取得が後から完了すると、古い状態で上書きされる恐れがある。
        val repository = SlowRepository(
            mutableListOf(
                0L to TargetListResult.Available(before),
                500L to TargetListResult.Available(after),
            ),
        )
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            targets = TargetListResult.Available(before),
            switchResult = SwitchResult.Confirmed(targetB.copy(status = ConnectionStatus.CONNECTED)),
            targetsAfterSwitch = TargetListResult.Available(after),
        )
        val scanner = FakeScanner(ScanResult.Found(listOf(device)))
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        val viewModel = AppViewModel(
            scanner = scanner,
            targetRepository = repository,
            settings = InMemorySettings(),
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = this,
            now = { 1_000L },
        )

        viewModel.start()
        advanceUntilIdle()

        // 切り替えを実行し、その直後に手動更新を重ねる。
        viewModel.switchTo(targetB)
        viewModel.refresh()
        advanceUntilIdle()

        val ready = viewModel.state.value.targets as TargetsUiState.Ready
        val connected = ready.snapshot.targets.filter { it.status == ConnectionStatus.CONNECTED }
        assertEquals(
            "最後に取得した新しい状態が表示されること",
            listOf(targetB.id),
            connected.map { it.id },
        )
    }

    @Test
    fun `更新中に再度更新を要求しても取得は重複しない`() = runTest {
        val repository = SlowRepository(
            mutableListOf(300L to TargetListResult.Available(listOf(targetA, targetB))),
        )
        val adapter = FakeMultipointAdapter(modelId = modelId)
        val scanner = FakeScanner(ScanResult.Found(listOf(device)))
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        val viewModel = AppViewModel(
            scanner = scanner,
            targetRepository = repository,
            settings = InMemorySettings(),
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = this,
            now = { 1_000L },
        )

        viewModel.start()
        // 実行中の連打を模す。
        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(
            "取得が重複して走らないこと（実際: ${repository.fetchCount}回）",
            repository.fetchCount <= 2,
        )
    }

    @Test
    fun `遅い古い取得が後から完了しても新しい状態を上書きしない`() = runTest {
        // 実機で起こり得る最も厄介な競合。
        // 切り替え後の再取得（速い・新しい）が先に完了し、
        // その前に始まっていた手動更新（遅い・古い）が後から完了する。
        val before = listOf(
            targetA.copy(status = ConnectionStatus.CONNECTED),
            targetB.copy(status = ConnectionStatus.DISCONNECTED),
        )
        val after = listOf(
            targetA.copy(status = ConnectionStatus.DISCONNECTED),
            targetB.copy(status = ConnectionStatus.CONNECTED),
        )

        // 1回目（手動更新）は遅く古い一覧、2回目（切り替え後）は速く新しい一覧。
        val repository = SlowRepository(
            mutableListOf(
                800L to TargetListResult.Available(before),
                0L to TargetListResult.Available(after),
            ),
        )
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            targets = TargetListResult.Available(before),
            switchResult = SwitchResult.Confirmed(targetB.copy(status = ConnectionStatus.CONNECTED)),
            targetsAfterSwitch = TargetListResult.Available(after),
        )
        val scanner = FakeScanner(ScanResult.Found(listOf(device)))
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        val viewModel = AppViewModel(
            scanner = scanner,
            targetRepository = repository,
            settings = InMemorySettings(),
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = this,
            now = { 1_000L },
        )

        viewModel.start()
        advanceUntilIdle()

        // 手動更新（遅い・古い）を先に始める。
        viewModel.refresh()
        // その直後に切り替え（速い・新しい）を実行する。
        viewModel.switchTo(targetB)
        advanceUntilIdle()

        val ready = viewModel.state.value.targets as TargetsUiState.Ready
        val connected = ready.snapshot.targets.filter { it.status == ConnectionStatus.CONNECTED }
        assertEquals(
            "遅れて完了した古い取得で上書きされないこと",
            listOf(targetB.id),
            connected.map { it.id },
        )
    }

    @Test
    fun `切り替え後の再取得が遅くても手動更新の古い結果で上書きしない`() = runTest {
        // 逆順の競合。切り替え後の再取得が遅く、手動更新（古い）が先に完了する。
        val before = listOf(
            targetA.copy(status = ConnectionStatus.CONNECTED),
            targetB.copy(status = ConnectionStatus.DISCONNECTED),
        )
        val after = listOf(
            targetA.copy(status = ConnectionStatus.DISCONNECTED),
            targetB.copy(status = ConnectionStatus.CONNECTED),
        )

        // 1回目（手動更新）は速く古い一覧、2回目（切り替え後）は遅く新しい一覧。
        val repository = SlowRepository(
            mutableListOf(
                0L to TargetListResult.Available(before),
                600L to TargetListResult.Available(after),
            ),
        )
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            targets = TargetListResult.Available(before),
            switchResult = SwitchResult.Confirmed(targetB.copy(status = ConnectionStatus.CONNECTED)),
            targetsAfterSwitch = TargetListResult.Available(after),
        )
        val scanner = FakeScanner(ScanResult.Found(listOf(device)))
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        val viewModel = AppViewModel(
            scanner = scanner,
            targetRepository = repository,
            settings = InMemorySettings(),
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = this,
            now = { 1_000L },
        )

        viewModel.start()
        advanceUntilIdle()

        viewModel.refresh()
        viewModel.switchTo(targetB)
        advanceUntilIdle()

        val ready = viewModel.state.value.targets as TargetsUiState.Ready
        val connected = ready.snapshot.targets.filter { it.status == ConnectionStatus.CONNECTED }
        assertEquals(
            "最終的に新しい状態が表示されること",
            listOf(targetB.id),
            connected.map { it.id },
        )
    }
}
