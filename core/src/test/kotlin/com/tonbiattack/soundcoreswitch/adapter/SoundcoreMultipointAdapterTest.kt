package com.tonbiattack.soundcoreswitch.adapter

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransport
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransportException
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * マルチポイントアダプタの切り替え手順を検証する。
 *
 * 最重要の性質は「接続上限に達しているとき、先に1台切断してから接続する」こと。
 * 実機でこの手順を省くと接続コマンドが無視されることを確認している。
 */
class SoundcoreMultipointAdapterTest {

    private val device = earbuds(
        address = "F4:9D:8A:4D:43:B6",
        displayName = "Liberty 4 NC",
        modelId = "liberty-4-nc",
    )

    /**
     * スクリプト化したトランスポート。
     *
     * 送信されたパケットを記録し、コマンドに応じた応答を返す。
     */
    private class ScriptedTransport(
        var devices: List<Triple<String, String, Boolean>> = emptyList(),
        var failWith: Exception? = null,
    ) : SoundcoreTransport {
        val sentCommands = mutableListOf<Int>()
        val sentBodies = mutableListOf<ByteArray>()

        override suspend fun exchange(
            mac: String,
            modelCode: String,
            packet: ByteArray,
            expectedCommand: Int?,
            timeoutMillis: Long,
        ): List<ByteArray> {
            failWith?.let { throw it }

            val command = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
            sentCommands += command
            sentBodies += packet.copyOfRange(9, packet.size - 1)

            return when (command) {
                SoundcoreProtocol.Rsp.DUAL_DEVICES -> listOf(buildDeviceListFrame(devices))
                else -> emptyList()
            }
        }

        /** 接続先一覧の応答フレームを組み立てる。 */
        private fun buildDeviceListFrame(
            entries: List<Triple<String, String, Boolean>>,
        ): ByteArray {
            val body = mutableListOf<Byte>()
            body += 1.toByte() // total
            body += 1.toByte() // index
            entries.forEach { (mac, name, connected) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                // 名前欄は 32 バイト固定。実機の応答に合わせる。
                val nameField = ByteArray(32)
                nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, 32))
                val entryLen = 8 + nameField.size
                body += entryLen.toByte()
                body += if (connected) 1.toByte() else 0.toByte()
                body += SoundcoreProtocol.parseMac(mac).toList()
                body += nameField.toList()
            }

            val bodyArray = body.toByteArray()
            val total = SoundcoreProtocol.HEADER_LEN + bodyArray.size + 1
            val frame = mutableListOf<Byte>()
            frame += SoundcoreProtocol.DIRECTION_DEVICE_TO_HOST.toList()
            frame += 0x0B.toByte() // コマンド上位
            frame += 0x01.toByte() // コマンド下位
            frame += (total and 0xFF).toByte()
            frame += ((total shr 8) and 0xFF).toByte()
            frame += bodyArray.toList()
            val checksum = SoundcoreProtocol.checksum(frame.toByteArray())
            frame += checksum
            return frame.toByteArray()
        }
    }

    @Test
    fun `接続先一覧を取得してドメインの型に変換する`() = runTest {
        val transport = ScriptedTransport(
            devices = listOf(
                Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.readTargets(device)

        assertTrue(result is TargetListResult.Available)
        val targets = (result as TargetListResult.Available).targets
        assertEquals(2, targets.size)
        assertEquals("OPPO Reno13 A", targets[0].displayName)
        assertEquals(ConnectionStatus.CONNECTED, targets[0].status)
        assertEquals("DESKTOP-ILQVMVD", targets[1].displayName)
        assertEquals(ConnectionStatus.DISCONNECTED, targets[1].status)
    }

    @Test
    fun `未対応機種では一覧取得を行わない`() = runTest {
        val transport = ScriptedTransport()
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.readTargets(earbuds(modelId = "unknown-model"))

        assertEquals(TargetListResult.Unavailable(FailureReason.UnsupportedModel), result)
        assertTrue(transport.sentCommands.isEmpty())
    }

    @Test
    fun `既に対象が接続中なら切断も接続も送らない`() = runTest {
        val transport = ScriptedTransport(
            devices = listOf(
                Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.switchTo(device, target(id = "7A:A7:6A:03:DD:08"))

        assertTrue(result is SwitchResult.Confirmed)
        // 一覧取得のみ。切断・接続コマンドは送らない。
        assertEquals(
            listOf(SoundcoreProtocol.Rsp.DUAL_DEVICES),
            transport.sentCommands,
        )
    }

    @Test
    fun `接続上限に達しているときは先に1台切断してから接続する`() = runTest {
        // 2台接続中（上限）。3台目を繋ぐには先に1台切る必要がある。
        val transport = ScriptedTransport(
            devices = listOf(
                Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", true),
                Triple("8F:A5:32:05:AE:D0", "Redmi Pad SE 8.7", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport, maxConnections = 2)

        adapter.switchTo(device, target(id = "8F:A5:32:05:AE:D0"))

        val disconnectIndex = transport.sentCommands.indexOf(
            ((SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[1].toInt() and 0xFF),
        )
        val connectIndex = transport.sentCommands.indexOf(
            ((SoundcoreProtocol.Cmd.CONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.CONNECT_DEVICE[1].toInt() and 0xFF),
        )

        assertTrue("切断コマンドが送られていない", disconnectIndex >= 0)
        assertTrue("接続コマンドが送られていない", connectIndex >= 0)
        assertTrue("切断が接続より後になっている", disconnectIndex < connectIndex)
    }

    @Test
    fun `接続上限に達していなければ切断せず接続だけ送る`() = runTest {
        val transport = ScriptedTransport(
            devices = listOf(
                Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                Triple("8F:A5:32:05:AE:D0", "Redmi Pad SE 8.7", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport, maxConnections = 2)

        adapter.switchTo(device, target(id = "8F:A5:32:05:AE:D0"))

        val disconnectCommand =
            ((SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[1].toInt() and 0xFF)
        assertTrue(
            "上限に達していないのに切断コマンドが送られた",
            disconnectCommand !in transport.sentCommands,
        )
    }

    @Test
    fun `接続後に状態を再取得して接続中を確認できなければ成功にしない`() = runTest {
        // 接続コマンドを送っても connected が false のままのケース。
        val transport = ScriptedTransport(
            devices = listOf(
                Triple("8F:A5:32:05:AE:D0", "Redmi Pad SE 8.7", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.switchTo(device, target(id = "8F:A5:32:05:AE:D0"))

        assertEquals(SwitchResult.Failed(FailureReason.SwitchUnconfirmed), result)
    }

    @Test
    fun `対象が一覧に無ければ対象不在として失敗する`() = runTest {
        val transport = ScriptedTransport(
            devices = listOf(Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true)),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.switchTo(device, target(id = "FF:FF:FF:FF:FF:FF"))

        assertEquals(SwitchResult.Failed(FailureReason.TargetNotFound), result)
    }

    @Test
    fun `トランスポートが失敗したらタイムアウトとして扱う`() = runTest {
        val transport = ScriptedTransport(
            failWith = SoundcoreTransportException("接続できません"),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.switchTo(device, target())

        assertEquals(SwitchResult.Failed(FailureReason.Timeout), result)
    }

    @Test
    fun `一覧取得が失敗したら取得不可として扱う`() = runTest {
        val transport = ScriptedTransport(
            failWith = SoundcoreTransportException("接続できません"),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.readTargets(device)

        assertEquals(
            TargetListResult.Unavailable(FailureReason.TargetListUnavailable),
            result,
        )
    }

    @Test
    fun `表示名から端末種別を推定する`() {
        assertEquals(
            com.tonbiattack.soundcoreswitch.domain.model.TargetKind.PC,
            guessKind("DESKTOP-ILQVMVD"),
        )
        assertEquals(
            com.tonbiattack.soundcoreswitch.domain.model.TargetKind.TABLET,
            guessKind("Redmi Pad SE 8.7"),
        )
        assertEquals(
            com.tonbiattack.soundcoreswitch.domain.model.TargetKind.UNKNOWN,
            guessKind("MPC144"),
        )
    }
}
