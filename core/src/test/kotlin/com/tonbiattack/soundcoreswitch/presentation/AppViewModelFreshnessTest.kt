package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState
import com.tonbiattack.soundcoreswitch.domain.repository.ProtectionCache
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import com.tonbiattack.soundcoreswitch.testing.FakeBluetoothChecker
import com.tonbiattack.soundcoreswitch.testing.FakeMultipointAdapter
import com.tonbiattack.soundcoreswitch.testing.FakeRegistry
import com.tonbiattack.soundcoreswitch.testing.FakeScanner
import com.tonbiattack.soundcoreswitch.testing.FakeTargetRepository
import com.tonbiattack.soundcoreswitch.testing.InMemorySettings
import com.tonbiattack.soundcoreswitch.testing.InMemoryTargetCache
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最新の状態が反映されない」不具合の再現と回帰防止。
 *
 * 実機で観測された症状:
 * - 切り替えたのに一覧が古いまま
 * - 保護したのに自動切断される
 * - 画面に戻っても状態が更新されない
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelFreshnessTest {

    private val modelId = "space-a40"
    private val device = earbuds()

    private val targetA = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val targetB = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")

    private fun harness(
        scope: TestScope,
        repository: FakeTargetRepository = FakeTargetRepository(),
        adapter: FakeMultipointAdapter = FakeMultipointAdapter(modelId = modelId),
        settings: InMemorySettings = InMemorySettings(),
        cache: InMemoryTargetCache = InMemoryTargetCache(),
    ): Triple<AppViewModel, FakeTargetRepository, FakeMultipointAdapter> {
        val scanner = FakeScanner(ScanResult.Found(listOf(device)))
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        val viewModel = AppViewModel(
            scanner = scanner,
            targetRepository = repository,
            settings = settings,
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = scope,
            now = { 1_000L },
        )
        return Triple(viewModel, repository, adapter)
    }

    /**
     * アダプタを経由するリポジトリ。
     *
     * 実装（`DefaultTargetRepository`）と同じ経路にする。これを経由しないと
     * 「切り替え後の再取得」が反映されず、テストの前提が実装とずれる。
     */
    private class AdapterBackedRepository(
        private val adapter: FakeMultipointAdapter,
    ) : com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository {
        override suspend fun readCached(
            device: com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice,
        ): com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot? = null

        override suspend fun fetch(
            device: com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice,
        ): TargetListResult = adapter.readTargets(device)
    }

    @Test
    fun `切り替え後に一覧が最新の状態になる`() = runTest {
        // 切り替え前は A が接続中、切り替え後は B が接続中になる。
        val before = listOf(
            targetA.copy(status = ConnectionStatus.CONNECTED),
            targetB.copy(status = ConnectionStatus.DISCONNECTED),
        )
        val after = listOf(
            targetA.copy(status = ConnectionStatus.DISCONNECTED),
            targetB.copy(status = ConnectionStatus.CONNECTED),
        )
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            targets = TargetListResult.Available(before),
            switchResult = SwitchResult.Confirmed(targetB.copy(status = ConnectionStatus.CONNECTED)),
            targetsAfterSwitch = TargetListResult.Available(after),
        )
        val repository = AdapterBackedRepository(adapter)
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
        viewModel.switchTo(targetB)
        advanceUntilIdle()

        val state = viewModel.state.value
        val ready = state.targets as TargetsUiState.Ready
        val connected = ready.snapshot.targets.filter { it.status == ConnectionStatus.CONNECTED }
        assertEquals(
            "切り替え後は B だけが接続中であること",
            listOf(targetB.id),
            connected.map { it.id },
        )
        assertFalse("最新を取得したので fromCache は false であること", ready.snapshot.fromCache)
    }

    @Test
    fun `切り替え中に refresh が走っても古い一覧で上書きしない`() = runTest {
        // 切り替え前の一覧を返すリポジトリ。切り替え後は新しい一覧を返す。
        val before = listOf(
            targetA.copy(status = ConnectionStatus.CONNECTED),
            targetB.copy(status = ConnectionStatus.DISCONNECTED),
        )
        val after = listOf(
            targetA.copy(status = ConnectionStatus.DISCONNECTED),
            targetB.copy(status = ConnectionStatus.CONNECTED),
        )
        val repository = FakeTargetRepository(fetchResult = TargetListResult.Available(before))
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(targetB.copy(status = ConnectionStatus.CONNECTED)),
            targets = TargetListResult.Available(after),
        )
        val (viewModel, _, _) = harness(this, repository = repository, adapter = adapter)

        viewModel.start()
        advanceUntilIdle()

        // 切り替えと同時に手動更新を要求する（実機で起こり得る操作）。
        viewModel.switchTo(targetB)
        repository.fetchResult = TargetListResult.Available(after)
        viewModel.refresh()
        advanceUntilIdle()

        val ready = viewModel.state.value.targets as TargetsUiState.Ready
        val connected = ready.snapshot.targets.filter { it.status == ConnectionStatus.CONNECTED }
        assertEquals(
            "古い一覧で上書きされず、B が接続中のままであること",
            listOf(targetB.id),
            connected.map { it.id },
        )
    }

    @Test
    fun `保護を切り替えるとアダプタに伝わる`() = runTest {
        // 保護設定はアダプタが同期的に参照する。ViewModel の変更が
        // アダプタ側の保護設定に反映されること。
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(targetA, targetB)),
        )
        // 実装と同じく、ViewModel とアダプタが同じキャッシュを共有する。
        val settings = InMemorySettings()
        val cache = ProtectionCache(settings, backgroundScope)
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            protectionProvider = { cache.current() },
        )
        val scanner = FakeScanner(ScanResult.Found(listOf(device)))
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        val viewModel = AppViewModel(
            scanner = scanner,
            targetRepository = repository,
            settings = settings,
            switchTarget = SwitchTargetUseCase(
                registry = FakeRegistry(mapOf(modelId to adapter)),
                bluetooth = checker,
            ),
            bluetooth = checker,
            scope = this,
            now = { 1_000L },
            protectionCache = cache,
        )

        viewModel.start()
        advanceUntilIdle()

        viewModel.toggleProtection(targetA.id)
        advanceUntilIdle()

        assertTrue(
            "保護した端末がアダプタの保護設定に含まれること",
            adapter.protectedTargetIds().contains(targetA.id),
        )
    }

    @Test
    fun `お気に入りは再起動後も復元される`() = runTest {
        val settings = InMemorySettings()
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(targetA, targetB)),
        )
        val (viewModel, _, _) = harness(this, repository = repository, settings = settings)

        viewModel.start()
        advanceUntilIdle()
        viewModel.toggleFavorite(targetB.id)
        advanceUntilIdle()

        // 新しい ViewModel（アプリ再起動に相当）を作る。
        val (restarted, _, _) = harness(this, repository = repository, settings = settings)
        restarted.start()
        advanceUntilIdle()

        assertTrue(
            "お気に入りが復元されること",
            restarted.state.value.favoriteTargetIds.contains(targetB.id),
        )
    }

    @Test
    fun `保護は再起動後も復元される`() = runTest {
        val settings = InMemorySettings()
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(targetA, targetB)),
        )
        val (viewModel, _, _) = harness(this, repository = repository, settings = settings)

        viewModel.start()
        advanceUntilIdle()
        viewModel.toggleProtection(targetA.id)
        advanceUntilIdle()

        val (restarted, _, _) = harness(this, repository = repository, settings = settings)
        restarted.start()
        advanceUntilIdle()

        assertTrue(
            "保護設定が復元されること",
            restarted.state.value.protectedTargetIds.contains(targetA.id),
        )
    }

    @Test
    fun `画面復帰で状態を取り直す`() = runTest {
        // 画面が前面に戻るたびに start() が呼ばれる。取得が走り直されること。
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(targetA, targetB)),
        )
        val (viewModel, _, _) = harness(this, repository = repository)

        viewModel.start()
        advanceUntilIdle()
        val firstCount = repository.fetchCount

        // バックグラウンドから復帰した状況を模す。
        viewModel.start()
        advanceUntilIdle()

        assertTrue(
            "復帰時に接続先を取り直すこと（初回 $firstCount 回 → ${repository.fetchCount} 回）",
            repository.fetchCount > firstCount,
        )
    }

    @Test
    fun `画面復帰で設定を復元し直さない`() = runTest {
        // 復帰のたびに復元すると、画面で変更した内容が保存前の値に戻る恐れがある。
        val settings = InMemorySettings()
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(targetA, targetB)),
        )
        val (viewModel, _, _) = harness(this, repository = repository, settings = settings)

        viewModel.start()
        advanceUntilIdle()
        viewModel.toggleProtection(targetA.id)
        advanceUntilIdle()

        // 復帰。この時点で保存済みの設定は A を保護している。
        viewModel.start()
        advanceUntilIdle()

        assertTrue(
            "復帰後も保護が維持されること",
            viewModel.state.value.protectedTargetIds.contains(targetA.id),
        )
    }

    @Test
    fun `接続中の端末が一覧の先頭に表示される`() = runTest {
        // 接続中を最初に見せたい。お気に入りより優先する。
        val disconnectedA = targetA.copy(status = ConnectionStatus.DISCONNECTED)
        val connectedB = targetB.copy(status = ConnectionStatus.CONNECTED)
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(disconnectedA, connectedB)),
        )
        val (viewModel, _, _) = harness(this, repository = repository)

        viewModel.start()
        advanceUntilIdle()

        val ready = viewModel.state.value.targets as TargetsUiState.Ready
        assertEquals(
            "接続中の端末が先頭であること",
            connectedB.id,
            ready.snapshot.targets.first().id,
        )
    }

    @Test
    fun `切り替え後は新しい接続先が先頭に表示される`() = runTest {
        // 切り替えると接続中が入れ替わる。並び順もそれに追従すること。
        val before = listOf(
            targetA.copy(status = ConnectionStatus.CONNECTED),
            targetB.copy(status = ConnectionStatus.DISCONNECTED),
        )
        val after = listOf(
            targetA.copy(status = ConnectionStatus.DISCONNECTED),
            targetB.copy(status = ConnectionStatus.CONNECTED),
        )
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            targets = TargetListResult.Available(before),
            switchResult = SwitchResult.Confirmed(targetB.copy(status = ConnectionStatus.CONNECTED)),
            targetsAfterSwitch = TargetListResult.Available(after),
        )
        val repository = AdapterBackedRepository(adapter)
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
        viewModel.switchTo(targetB)
        advanceUntilIdle()

        val ready = viewModel.state.value.targets as TargetsUiState.Ready
        assertEquals(
            "切り替え後は新しい接続先が先頭であること",
            targetB.id,
            ready.snapshot.targets.first().id,
        )
    }
}
