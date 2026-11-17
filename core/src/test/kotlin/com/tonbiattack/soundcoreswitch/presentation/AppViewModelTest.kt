package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.SupportLevel
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.SwitchUiState
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面の状態遷移を検証する。
 *
 * 仕様書 §5 の「キャッシュ表示と実際の接続完了を混同しない」「二重送信防止」
 * 「タイムアウト時は安全に再試行可能」を重点的に確認する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val modelId = "space-a40"
    private val device = earbuds()

    private class Harness(
        val viewModel: AppViewModel,
        val scanner: FakeScanner,
        val repository: FakeTargetRepository,
        val adapter: FakeMultipointAdapter,
        val bluetooth: FakeBluetoothChecker,
        val settings: InMemorySettings,
        val cache: InMemoryTargetCache,
    )

    private fun harness(
        scope: TestScope,
        devices: List<com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice> = listOf(device),
        adapter: FakeMultipointAdapter = FakeMultipointAdapter(modelId = modelId),
        repository: FakeTargetRepository = FakeTargetRepository(),
        bluetooth: BluetoothAvailability = BluetoothAvailability.Ready,
        settings: InMemorySettings = InMemorySettings(),
        cache: InMemoryTargetCache = InMemoryTargetCache(),
        now: () -> Long = { 1_000L },
    ): Harness {
        val scanner = FakeScanner(ScanResult.Found(devices))
        val checker = FakeBluetoothChecker(bluetooth)
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
            now = now,
        )
        return Harness(viewModel, scanner, repository, adapter, checker, settings, cache)
    }

    @Test
    fun `起動するとイヤホンを検出して先頭を選択する`() = runTest {
        val h = harness(this)

        h.viewModel.start()
        advanceUntilIdle()

        val state = h.viewModel.state.value
        assertEquals(1, state.devices.size)
        assertEquals(device.address, state.selectedDevice?.address)
    }

    @Test
    fun `前回選択した機種を復元する`() = runTest {
        val other = earbuds(address = "11:22:33:44:55:66", displayName = "Space A40 2台目")
        val h = harness(
            scope = this,
            devices = listOf(device, other),
            settings = InMemorySettings(address = other.address),
        )

        h.viewModel.start()
        advanceUntilIdle()

        assertEquals(other.address, h.viewModel.state.value.selectedDevice?.address)
    }

    @Test
    fun `Bluetoothオフなら検出せず理由を表示する`() = runTest {
        val h = harness(this, bluetooth = BluetoothAvailability.Off)

        h.viewModel.start()
        advanceUntilIdle()

        val state = h.viewModel.state.value
        assertEquals(BluetoothAvailability.Off, state.bluetooth)
        assertEquals(0, h.scanner.scanCount)
        assertFalse(state.canSwitch)
    }

    @Test
    fun `権限不足なら検出せず理由を表示する`() = runTest {
        val h = harness(this, bluetooth = BluetoothAvailability.PermissionDenied)

        h.viewModel.start()
        advanceUntilIdle()

        assertEquals(BluetoothAvailability.PermissionDenied, h.viewModel.state.value.bluetooth)
        assertEquals(0, h.scanner.scanCount)
    }

    @Test
    fun `権限が許可されたら検出を再開する`() = runTest {
        val h = harness(this, bluetooth = BluetoothAvailability.PermissionDenied)
        h.viewModel.start()
        advanceUntilIdle()
        assertEquals(0, h.scanner.scanCount)

        h.bluetooth.availability = BluetoothAvailability.Ready
        h.viewModel.onPermissionResult(granted = true)
        advanceUntilIdle()

        assertEquals(1, h.scanner.scanCount)
        assertEquals(BluetoothAvailability.Ready, h.viewModel.state.value.bluetooth)
    }

    @Test
    fun `キャッシュがあれば取得前に即表示する`() = runTest {
        val cache = InMemoryTargetCache()
        cache.write(
            device.address,
            TargetSnapshot(
                targets = listOf(target(displayName = "古いPC")),
                observedAt = 500L,
                fromCache = true,
            ),
        )
        val repository = FakeTargetRepository(cache = cache)
        val h = harness(this, repository = repository, cache = cache)

        h.viewModel.start()
        advanceUntilIdle()

        val targets = h.viewModel.state.value.targets
        assertTrue(targets is TargetsUiState.Ready)
        val snapshot = (targets as TargetsUiState.Ready).snapshot
        // 最新取得に成功しているので、最終的にはキャッシュ扱いではなくなる。
        assertFalse(snapshot.fromCache)
    }

    @Test
    fun `取得に失敗してもキャッシュ表示は消さない`() = runTest {
        val cache = InMemoryTargetCache()
        cache.write(
            device.address,
            TargetSnapshot(
                targets = listOf(target(displayName = "キャッシュのPC")),
                observedAt = 500L,
                fromCache = true,
            ),
        )
        val repository = FakeTargetRepository(
            cache = cache,
            fetchResult = TargetListResult.Unavailable(FailureReason.Timeout),
        )
        val h = harness(this, repository = repository, cache = cache)

        h.viewModel.start()
        advanceUntilIdle()

        val targets = h.viewModel.state.value.targets
        assertTrue(targets is TargetsUiState.Ready)
        assertTrue((targets as TargetsUiState.Ready).snapshot.fromCache)
    }

    @Test
    fun `キャッシュが無く取得に失敗したら理由を表示する`() = runTest {
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Unavailable(FailureReason.TargetListUnavailable),
        )
        val h = harness(this, repository = repository)

        h.viewModel.start()
        advanceUntilIdle()

        assertEquals(
            TargetsUiState.Unavailable(FailureReason.TargetListUnavailable),
            h.viewModel.state.value.targets,
        )
    }

    @Test
    fun `切り替え成功を確認できたら成功状態にする`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(status = ConnectionStatus.CONNECTED)),
            ),
        )
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(target())),
        )
        val h = harness(this, adapter = adapter, repository = repository)

        h.viewModel.start()
        advanceUntilIdle()
        h.viewModel.switchTo(target())
        advanceUntilIdle()

        val switch = h.viewModel.state.value.switch
        assertTrue(switch is SwitchUiState.Succeeded)
        assertEquals("Windows PC", (switch as SwitchUiState.Succeeded).targetName)
    }

    @Test
    fun `切り替えを確認できなければ失敗状態にする`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(status = ConnectionStatus.DISCONNECTED)),
            ),
        )
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(target())),
        )
        val h = harness(this, adapter = adapter, repository = repository)

        h.viewModel.start()
        advanceUntilIdle()
        h.viewModel.switchTo(target())
        advanceUntilIdle()

        val switch = h.viewModel.state.value.switch
        assertTrue(switch is SwitchUiState.Failed)
        assertEquals(
            FailureReason.SwitchUnconfirmed,
            (switch as SwitchUiState.Failed).reason,
        )
    }

    @Test
    fun `送信中は二重送信を受け付けない`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(status = ConnectionStatus.CONNECTED)),
            ),
        )
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(target())),
        )
        val h = harness(this, adapter = adapter, repository = repository)

        h.viewModel.start()
        advanceUntilIdle()

        // コルーチンを進めずに連続で要求する。1回目は InFlight になる。
        h.viewModel.switchTo(target())
        h.viewModel.switchTo(target())
        h.viewModel.switchTo(target())
        advanceUntilIdle()

        assertEquals(1, adapter.switchCalls)
    }

    @Test
    fun `未対応機種では切り替えを受け付けない`() = runTest {
        val unsupported = earbuds(supportLevel = SupportLevel.UNKNOWN)
        val adapter = FakeMultipointAdapter(modelId = modelId)
        val h = harness(
            scope = this,
            devices = listOf(unsupported),
            adapter = adapter,
        )

        h.viewModel.start()
        advanceUntilIdle()

        assertFalse(h.viewModel.state.value.canSwitch)
        h.viewModel.switchTo(target())
        advanceUntilIdle()

        assertEquals(0, adapter.switchCalls)
        assertEquals(SwitchUiState.Idle, h.viewModel.state.value.switch)
    }

    @Test
    fun `一部対応機種でも切り替えは無効`() = runTest {
        val partial = earbuds(supportLevel = SupportLevel.PARTIAL)
        val h = harness(scope = this, devices = listOf(partial))

        h.viewModel.start()
        advanceUntilIdle()

        assertFalse(h.viewModel.state.value.canSwitch)
    }

    @Test
    fun `イヤホン未接続では切り替えを無効にする`() = runTest {
        val disconnected = earbuds(connection = ConnectionStatus.DISCONNECTED)
        val h = harness(scope = this, devices = listOf(disconnected))

        h.viewModel.start()
        advanceUntilIdle()

        assertFalse(h.viewModel.state.value.canSwitch)
    }

    @Test
    fun `切り替え結果を閉じると待機に戻る`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Failed(FailureReason.Timeout),
        )
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(target())),
        )
        val h = harness(this, adapter = adapter, repository = repository)

        h.viewModel.start()
        advanceUntilIdle()
        h.viewModel.switchTo(target())
        advanceUntilIdle()
        assertTrue(h.viewModel.state.value.switch is SwitchUiState.Failed)

        h.viewModel.dismissSwitchResult()

        assertEquals(SwitchUiState.Idle, h.viewModel.state.value.switch)
    }

    @Test
    fun `タイムアウト後は再試行できる`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Failed(FailureReason.Timeout),
        )
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(target())),
        )
        val h = harness(this, adapter = adapter, repository = repository)

        h.viewModel.start()
        advanceUntilIdle()
        h.viewModel.switchTo(target())
        advanceUntilIdle()
        assertTrue(h.viewModel.state.value.switch is SwitchUiState.Failed)

        // 失敗後は操作可能に戻っていること。
        assertTrue(h.viewModel.state.value.canSwitch)

        h.viewModel.switchTo(target())
        advanceUntilIdle()
        assertEquals(2, adapter.switchCalls)
    }

    @Test
    fun `イヤホン未検出でもクラッシュせず選択なしになる`() = runTest {
        val h = harness(this, devices = emptyList())

        h.viewModel.start()
        advanceUntilIdle()

        val state = h.viewModel.state.value
        assertTrue(state.devices.isEmpty())
        assertNull(state.selectedDevice)
        assertFalse(state.canSwitch)
    }

    @Test
    fun `機種を切り替えると接続先を読み直す`() = runTest {
        val other = earbuds(address = "11:22:33:44:55:66", displayName = "Space A40 2台目")
        val repository = FakeTargetRepository(
            fetchResult = TargetListResult.Available(listOf(target())),
        )
        val h = harness(this, devices = listOf(device, other), repository = repository)

        h.viewModel.start()
        advanceUntilIdle()
        val afterStart = repository.fetchCount

        h.viewModel.selectDevice(other)
        advanceUntilIdle()

        assertTrue(repository.fetchCount > afterStart)
        assertEquals(other.address, h.viewModel.state.value.selectedDevice?.address)
    }

    @Test
    fun `選択した機種を保存する`() = runTest {
        val h = harness(this)

        h.viewModel.start()
        advanceUntilIdle()

        assertEquals(device.address, h.settings.lastSelectedAddress())
    }
}
