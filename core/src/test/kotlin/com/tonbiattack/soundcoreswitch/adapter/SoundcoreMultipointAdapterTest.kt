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
        address = "F0:00:00:00:00:01",
        displayName = "Liberty 4 NC",
        modelId = "liberty-4-nc",
    )

    /**
     * スクリプト化したトランスポート。
     *
     * 送信されたパケットを記録し、コマンドに応じた応答を返す。
     *
     * @param disconnectWorks 切断コマンドで実際に状態が変わるか。
     *   実機では対象によって拒否されることがあるため、両方を再現できるようにする。
     * @param connectWorks 接続コマンドで実際に状態が変わるか。
     */
    private class ScriptedTransport(
        var devices: List<Triple<String, String, Boolean>> = emptyList(),
        var failWith: Exception? = null,
        private val disconnectWorks: Boolean = false,
        private val connectWorks: Boolean = false,
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
            val body = packet.copyOfRange(9, packet.size - 1)
            sentBodies += body

            // 切断・接続コマンドは、実機の挙動に合わせて状態を変える。
            when (command) {
                commandOf(SoundcoreProtocol.Cmd.DISCONNECT_DEVICE) -> {
                    if (disconnectWorks && body.size >= 6) {
                        val target = SoundcoreProtocol.formatMac(body, 0)
                        devices = devices.map { (m, n, c) ->
                            Triple(m, n, if (m.equals(target, ignoreCase = true)) false else c)
                        }
                    }
                }
                commandOf(SoundcoreProtocol.Cmd.CONNECT_DEVICE) -> {
                    if (connectWorks && body.size >= 6) {
                        val target = SoundcoreProtocol.formatMac(body, 0)
                        devices = devices.map { (m, n, c) ->
                            Triple(m, n, if (m.equals(target, ignoreCase = true)) true else c)
                        }
                    }
                }
            }

            return when (command) {
                SoundcoreProtocol.Rsp.DUAL_DEVICES -> listOf(buildDeviceListFrame(devices))
                else -> emptyList()
            }
        }

        private fun commandOf(cmd: ByteArray): Int =
            ((cmd[0].toInt() and 0xFF) shl 8) or (cmd[1].toInt() and 0xFF)

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
                Triple("F0:00:00:00:00:10", "Pixel 9", true),
                Triple("F0:00:00:00:00:21", "DESKTOP-B", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.readTargets(device)

        assertTrue(result is TargetListResult.Available)
        val targets = (result as TargetListResult.Available).targets
        assertEquals(2, targets.size)
        assertEquals("Pixel 9", targets[0].displayName)
        assertEquals(ConnectionStatus.CONNECTED, targets[0].status)
        assertEquals("DESKTOP-B", targets[1].displayName)
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
                Triple("F0:00:00:00:00:10", "Pixel 9", true),
                Triple("F0:00:00:00:00:21", "DESKTOP-B", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.switchTo(device, target(id = "F0:00:00:00:00:10"))

        assertTrue(result is SwitchResult.Confirmed)
        // 一覧取得のみ。切断・接続コマンドは送らない。
        // 接続が維持されているかの確認で一覧を複数回読むことはある。
        assertTrue(
            "一覧取得以外のコマンドを送っている: ${transport.sentCommands}",
            transport.sentCommands.all { it == SoundcoreProtocol.Rsp.DUAL_DEVICES },
        )
    }

    @Test
    fun `接続上限に達しているときは先に1台切断してから接続する`() = runTest {
        // 2台接続中（上限）。3台目を繋ぐには先に1台切る必要がある。
        // 切断コマンドを受けたら実際に切断される（実機の挙動に合わせる）。
        val transport = ScriptedTransport(
            devices = listOf(
                Triple("F0:00:00:00:00:10", "Pixel 9", true),
                Triple("F0:00:00:00:00:21", "DESKTOP-B", true),
                Triple("F0:00:00:00:00:30", "Tablet A", false),
            ),
            disconnectWorks = true,
        )
        // 操作元を特定できる状態にする。特定できないと安全側で中止される。
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            maxConnections = 2,
            thisDeviceIdProvider = { "F0:00:00:00:00:10" },
        )

        adapter.switchTo(device, target(id = "F0:00:00:00:00:30"))

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
                Triple("F0:00:00:00:00:10", "Pixel 9", true),
                Triple("F0:00:00:00:00:30", "Tablet A", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport, maxConnections = 2)

        adapter.switchTo(device, target(id = "F0:00:00:00:00:30"))

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
                Triple("F0:00:00:00:00:30", "Tablet A", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport)

        val result = adapter.switchTo(device, target(id = "F0:00:00:00:00:30"))

        assertEquals(SwitchResult.Failed(FailureReason.SwitchUnconfirmed), result)
    }

    @Test
    fun `対象が一覧に無ければ対象不在として失敗する`() = runTest {
        val transport = ScriptedTransport(
            devices = listOf(Triple("F0:00:00:00:00:10", "Pixel 9", true)),
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
            guessKind("DESKTOP-B"),
        )
        assertEquals(
            com.tonbiattack.soundcoreswitch.domain.model.TargetKind.TABLET,
            guessKind("Tablet A"),
        )
        // 手掛かりの無い名前は UNKNOWN のままにする（推測で断定しない）。
        assertEquals(
            com.tonbiattack.soundcoreswitch.domain.model.TargetKind.UNKNOWN,
            guessKind("DEVBOARD-A"),
        )
    }
}
