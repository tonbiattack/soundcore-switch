package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.SupportLevel
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.testing.FakeBluetoothChecker
import com.tonbiattack.soundcoreswitch.testing.FakeMultipointAdapter
import com.tonbiattack.soundcoreswitch.testing.FakeRegistry
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切り替えの確定ロジックを検証する。
 *
 * 最重要の性質は「要求を送れたことを成功とみなさない」こと。
 * ここが壊れると、切り替わっていないのに成功と表示する最悪の誤動作になる。
 */
class SwitchTargetUseCaseTest {

    private val modelId = "space-a40"

    private fun useCase(
        adapter: FakeMultipointAdapter,
        bluetooth: BluetoothAvailability = BluetoothAvailability.Ready,
    ) = SwitchTargetUseCase(
        registry = FakeRegistry(mapOf(modelId to adapter)),
        bluetooth = FakeBluetoothChecker(bluetooth),
    )

    @Test
    fun `再取得で接続中を確認できたときだけ成功する`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(status = ConnectionStatus.CONNECTED)),
            ),
        )

        val result = useCase(adapter).execute(earbuds(), target())

        assertTrue(result is SwitchResult.Confirmed)
        assertEquals(1, adapter.switchCalls)
    }

    @Test
    fun `要求は成功したが再取得で切断中のままなら成功にしない`() = runTest {
        // アダプタは Confirmed を返すが、実際の状態は変わっていない。
        // ここで成功を返すと「切り替えたつもり」の誤動作になる。
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(status = ConnectionStatus.DISCONNECTED)),
            ),
        )

        val result = useCase(adapter).execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.SwitchUnconfirmed), result)
    }

    @Test
    fun `再取得で状態が不明なら成功にしない`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(status = ConnectionStatus.UNKNOWN)),
            ),
        )

        val result = useCase(adapter).execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.SwitchUnconfirmed), result)
    }

    @Test
    fun `再取得で対象が消えていたら対象不在として失敗する`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Available(
                listOf(target(id = "other", displayName = "Tablet")),
            ),
        )

        val result = useCase(adapter).execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.TargetNotFound), result)
    }

    @Test
    fun `再取得自体に失敗したら成功にしない`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Confirmed(target()),
            targetsAfterSwitch = TargetListResult.Unavailable(FailureReason.Timeout),
        )

        val result = useCase(adapter).execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.Timeout), result)
    }

    @Test
    fun `未対応機種では要求を一切送らない`() = runTest {
        val adapter = FakeMultipointAdapter(modelId = modelId)

        val result = useCase(adapter).execute(
            earbuds(supportLevel = SupportLevel.UNKNOWN),
            target(),
        )

        assertEquals(SwitchResult.Failed(FailureReason.UnsupportedModel), result)
        assertEquals(0, adapter.switchCalls)
    }

    @Test
    fun `一部対応機種でも切り替えは送らない`() = runTest {
        val adapter = FakeMultipointAdapter(modelId = modelId)

        val result = useCase(adapter).execute(
            earbuds(supportLevel = SupportLevel.PARTIAL),
            target(),
        )

        assertEquals(SwitchResult.Failed(FailureReason.UnsupportedModel), result)
        assertEquals(0, adapter.switchCalls)
    }

    @Test
    fun `Bluetoothオフでは要求を送らない`() = runTest {
        val adapter = FakeMultipointAdapter(modelId = modelId)

        val result = useCase(adapter, BluetoothAvailability.Off).execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.BluetoothOff), result)
        assertEquals(0, adapter.switchCalls)
    }

    @Test
    fun `権限不足では要求を送らない`() = runTest {
        val adapter = FakeMultipointAdapter(modelId = modelId)

        val result = useCase(adapter, BluetoothAvailability.PermissionDenied)
            .execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.PermissionDenied), result)
        assertEquals(0, adapter.switchCalls)
    }

    @Test
    fun `イヤホン未接続では要求を送らない`() = runTest {
        val adapter = FakeMultipointAdapter(modelId = modelId)

        val result = useCase(adapter).execute(
            earbuds(connection = ConnectionStatus.DISCONNECTED),
            target(),
        )

        assertEquals(SwitchResult.Failed(FailureReason.EarbudsNotConnected), result)
        assertEquals(0, adapter.switchCalls)
    }

    @Test
    fun `アダプタ未登録の機種は非対応として扱う`() = runTest {
        val useCase = SwitchTargetUseCase(
            registry = FakeRegistry(emptyMap()),
            bluetooth = FakeBluetoothChecker(),
        )

        val result = useCase.execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.UnsupportedModel), result)
    }

    @Test
    fun `アダプタが失敗を返したらその理由をそのまま伝える`() = runTest {
        val adapter = FakeMultipointAdapter(
            modelId = modelId,
            switchResult = SwitchResult.Failed(FailureReason.Timeout),
        )

        val result = useCase(adapter).execute(earbuds(), target())

        assertEquals(SwitchResult.Failed(FailureReason.Timeout), result)
    }
}
