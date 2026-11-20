package com.tonbiattack.soundcoreswitch.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 診断ログに識別情報が出ないことを検証する。
 *
 * 仕様書 §6「通信ログには秘密情報を記録しない」。
 * Bluetooth アドレスと端末名は個人を特定し得るため、そのまま出してはならない。
 */
class LogRedactionTest {

    private val mac = "F0:00:00:00:00:10"
    private val deviceName = "Pixel 9"

    @Test
    fun `MAC アドレスは復元できない形になる`() {
        val redacted = LogRedaction.deviceId(mac)

        assertFalse("MAC がそのまま出ている", redacted.contains(mac))
        assertFalse("MAC の全体が含まれている", redacted.contains("7A:A7:6A"))
        // 同じアドレスは同じ ID になる（ログ内で対応を追える）。
        assertTrue(redacted.startsWith("dev-"))
    }

    @Test
    fun `同じ MAC は同じ匿名 ID になる`() {
        assertTrue(LogRedaction.deviceId(mac) == LogRedaction.deviceId(mac))
    }

    @Test
    fun `異なる MAC は異なる匿名 ID になる`() {
        val other = "F0:00:00:00:00:20"

        assertFalse(
            "別の端末が同じ ID になっている",
            LogRedaction.deviceId(mac) == LogRedaction.deviceId(other),
        )
    }

    @Test
    fun `MAC が無ければ不明として扱う`() {
        assertTrue(LogRedaction.deviceId(null).startsWith("dev-"))
        assertTrue(LogRedaction.deviceId("").startsWith("dev-"))
    }

    @Test
    fun `端末名は伏せられる`() {
        val redacted = LogRedaction.deviceName(deviceName)

        assertFalse("端末名がそのまま出ている", redacted.contains(deviceName))
        assertFalse("端末名の一部が出ている", redacted.contains("OPPO"))
        assertFalse("端末名の一部が出ている", redacted.contains("Reno"))
        // 長さは残す（空でないことは分かる）。
        assertTrue(redacted.contains("len="))
    }

    @Test
    fun `応答 body は長さだけになる`() {
        // MAC と端末名を含む生データを模す。
        val body = byteArrayOf(0x28, 0x01, 0x7A, 0xA7.toByte(), 0x6A, 0x03, 0xDD.toByte(), 0x08) +
            "Pixel 9".toByteArray()

        val redacted = LogRedaction.body(body)

        assertFalse("生データがそのまま出ている", redacted.contains("7A"))
        assertTrue(redacted.contains("len=${body.size}"))
    }

    @Test
    fun `例外は種類だけになる`() {
        val error = IllegalStateException("MAC F0:00:00:00:00:10 で失敗")

        val redacted = LogRedaction.error(error)

        assertFalse("例外メッセージがそのまま出ている", redacted.contains("7A:A7:6A"))
        assertTrue(redacted.contains("IllegalStateException"))
    }

    @Test
    fun `接続先一覧は件数だけになる`() {
        val devices = listOf(
            RawDevice(mac = mac, name = deviceName, connected = true),
            RawDevice(mac = "F0:00:00:00:00:20", name = "DESKTOP-A", connected = false),
        )

        val redacted = LogRedaction.deviceList(devices)

        assertFalse("MAC が出ている", redacted.contains("7A:A7"))
        assertFalse("端末名が出ている", redacted.contains("OPPO"))
        assertFalse("端末名が出ている", redacted.contains("DESKTOP-A"))
        assertTrue("件数は残す", redacted.contains("total=2"))
        assertTrue("接続中の件数は残す", redacted.contains("connected=1"))
    }

    @Test
    fun `取得できなかった一覧も安全に表せる`() {
        val redacted = LogRedaction.deviceList(null)

        assertTrue(redacted.contains("unavailable"))
    }
}
