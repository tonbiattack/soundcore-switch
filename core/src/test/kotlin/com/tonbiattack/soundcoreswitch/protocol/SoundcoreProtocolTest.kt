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
            0x00, 0xB3.toByte(), 0x2C, 0x83.toByte(), 0x80.toByte(), 0xD8.toByte(),
            0x4D,
        )

        val actual = SoundcoreProtocol.buildPacket(
            SoundcoreProtocol.Cmd.CONNECT_DEVICE,
            SoundcoreProtocol.parseMac("00:B3:2C:83:80:D8"),
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
                "02 01 " +
                "28 01 7A A7 6A 03 DD 08 4F 50 50 4F 20 52 65 6E 6F 31 33 20 41 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 01 90 FB C5 9D 28 F4 44 45 4C 4C 54 4F 57 45 52 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 00 DA 48 59 8A E9 D4 43 58 44 45 50 43 32 36 31 30 30 34 34 34 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 00 00 B3 2C 83 80 D8 44 45 53 4B 54 4F 50 2D 49 4C 51 56 4D 56 44 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "7D",
        )

        val frames = SoundcoreProtocol.extractFrames(frame)

        assertEquals(1, frames.size)
        assertEquals(SoundcoreProtocol.Rsp.DUAL_DEVICES, SoundcoreProtocol.commandOf(frames[0]))
    }

    @Test
    fun `実機の応答から接続先一覧をデコードできる`() {
        val body = hexToBytes(
            "02 01 " +
                "28 01 7A A7 6A 03 DD 08 4F 50 50 4F 20 52 65 6E 6F 31 33 20 41 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 01 90 FB C5 9D 28 F4 44 45 4C 4C 54 4F 57 45 52 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 00 DA 48 59 8A E9 D4 43 58 44 45 50 43 32 36 31 30 30 34 34 34 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 00 00 B3 2C 83 80 D8 44 45 53 4B 54 4F 50 2D 49 4C 51 56 4D 56 44 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        )

        val chunk = SoundcoreProtocol.decodeDeviceList(body)

        assertEquals(2, chunk.total)
        assertEquals(1, chunk.index)
        assertEquals(4, chunk.devices.size)

        assertEquals("7A:A7:6A:03:DD:08", chunk.devices[0].mac)
        assertEquals("OPPO Reno13 A", chunk.devices[0].name)
        assertTrue(chunk.devices[0].connected)

        assertEquals("90:FB:C5:9D:28:F4", chunk.devices[1].mac)
        assertEquals("DELLTOWER", chunk.devices[1].name)
        assertTrue(chunk.devices[1].connected)

        assertEquals("DA:48:59:8A:E9:D4", chunk.devices[2].mac)
        assertEquals("CXDEPC26100444", chunk.devices[2].name)
        assertEquals(false, chunk.devices[2].connected)

        assertEquals("00:B3:2C:83:80:D8", chunk.devices[3].mac)
        assertEquals("DESKTOP-ILQVMVD", chunk.devices[3].name)
        assertEquals(false, chunk.devices[3].connected)
    }

    @Test
    fun `2パケット目の一覧もデコードできる`() {
        val body = hexToBytes(
            "02 02 " +
                "28 00 8F A5 32 05 AE D0 52 65 64 6D 69 20 50 61 64 20 53 45 20 38 2E 37 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 00 82 B0 C1 77 6D FC 4D 50 43 31 34 34 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "28 00 25 65 08 37 A3 64 52 45 44 50 49 4E 45 5F 47 41 54 54 5F 56 30 32 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        )

        val chunk = SoundcoreProtocol.decodeDeviceList(body)

        assertEquals(2, chunk.total)
        assertEquals(2, chunk.index)
        assertEquals(3, chunk.devices.size)
        assertEquals("Redmi Pad SE 8.7", chunk.devices[0].name)
        assertEquals("MPC144", chunk.devices[1].name)
        assertEquals("REDPINE_GATT_V02", chunk.devices[2].name)
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
        val mac = "00:B3:2C:83:80:D8"

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
