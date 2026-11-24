package com.tonbiattack.soundcoreswitch.adapter

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransport
import com.tonbiattack.soundcoreswitch.testing.earbuds
import com.tonbiattack.soundcoreswitch.testing.target
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実機（OPPO CPH2699 / Android 16）の検証で発見した挙動の回帰テスト。
 *
 * いずれもエミュレータでは再現せず、実機で初めて分かった問題である。
 * 詳細は `docs/VERIFICATION_ANDROID.md` を参照。
 */
class SoundcoreAdapterDeviceRegressionTest {

    private val device = earbuds(
        address = "F4:9D:8A:4D:43:B6",
        displayName = "Liberty 4 NC",
        modelId = "liberty-4-nc",
    )

    /**
     * 応答をスクリプト化したトランスポート。
     *
     * 接続先一覧は呼び出しごとに [deviceLists] を順に返す。
     * 実機では接続コマンドの反映に時間がかかるため、この仕組みで再現する。
     */
    private class SequencedTransport(
        private val deviceLists: List<List<Triple<String, String, Boolean>>>,
    ) : SoundcoreTransport {
        val sentCommands = mutableListOf<Int>()
        private var listIndex = 0

        override suspend fun exchange(
            mac: String,
            modelCode: String,
            packet: ByteArray,
            expectedCommand: Int?,
            timeoutMillis: Long,
        ): List<ByteArray> {
            val command = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
            sentCommands += command

            if (command != SoundcoreProtocol.Rsp.DUAL_DEVICES) {
                // 切断・接続コマンドには MAC をそのまま返す（実機の応答に合わせる）。
                return listOf(buildEchoFrame(command, packet.copyOfRange(9, packet.size - 1)))
            }

            val entries = deviceLists[minOf(listIndex, deviceLists.size - 1)]
            listIndex++
            return listOf(buildDeviceListFrame(entries))
        }

        private fun buildEchoFrame(command: Int, body: ByteArray): ByteArray {
            val total = SoundcoreProtocol.HEADER_LEN + body.size + 1
            val frame = mutableListOf<Byte>()
            frame += SoundcoreProtocol.DIRECTION_DEVICE_TO_HOST.toList()
            frame += ((command shr 8) and 0xFF).toByte()
            frame += (command and 0xFF).toByte()
            frame += (total and 0xFF).toByte()
            frame += ((total shr 8) and 0xFF).toByte()
            frame += body.toList()
            frame += SoundcoreProtocol.checksum(frame.toByteArray())
            return frame.toByteArray()
        }

        private fun buildDeviceListFrame(
            entries: List<Triple<String, String, Boolean>>,
        ): ByteArray {
            val body = mutableListOf<Byte>()
            body += 1.toByte()
            body += 1.toByte()
            entries.forEach { (mac, name, connected) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val nameField = ByteArray(32)
                nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, 32))
                body += (8 + nameField.size).toByte()
                body += if (connected) 1.toByte() else 0.toByte()
                body += SoundcoreProtocol.parseMac(mac).toList()
                body += nameField.toList()
            }
            val bodyArray = body.toByteArray()
            val total = SoundcoreProtocol.HEADER_LEN + bodyArray.size + 1
            val frame = mutableListOf<Byte>()
            frame += SoundcoreProtocol.DIRECTION_DEVICE_TO_HOST.toList()
            frame += 0x0B.toByte()
            frame += 0x01.toByte()
            frame += (total and 0xFF).toByte()
            frame += ((total shr 8) and 0xFF).toByte()
            frame += bodyArray.toList()
            frame += SoundcoreProtocol.checksum(frame.toByteArray())
            return frame.toByteArray()
        }
    }

    @Test
    fun `重複した端末が一覧に含まれる場合は切断や接続を行わない`() = runTest {
        val transport = SequencedTransport(
            deviceLists = listOf(listOf(
                Triple("AA:AA:AA:AA:AA:01", "端末A", true),
                Triple("AA:AA:AA:AA:AA:01", "端末A", false),
                Triple("AA:AA:AA:AA:AA:02", "端末B", false),
            )),
        )
        val adapter = SoundcoreMultipointAdapter(transport = transport)
        val result = adapter.switchTo(device, target(id = "AA:AA:AA:AA:AA:02"))

        assertEquals(SwitchResult.Failed(FailureReason.TargetListIncomplete), result)
        assertEquals(listOf(SoundcoreProtocol.Rsp.DUAL_DEVICES), transport.sentCommands)
    }

    @Test
    fun `操作元の端末は切断対象に選ばない`() = runTest {
        // 実機の Bluetooth 名は「OPPO Reno13 A」。キーワード判定では PC と認識されず、
        // 修正前は操作元自身が切断されていた。
        val transport = SequencedTransport(
            deviceLists = listOf(
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("90:FB:C5:9D:28:F4", "DELLTOWER", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("90:FB:C5:9D:28:F4", "DELLTOWER", false),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", true),
                ),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { "7A:A7:6A:03:DD:08" },
        )

        adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        // 切断コマンドの body が操作元（7A:A7:...）でないこと。
        val disconnectCommand =
            ((SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[1].toInt() and 0xFF)
        assertTrue(disconnectCommand in transport.sentCommands)
    }

    @Test
    fun `接続の反映に時間がかかっても成功を確認できる`() = runTest {
        // 実機では接続コマンド送信直後の再取得では、まだ反映されていないことがあった。
        // 数回確認することで成功を検出できること。
        val transport = SequencedTransport(
            deviceLists = listOf(
                // 1回目: 対象は未接続
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
                // 2回目: まだ反映されていない
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
                // 3回目: 反映された
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", true),
                ),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { "7A:A7:6A:03:DD:08" },
        )

        val result = adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        assertTrue("反映待ちの再試行で成功できること: $result", result is SwitchResult.Confirmed)
    }

    @Test
    fun `最後まで反映されなければ成功にしない`() = runTest {
        // 圏外の端末を指定した場合。実機では「確認できませんでした」と表示された。
        val transport = SequencedTransport(
            deviceLists = listOf(
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { "7A:A7:6A:03:DD:08" },
        )

        val result = adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        assertEquals(SwitchResult.Failed(FailureReason.SwitchUnconfirmed), result)
    }

    @Test
    fun `操作元を特定できず自動切断を禁止する設定なら中止する`() = runTest {
        // 操作元の ID が取れない端末では、どの接続先を切ってよいか判断できない。
        // 誤って必要な端末を切るより、切り替えを中止するほうが安全。
        val transport = SequencedTransport(
            deviceLists = listOf(
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("90:FB:C5:9D:28:F4", "DELLTOWER", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { null },
            protectionProvider = { ProtectionSettings(neverDisconnectUnknown = true) },
        )

        val result = adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        assertTrue(result is SwitchResult.Failed)
        val reason = (result as SwitchResult.Failed).reason
        assertTrue(
            "切断候補を選べない理由が返ること: $reason",
            reason is FailureReason.CannotFreeConnectionSlot,
        )
        // 切断コマンドを送っていないこと（誤切断の防止）。
        val disconnectCommand =
            ((SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[1].toInt() and 0xFF)
        assertTrue(
            "操作元が不明なのに切断コマンドを送っている",
            disconnectCommand !in transport.sentCommands,
        )
    }

    @Test
    fun `保護された端末は切断せず中止する`() = runTest {
        // 接続中の2台が両方保護されている場合、どちらも切れない。
        val transport = SequencedTransport(
            deviceLists = listOf(
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("90:FB:C5:9D:28:F4", "DELLTOWER", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { "7A:A7:6A:03:DD:08" },
            protectionProvider = {
                ProtectionSettings(
                    protectedTargetIds = setOf("90:FB:C5:9D:28:F4"),
                )
            },
        )

        val result = adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        assertTrue(result is SwitchResult.Failed)
        val reason = (result as SwitchResult.Failed).reason
        assertTrue(
            "保護により切断できない理由が返ること: $reason",
            reason is FailureReason.CannotFreeConnectionSlot,
        )
        val disconnectCommand =
            ((SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.DISCONNECT_DEVICE[1].toInt() and 0xFF)
        assertTrue(
            "保護対象に切断コマンドを送っている",
            disconnectCommand !in transport.sentCommands,
        )
    }

    @Test
    fun `接続状態が不明でも一覧は取得できる`() = runTest {
        // OPPO ではプロファイル API が使えず接続状態が UNKNOWN になるが、
        // 一覧の取得自体は成功する。この組み合わせで切り替えを塞いではならない。
        val transport = SequencedTransport(
            deviceLists = listOf(
                listOf(
                    Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                    Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
                ),
            ),
        )
        val adapter = SoundcoreMultipointAdapter(transport = transport)

        val result = adapter.readTargets(device)

        assertTrue(result is TargetListResult.Available)
        val targets = (result as TargetListResult.Available).targets
        assertEquals(2, targets.size)
        assertEquals(ConnectionStatus.CONNECTED, targets[0].status)
    }

    @Test
    fun `切断が拒否されたら別の端末を試す`() = runTest {
        // 実機（Space A40）では DELLTOWER の切断が拒否され、OPPO は成功した。
        // 1台目で諦めず、次の候補を試すこと。
        val transport = RejectingDisconnectTransport(
            initial = listOf(
                Triple("90:FB:C5:9D:28:F4", "DELLTOWER", true),
                Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
            ),
            // DELLTOWER への切断は拒否する。
            rejectMac = "90:FB:C5:9D:28:F4",
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { "7A:A7:6A:03:DD:08" },
        )

        val result = adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        // DELLTOWER は拒否されたが、OPPO は操作元なので候補から外れる。
        // 結果として切断できる端末が無く、失敗として返るのが正しい。
        assertEquals(SwitchResult.Failed(FailureReason.SwitchUnconfirmed), result)
    }

    @Test
    fun `切断できる端末が無ければ接続せず失敗する`() = runTest {
        // 上限に達していて1台も切断できない場合、接続コマンドを送っても無駄。
        // 送信せずに失敗を返すこと。
        val transport = RejectingDisconnectTransport(
            initial = listOf(
                Triple("90:FB:C5:9D:28:F4", "DELLTOWER", true),
                Triple("7A:A7:6A:03:DD:08", "OPPO Reno13 A", true),
                Triple("00:B3:2C:83:80:D8", "DESKTOP-ILQVMVD", false),
            ),
            rejectMac = "90:FB:C5:9D:28:F4",
        )
        val adapter = SoundcoreMultipointAdapter(
            transport = transport,
            thisDeviceIdProvider = { "7A:A7:6A:03:DD:08" },
        )

        adapter.switchTo(device, target(id = "00:B3:2C:83:80:D8"))

        val connectCommand =
            ((SoundcoreProtocol.Cmd.CONNECT_DEVICE[0].toInt() and 0xFF) shl 8) or
                (SoundcoreProtocol.Cmd.CONNECT_DEVICE[1].toInt() and 0xFF)
        assertTrue(
            "切断できていないのに接続コマンドを送っている",
            connectCommand !in transport.sentCommands,
        )
    }

    /**
     * 特定の端末への切断を拒否するトランスポート。
     *
     * 実機で観測した「切断コマンドに応答がなく、状態も変わらない」挙動を再現する。
     */
    private class RejectingDisconnectTransport(
        initial: List<Triple<String, String, Boolean>>,
        private val rejectMac: String,
    ) : SoundcoreTransport {
        private var devices = initial
        val sentCommands = mutableListOf<Int>()

        override suspend fun exchange(
            mac: String,
            modelCode: String,
            packet: ByteArray,
            expectedCommand: Int?,
            timeoutMillis: Long,
        ): List<ByteArray> {
            val command = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
            sentCommands += command
            val body = packet.copyOfRange(9, packet.size - 1)

            if (command == commandOf(SoundcoreProtocol.Cmd.DISCONNECT_DEVICE) && body.size >= 6) {
                val target = SoundcoreProtocol.formatMac(body, 0)
                if (!target.equals(rejectMac, ignoreCase = true)) {
                    devices = devices.map { (m, n, c) ->
                        Triple(m, n, if (m.equals(target, ignoreCase = true)) false else c)
                    }
                }
            }

            return when (command) {
                SoundcoreProtocol.Rsp.DUAL_DEVICES -> listOf(buildListFrame(devices))
                else -> emptyList()
            }
        }

        private fun commandOf(cmd: ByteArray): Int =
            ((cmd[0].toInt() and 0xFF) shl 8) or (cmd[1].toInt() and 0xFF)

        private fun buildListFrame(entries: List<Triple<String, String, Boolean>>): ByteArray {
            val body = mutableListOf<Byte>()
            body += 1.toByte()
            body += 1.toByte()
            entries.forEach { (mac, name, connected) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val nameField = ByteArray(32)
                nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, 32))
                body += (8 + nameField.size).toByte()
                body += if (connected) 1.toByte() else 0.toByte()
                body += SoundcoreProtocol.parseMac(mac).toList()
                body += nameField.toList()
            }
            val bodyArray = body.toByteArray()
            val total = SoundcoreProtocol.HEADER_LEN + bodyArray.size + 1
            val frame = mutableListOf<Byte>()
            frame += SoundcoreProtocol.DIRECTION_DEVICE_TO_HOST.toList()
            frame += 0x0B.toByte()
            frame += 0x01.toByte()
            frame += (total and 0xFF).toByte()
            frame += ((total shr 8) and 0xFF).toByte()
            frame += bodyArray.toList()
            frame += SoundcoreProtocol.checksum(frame.toByteArray())
            return frame.toByteArray()
        }
    }
}
