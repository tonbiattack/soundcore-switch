package com.tonbiattack.soundcoreswitch.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * プロトコルのパケット生成・解析を検証する。
 *
 * テストデータは **実機（Liberty 4 NC）から実際に取得した応答** を使う。
 * 推測で作った値ではないため、実装が実機の挙動から外れたらここで落ちる。
 */
class SoundcoreProtocolTest {

    @Test
    fun `GET_DEVICES のパケットが実機の送信内容と一致する`() {
        // 実機で送信して応答を得たパケット。
        val expected = byteArrayOf(
            0x08, 0xEE.toByte(), 0x00, 0x00, 0x00,
            0x0B, 0x01,
            0x0A, 0x00,
            0x0C,
        )

        val actual = SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_DEVICES)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `GET_STATE のパケットが実機の送信内容と一致する`() {
        val expected = byteArrayOf(
            0x08, 0xEE.toByte(), 0x00, 0x00, 0x00,
            0x01, 0x01,
            0x0A, 0x00,
            0x02,
        )

        val actual = SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_STATE)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `CONNECT のパケットが実機の送信内容と一致する`() {
        // 実機で送信した切り替えコマンド。
        val expected = byteArrayOf(
            0x08, 0xEE.toByte(), 0x00, 0x00, 0x00,
            0x0B, 0x82.toByte(),
            0x10, 0x00,
            0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x21,
            0xA4.toByte(),
        )

        val actual = SoundcoreProtocol.buildPacket(
            SoundcoreProtocol.Cmd.CONNECT_DEVICE,
            SoundcoreProtocol.parseMac("F0:00:00:00:00:21"),
        )

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `チェックサムは総和の下位8ビット`() {
        val bytes = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00, 0x0B, 0x01, 0x0A, 0x00)
        // 0x08+0xEE+0x0B+0x01+0x0A = 0x10C → 0x0C
        assertEquals(0x0C.toByte(), SoundcoreProtocol.checksum(bytes))
    }

    @Test
    fun `実機の応答フレームを抽出できる`() {
        // 実機から受信した 1 パケット目（ヘッダ + body + チェックサム）。
        val frame = hexToBytes(
            "09 FF 00 00 01 0B 01 AC 00 " +
                "02 01 28 01 F0 00 00 00 00 10 50 69 78 65 6C 20 " +
                "39 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 28 01 F0 00 00 00 " +
                "00 20 44 45 53 4B 54 4F 50 2D 41 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 28 00 F0 00 00 00 00 22 4C 41 50 54 4F 50 " +
                "2D 41 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 28 00 F0 00 00 00 " +
                "00 21 44 45 53 4B 54 4F 50 2D 42 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 43",
        )

        val frames = SoundcoreProtocol.extractFrames(frame)

        assertEquals(1, frames.size)
        assertEquals(SoundcoreProtocol.Rsp.DUAL_DEVICES, SoundcoreProtocol.commandOf(frames[0]))
    }

    @Test
    fun `実機の応答から接続先一覧をデコードできる`() {
        val body = hexToBytes(
            "02 01 28 01 F0 00 00 00 00 10 50 69 78 65 6C 20 " +
                "39 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 28 01 F0 00 00 00 " +
                "00 20 44 45 53 4B 54 4F 50 2D 41 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 28 00 F0 00 00 00 00 22 4C 41 50 54 4F 50 " +
                "2D 41 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 28 00 F0 00 00 00 " +
                "00 21 44 45 53 4B 54 4F 50 2D 42 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00",
        )

        val chunk = SoundcoreProtocol.decodeDeviceList(body)

        assertEquals(2, chunk.total)
        assertEquals(1, chunk.index)
        assertEquals(4, chunk.devices.size)

        assertEquals("F0:00:00:00:00:10", chunk.devices[0].mac)
        assertEquals("Pixel 9", chunk.devices[0].name)
        assertTrue(chunk.devices[0].connected)

        assertEquals("F0:00:00:00:00:20", chunk.devices[1].mac)
        assertEquals("DESKTOP-A", chunk.devices[1].name)
        assertTrue(chunk.devices[1].connected)

        assertEquals("F0:00:00:00:00:22", chunk.devices[2].mac)
        assertEquals("LAPTOP-A", chunk.devices[2].name)
        assertEquals(false, chunk.devices[2].connected)

        assertEquals("F0:00:00:00:00:21", chunk.devices[3].mac)
        assertEquals("DESKTOP-B", chunk.devices[3].name)
        assertEquals(false, chunk.devices[3].connected)
    }

    @Test
    fun `2パケット目の一覧もデコードできる`() {
        val body = hexToBytes(
            "02 02 28 00 F0 00 00 00 00 30 54 61 62 6C 65 74 " +
                "20 41 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 28 00 F0 00 00 00 " +
                "00 41 4C 41 50 54 4F 50 2D 42 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 28 00 F0 00 00 00 00 50 44 45 56 42 4F 41 " +
                "52 44 2D 41 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00",
        )

        val chunk = SoundcoreProtocol.decodeDeviceList(body)

        assertEquals(2, chunk.total)
        assertEquals(2, chunk.index)
        assertEquals(3, chunk.devices.size)
        assertEquals("Tablet A", chunk.devices[0].name)
        assertEquals("LAPTOP-B", chunk.devices[1].name)
        assertEquals("DEVBOARD-A", chunk.devices[2].name)
        assertTrue(chunk.devices.none { it.connected })
    }

    @Test
    fun `壊れた長さのエントリは打ち切る`() {
        // 長さ 0 のエントリは不正。無限ループせず打ち切ること。
        val body = hexToBytes("01 01 00 00 00 00")

        val chunk = SoundcoreProtocol.decodeDeviceList(body)

        assertTrue(chunk.devices.isEmpty())
    }

    @Test
    fun `チェックサムが合わないフレームは捨てる`() {
        val frame = hexToBytes("09 FF 00 00 01 0B 01 0A 00 00")

        val frames = SoundcoreProtocol.extractFrames(frame)

        assertTrue(frames.isEmpty())
    }

    @Test
    fun `分割されたストリームから複数フレームを取り出せる`() {
        // デバイス→ホストのヘッダは 09 FF 00 00 01。総和は 0x11F → チェックサム 0x1F。
        val frame1 = hexToBytes("09 FF 00 00 01 0B 01 0A 00 1F")
        val frame2 = hexToBytes("09 FF 00 00 01 0B 01 0A 00 1F")
        val stream = frame1 + frame2

        val frames = SoundcoreProtocol.extractFrames(stream)

        assertEquals(2, frames.size)
    }

    @Test
    fun `MAC アドレスの変換が往復する`() {
        val mac = "F0:00:00:00:00:21"

        val bytes = SoundcoreProtocol.parseMac(mac)
        val back = SoundcoreProtocol.formatMac(bytes, 0)

        assertEquals(mac, back)
    }

    @Test
    fun `機種コードからベンダー UUID を作れる`() {
        assertEquals(
            "0cf12d31-fac3-4553-bd80-d6832e7b3947",
            SoundcoreUuids.vendorUuid("3947"),
        )
    }

    @Test
    fun `機種 ID から機種コードを引ける`() {
        assertEquals("3947", SoundcoreUuids.modelCodeOf("liberty-4-nc"))
        assertEquals("3936", SoundcoreUuids.modelCodeOf("space-a40"))
        assertEquals(null, SoundcoreUuids.modelCodeOf("unknown-model"))
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
