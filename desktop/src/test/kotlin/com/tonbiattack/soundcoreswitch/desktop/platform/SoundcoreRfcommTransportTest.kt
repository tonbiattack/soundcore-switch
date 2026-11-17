package com.tonbiattack.soundcoreswitch.desktop.platform

import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransportException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFCOMM トランスポートのうち、実機に依存しない部分を検証する。
 *
 * 実際の接続はイヤホンが必要なため、ここでは
 * 「不正な入力で例外を投げ、MAC アドレスを例外メッセージに含めない」ことを確認する。
 */
class SoundcoreRfcommTransportTest {

    private val transport = SoundcoreRfcommTransport()

    @Test
    fun `不正な MAC アドレスは例外になり、値がメッセージに含まれない`() = runTest {
        val badMac = "ZZ:ZZ:ZZ:ZZ:ZZ:ZZ"
        val result = runCatching {
            transport.exchange(
                mac = badMac,
                modelCode = "3947",
                packet = SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_DEVICES),
                expectedCommand = SoundcoreProtocol.Rsp.DUAL_DEVICES,
                timeoutMillis = 100,
            )
        }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        // 仕様書 §6「通信ログには秘密情報を記録しない」。MAC を例外に載せない。
        assertTrue("例外メッセージに MAC が含まれている: $message", !message.contains(badMac))
    }

    @Test
    fun `未対応の機種コードは UUID を作れず例外になる`() = runTest {
        val result = runCatching {
            transport.exchange(
                mac = "F4:9D:8A:4D:43:B6",
                modelCode = "",
                packet = SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_DEVICES),
                expectedCommand = SoundcoreProtocol.Rsp.DUAL_DEVICES,
                timeoutMillis = 100,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SoundcoreTransportException)
    }

    @Test
    fun `接続できないときは SoundcoreTransportException になる`() = runTest {
        // 到達しない MAC を指定する。実機が無くても「例外の型」は検証できる。
        val result = runCatching {
            transport.exchange(
                mac = "00:00:00:00:00:01",
                modelCode = "3947",
                packet = SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_DEVICES),
                expectedCommand = SoundcoreProtocol.Rsp.DUAL_DEVICES,
                timeoutMillis = 500,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(
            "想定外の例外型: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is SoundcoreTransportException,
        )
    }

    @Test
    fun `Winsock の初期化に成功している`() = runTest {
        // WSAStartup が失敗すると全ての通信が使えなくなるため、起動時に確認する。
        val result = runCatching {
            transport.exchange(
                mac = "00:00:00:00:00:01",
                modelCode = "3947",
                packet = ByteArray(0),
                expectedCommand = null,
                timeoutMillis = 100,
            )
        }
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "Winsock の初期化に失敗している: $message",
            !message.contains("Winsock"),
        )
    }
}
