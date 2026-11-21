package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import com.tonbiattack.soundcoreswitch.testing.FakeBluetoothChecker
import com.tonbiattack.soundcoreswitch.testing.FakeMultipointAdapter
import com.tonbiattack.soundcoreswitch.testing.FakeRegistry
import com.tonbiattack.soundcoreswitch.testing.FakeScanner
import com.tonbiattack.soundcoreswitch.testing.FakeTargetRepository
import com.tonbiattack.soundcoreswitch.testing.InMemorySettings
import com.tonbiattack.soundcoreswitch.testing.earbuds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接続中のイヤホンが自動的に選ばれることを検証する。
 *
 * 複数の Soundcore イヤホンをペアリングしていると、前回選んだ別の機種が
 * 選ばれたままで「なぜか切り替えられない」状態になっていた。
 * ユーザーが最も操作したいのは**今つながっているイヤホン**なので、
 * それを最優先で選ぶ。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelSelectionTest {

    private val modelId = "space-a40"

    private val connectedDevice = earbuds(
        address = "AA:AA:AA:AA:AA:01",
        displayName = "Space A40（接続中）",
        connection = ConnectionStatus.CONNECTED,
    )
    private val disconnectedDevice = earbuds(
        address = "AA:AA:AA:AA:AA:02",
        displayName = "Liberty 4 NC（切断中）",
        connection = ConnectionStatus.DISCONNECTED,
    )

    private fun viewModel(
        scope: kotlinx.coroutines.test.TestScope,
        devices: List<com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice>,
        settings: InMemorySettings = InMemorySettings(),
    ): AppViewModel {
        val adapter = FakeMultipointAdapter(modelId = modelId)
        val checker = FakeBluetoothChecker(BluetoothAvailability.Ready)
        return AppViewModel(
            scanner = FakeScanner(ScanResult.Found(devices)),
            targetRepository = FakeTargetRepository(
                fetchResult = TargetListResult.Available(emptyList()),
            ),
            settings = settings,
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
    fun `接続中のイヤホンが自動的に選ばれる`() = runTest {
        // 切断中の機種が先頭にあっても、接続中の機種を選ぶ。
        val vm = viewModel(
            this,
            devices = listOf(disconnectedDevice, connectedDevice),
        )

        vm.start()
        advanceUntilIdle()

        assertEquals(
            "接続中のイヤホンが選ばれること",
            connectedDevice.address,
            vm.state.value.selectedDevice?.address,
        )
    }

    @Test
    fun `前回選択より接続中を優先する`() = runTest {
        // 前回は切断中の機種を選んでいた。それでも接続中を選ぶ。
        val settings = InMemorySettings(address = disconnectedDevice.address)
        val vm = viewModel(
            this,
            devices = listOf(disconnectedDevice, connectedDevice),
            settings = settings,
        )

        vm.start()
        advanceUntilIdle()

        assertEquals(
            "前回選択より接続中が優先されること",
            connectedDevice.address,
            vm.state.value.selectedDevice?.address,
        )
    }

    @Test
    fun `接続中が無ければ前回選択を復元する`() = runTest {
        val other = earbuds(
            address = "AA:AA:AA:AA:AA:03",
            displayName = "3台目",
            connection = ConnectionStatus.DISCONNECTED,
        )
        val settings = InMemorySettings(address = other.address)
        val vm = viewModel(
            this,
            devices = listOf(disconnectedDevice, other),
            settings = settings,
        )

        vm.start()
        advanceUntilIdle()

        assertEquals(
            "接続中が無ければ前回選択を復元すること",
            other.address,
            vm.state.value.selectedDevice?.address,
        )
    }

    @Test
    fun `接続状態が不明なら前回選択を優先する`() = runTest {
        // 取得不可（UNKNOWN）は「切断中」ではない。前回選択を尊重する。
        val unknown = earbuds(
            address = "AA:AA:AA:AA:AA:04",
            displayName = "状態不明",
            connection = ConnectionStatus.UNKNOWN,
        )
        val settings = InMemorySettings(address = disconnectedDevice.address)
        val vm = viewModel(
            this,
            devices = listOf(unknown, disconnectedDevice),
            settings = settings,
        )

        vm.start()
        advanceUntilIdle()

        assertEquals(
            "UNKNOWN は接続中として扱わないこと",
            disconnectedDevice.address,
            vm.state.value.selectedDevice?.address,
        )
    }

    @Test
    fun `接続中が複数あれば先頭を使う`() = runTest {
        val secondConnected = earbuds(
            address = "AA:AA:AA:AA:AA:05",
            displayName = "2台目接続中",
            connection = ConnectionStatus.CONNECTED,
        )
        val vm = viewModel(
            this,
            devices = listOf(connectedDevice, secondConnected),
        )

        vm.start()
        advanceUntilIdle()

        assertEquals(
            "接続中が複数あれば先頭を使うこと",
            connectedDevice.address,
            vm.state.value.selectedDevice?.address,
        )
    }
}
