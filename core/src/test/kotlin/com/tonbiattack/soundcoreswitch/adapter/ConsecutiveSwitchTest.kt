package com.tonbiattack.soundcoreswitch.adapter

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransport
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 連続切り替えの安定性を検証する。
 *
 * 実機では「連続で切り替えると2回目以降が『応答がありませんでした』になる」
 * 現象が起きていた。イヤホンが前の処理を終える前に次のコマンドを送るため。
 */
class ConsecutiveSwitchTest {

    private val device = earbuds()

    private val targetA = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val targetB = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")
    private val targetC = target(id = "AA:AA:AA:AA:AA:03", displayName = "端末C")

    /**
     * イヤホンの処理待ちを模すトランスポート。
     *
     * 前のコマンドから [settleMillis] 以内に次のコマンドが来たら
     * 「応答なし」として失敗させる（実機の挙動に合わせる）。
     *
     * **仮想時間ではなく実時間を使う。** `runTest` の仮想時間は `delay` だけを
     * 進めるため、`System.currentTimeMillis()` では経過を測れない。
     * ここでは `delay` を実際に待たせず、呼び出し回数で「待機したか」を判定する。
     */
    private class SettleAwareTransport(
        private val initial: List<Triple<String, String, Boolean>>,
        /** 何回のコマンドまで「前の処理中」とみなすか。 */
        private val rejectFirstCommands: Int = 0,
    ) : SoundcoreTransport {
        private var commandCount = 0
        private var currentState = initial.toMutableList()
        var rejectedCount = 0
            private set

        override suspend fun exchange(
            mac: String,
            modelCode: String,
            packet: ByteArray,
            expectedCommand: Int?,
            timeoutMillis: Long,
        ): List<ByteArray> {
            commandCount++
            val command = SoundcoreProtocol.commandOf(packet)

            // 最初の数コマンドは「前の処理中」として拒否する。
            if (commandCount <= rejectFirstCommands) {
                rejectedCount++
                throw com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransportException(
                    "応答が時間内に返りませんでした",
                )
            }

            val getDevices = SoundcoreProtocol.commandOf(
                SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_DEVICES),
            )
            val disconnect = SoundcoreProtocol.commandOf(
                SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.DISCONNECT_DEVICE),
            )
            val connect = SoundcoreProtocol.commandOf(
                SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.CONNECT_DEVICE),
            )

            return when (command) {
                getDevices -> listOf(encodeDeviceList(currentState))
                disconnect -> {
                    // body の MAC を切断済みにする。
                    val body = SoundcoreProtocol.bodyOf(packet)
                    val mac = SoundcoreProtocol.formatMac(body, 0)
                    currentState = currentState.map {
                        if (it.first.equals(mac, ignoreCase = true)) Triple(it.first, it.second, false) else it
                    }.toMutableList()
                    emptyList()
                }
                connect -> {
                    val body = SoundcoreProtocol.bodyOf(packet)
                    val mac = SoundcoreProtocol.formatMac(body, 0)
                    currentState = currentState.map {
                        if (it.first.equals(mac, ignoreCase = true)) Triple(it.first, it.second, true) else it
                    }.toMutableList()
                    emptyList()
                }
                else -> emptyList()
            }
        }

        private fun encodeDeviceList(devices: List<Triple<String, String, Boolean>>): ByteArray {
            val body = mutableListOf<Byte>()
            body += 1.toByte()
            body += 1.toByte()
            devices.forEach { (mac, name, connected) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val nameField = ByteArray(32)
                nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, 32))
                body += 40.toByte()
                body += (if (connected) 1 else 0).toByte()
                body += SoundcoreProtocol.parseMac(mac).toList()
                body += nameField.toList()
            }
            return SoundcoreProtocol.buildPacket(
                byteArrayOf(
                    (SoundcoreProtocol.Rsp.DUAL_DEVICES shr 8).toByte(),
                    (SoundcoreProtocol.Rsp.DUAL_DEVICES and 0xFF).toByte(),
                ),
                body.toByteArray(),
            )
        }
    }

    @Test
    fun `切り替え後に待機してから次を実行すれば連続で成功する`() = runTest {
        // イヤホンは前の処理を終えるまで次のコマンドを受け付けない。
        // 切り替え後に待機を入れれば連続で成功する。
        val transport = SettleAwareTransport(
            initial = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
                Triple(targetC.id, "端末C", false),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            maxConnections = 2,
            thisDeviceIdProvider = { targetA.id },
            switchSettleMillis = 1_000,
            disconnectSettleMillis = 1_000,
            confirmInitialDelayMillis = 1_000,
            confirmRetryIntervalMillis = 1_000,
        )

        val first = adapter.switchTo(device, targetB)
        val second = adapter.switchTo(device, targetC)

        assertTrue("1回目が成功すること（実際: $first）", first is SwitchResult.Confirmed)
        assertTrue("2回目が成功すること（実際: $second）", second is SwitchResult.Confirmed)
        assertEquals("イヤホンがコマンドを拒否していないこと", 0, transport.rejectedCount)
    }

    @Test
    fun `イヤホンが前の処理中なら失敗として返す`() = runTest {
        // 実機では前の処理が終わる前に次のコマンドを送ると「応答なし」になる。
        // その場合に成功と誤表示しないことを確認する。
        val transport = SettleAwareTransport(
            initial = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
            ),
            // 最初の数コマンドを「前の処理中」として拒否する。
            rejectFirstCommands = 3,
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            maxConnections = 2,
            thisDeviceIdProvider = { targetA.id },
            switchSettleMillis = 0,
            disconnectSettleMillis = 0,
            confirmInitialDelayMillis = 0,
            confirmRetryIntervalMillis = 0,
        )

        val result = adapter.switchTo(device, targetB)

        assertTrue("失敗として返すこと（実際: $result）", result is SwitchResult.Failed)
        assertTrue("イヤホンがコマンドを拒否していること", transport.rejectedCount > 0)
    }
}
