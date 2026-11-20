package com.tonbiattack.soundcoreswitch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分割パケットの組み立てを検証する。
 *
 * 最重要の性質は「不完全な一覧を完全と誤認しないこと」。
 * 誤認すると、存在する端末を隠したり、誤った切断対象を選んだりする。
 */
class DeviceListAssemblerTest {

    private fun device(mac: String, name: String, connected: Boolean = false) =
        RawDevice(mac = mac, name = name, connected = connected)

    private fun chunk(total: Int, index: Int, devices: List<RawDevice>) =
        DeviceListChunk(total = total, index = index, devices = devices)

    @Test
    fun `すべて揃っていれば完全と判定する`() {
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A"))),
                chunk(2, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
            ),
        )

        assertTrue(snapshot.isComplete)
        assertEquals(2, snapshot.expectedTotal)
        assertEquals(2, snapshot.receivedCount)
        assertEquals(listOf("端末A", "端末B"), snapshot.devices.map { it.name })
    }

    @Test
    fun `受信順が逆でもパケット番号順に並べる`() {
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A"))),
            ),
        )

        assertTrue(snapshot.isComplete)
        assertEquals(listOf("端末A", "端末B"), snapshot.devices.map { it.name })
    }

    @Test
    fun `欠番があれば不完全と判定する`() {
        // 2パケット中1つしか届いていない。
        val snapshot = DeviceListAssembler.assemble(
            listOf(chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A")))),
        )

        assertFalse("欠番は不完全であること", snapshot.isComplete)
        assertEquals(2, snapshot.expectedTotal)
        assertEquals(1, snapshot.receivedCount)
        assertNull("不完全な一覧は返さないこと", snapshot.completeOrNull())
    }

    @Test
    fun `重複したパケットがあれば不完全と判定する`() {
        // 同じ index が2つ。どちらが正しいか決められない。
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A"))),
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:09", "別の端末"))),
                chunk(2, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
            ),
        )

        assertFalse("重複は不完全であること", snapshot.isComplete)
        assertNull(snapshot.completeOrNull())
    }

    @Test
    fun `total が一致しなければ不完全と判定する`() {
        // 別々の応答が混ざった可能性がある。
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A"))),
                chunk(3, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
            ),
        )

        assertFalse("total 不一致は不完全であること", snapshot.isComplete)
        assertNull(snapshot.completeOrNull())
    }

    @Test
    fun `範囲外の index は不完全と判定する`() {
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A"))),
                chunk(2, 5, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
            ),
        )

        assertFalse("範囲外は不完全であること", snapshot.isComplete)
        assertNull(snapshot.completeOrNull())
    }

    @Test
    fun `index が0でも不完全と判定する`() {
        // パケット番号は 1 始まり。
        val snapshot = DeviceListAssembler.assemble(
            listOf(chunk(1, 0, listOf(device("AA:AA:AA:AA:AA:01", "端末A")))),
        )

        assertFalse(snapshot.isComplete)
    }

    @Test
    fun `パケットが空なら取得できていない`() {
        val snapshot = DeviceListAssembler.assemble(emptyList())

        assertFalse(snapshot.isComplete)
        assertEquals(0, snapshot.expectedTotal)
        assertTrue(snapshot.devices.isEmpty())
    }

    @Test
    fun `total が0なら一覧を持たない応答として完全扱いする`() {
        // 接続先が1台も登録されていない場合。
        val snapshot = DeviceListAssembler.assemble(
            listOf(chunk(0, 0, emptyList())),
        )

        assertTrue(snapshot.isComplete)
        assertEquals(0, snapshot.expectedTotal)
        assertTrue(snapshot.devices.isEmpty())
    }

    @Test
    fun `パケット内の端末が空でも完全と判定する`() {
        // 端末を含まないパケットが届くことはある。
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 1, emptyList()),
                chunk(2, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
            ),
        )

        assertTrue(snapshot.isComplete)
        assertEquals(listOf("端末B"), snapshot.devices.map { it.name })
    }

    @Test
    fun `3パケット以上でも正しく組み立てる`() {
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(3, 3, listOf(device("AA:AA:AA:AA:AA:03", "端末C"))),
                chunk(3, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A"))),
                chunk(3, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B"))),
            ),
        )

        assertTrue(snapshot.isComplete)
        assertEquals(listOf("端末A", "端末B", "端末C"), snapshot.devices.map { it.name })
    }

    @Test
    fun `接続状態は組み立てても保たれる`() {
        val snapshot = DeviceListAssembler.assemble(
            listOf(
                chunk(2, 1, listOf(device("AA:AA:AA:AA:AA:01", "端末A", connected = true))),
                chunk(2, 2, listOf(device("AA:AA:AA:AA:AA:02", "端末B", connected = false))),
            ),
        )

        assertTrue(snapshot.isComplete)
        assertTrue(snapshot.devices[0].connected)
        assertFalse(snapshot.devices[1].connected)
    }
}
