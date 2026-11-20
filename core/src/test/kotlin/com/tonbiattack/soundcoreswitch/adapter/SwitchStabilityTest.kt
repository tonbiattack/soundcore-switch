package com.tonbiattack.soundcoreswitch.adapter

import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
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
 * 切り替えの安定性を検証する。
 *
 * 実機で「切り替えが成功したり失敗したりする」現象が起きている。
 * 原因として次を疑う:
 * - 切断直後の一覧取得が、まだ反映されていない古い状態を返す
 * - 接続直後の確認が早すぎて、確立前の状態を見て失敗と判定する
 * - 同じ端末への連続操作が排他されていない
 */
class SwitchStabilityTest {

    private val device = earbuds()

    private val targetA = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val targetB = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")
    private val targetC = target(id = "AA:AA:AA:AA:AA:03", displayName = "端末C")

    /**
     * 反映の遅れを模すトランスポート。
     *
     * 実機では切断・接続の結果が一覧に反映されるまで時間がかかる。
     * [reflectAfterReads] 回目の一覧取得までは古い状態を返す。
     */
    private class LaggingTransport(
        private val initial: List<Triple<String, String, Boolean>>,
        private val afterDisconnect: List<Triple<String, String, Boolean>>,
        private val afterConnect: List<Triple<String, String, Boolean>>,
        /** 何回目の一覧取得から新しい状態を返すか。 */
        private val reflectAfterReads: Int = 1,
    ) : SoundcoreTransport {
        private var readCount = 0
        private var disconnected = false
        private var connected = false

        val sentCommands = mutableListOf<Int>()

        override suspend fun exchange(
            mac: String,
            modelCode: String,
            packet: ByteArray,
            expectedCommand: Int?,
            timeoutMillis: Long,
        ): List<ByteArray> {
            val command = SoundcoreProtocol.commandOf(packet)
            sentCommands += command

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
                getDevices -> {
                    readCount++
                    val state = when {
                        connected && readCount > reflectAfterReads -> afterConnect
                        disconnected && readCount > reflectAfterReads -> afterDisconnect
                        else -> initial
                    }
                    listOf(encodeDeviceList(state))
                }
                // 切断・接続コマンドを受けたら、以後の一覧取得で反映後の状態を返す。
                disconnect -> {
                    disconnected = true
                    emptyList()
                }
                connect -> {
                    connected = true
                    emptyList()
                }
                else -> emptyList()
            }
        }

        private fun encodeDeviceList(devices: List<Triple<String, String, Boolean>>): ByteArray {
            val body = mutableListOf<Byte>()
            // 先頭2バイトは「パケット総数」「このパケットの番号」。
            // 端末数ではない（実機のフレームに合わせる）。
            body += 1.toByte()
            body += 1.toByte()
            devices.forEachIndexed { index, (mac, name, connected) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val nameField = ByteArray(32)
                nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, 32))
                body += (40).toByte()
                body += (if (connected) 1 else 0).toByte()
                body += SoundcoreProtocol.parseMac(mac).toList()
                body += nameField.toList()
                // 未使用バイトを埋める（実機のフレームに合わせる）。
                repeat(0) { body += 0 }
                if (index == devices.lastIndex) Unit
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
    fun `切断の反映が遅れても切り替えが成功する`() = runTest {
        // 実機では切断結果が一覧に反映されるまで時間がかかる。
        // 1回目の確認ではまだ接続中に見えるが、待てば反映される。
        val transport = LaggingTransport(
            initial = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", true),
                Triple(targetC.id, "端末C", false),
            ),
            afterDisconnect = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
                Triple(targetC.id, "端末C", false),
            ),
            afterConnect = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
                Triple(targetC.id, "端末C", true),
            ),
            reflectAfterReads = 3,
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            maxConnections = 2,
            thisDeviceIdProvider = { targetA.id },
        )

        val result = adapter.switchTo(device, targetC)

        assertTrue(
            "反映が遅れても成功すること（実際: $result）",
            result is SwitchResult.Confirmed,
        )
    }

    @Test
    fun `接続の反映が遅れても切り替えが成功する`() = runTest {
        // 接続直後はまだ切断中に見えるが、待てば接続中になる。
        val transport = LaggingTransport(
            initial = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
                Triple(targetC.id, "端末C", false),
            ),
            afterDisconnect = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
                Triple(targetC.id, "端末C", false),
            ),
            afterConnect = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", false),
                Triple(targetC.id, "端末C", true),
            ),
            reflectAfterReads = 5,
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            maxConnections = 2,
            thisDeviceIdProvider = { targetA.id },
        )

        val result = adapter.switchTo(device, targetC)

        assertTrue(
            "接続の反映が遅れても成功すること（実際: $result）",
            result is SwitchResult.Confirmed,
        )
    }

    @Test
    fun `切断が反映されないまま接続すると失敗として返す`() = runTest {
        // 切断がまったく反映されない場合、上限が空かないため接続しても無駄。
        // 漫然と接続コマンドを送らず、失敗として返すこと。
        val transport = LaggingTransport(
            initial = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", true),
                Triple(targetC.id, "端末C", false),
            ),
            afterDisconnect = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", true),
                Triple(targetC.id, "端末C", false),
            ),
            afterConnect = listOf(
                Triple(targetA.id, "端末A", true),
                Triple(targetB.id, "端末B", true),
                Triple(targetC.id, "端末C", false),
            ),
            reflectAfterReads = 100,
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            maxConnections = 2,
            thisDeviceIdProvider = { targetA.id },
        )

        val result = adapter.switchTo(device, targetC)

        assertTrue("失敗として返すこと（実際: $result）", result is SwitchResult.Failed)
        val connectCommand = ((SoundcoreProtocol.Cmd.CONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
            (SoundcoreProtocol.Cmd.CONNECT_DEVICE[1].toInt() and 0xFF)
        assertTrue(
            "切断できていないのに接続コマンドを送っている",
            connectCommand !in transport.sentCommands,
        )
    }
}
