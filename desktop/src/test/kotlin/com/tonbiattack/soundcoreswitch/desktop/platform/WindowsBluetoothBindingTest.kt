package com.tonbiattack.soundcoreswitch.desktop.platform

import org.junit.Test

/**
 * Win32 バインディングが実際に動くかを確認するための診断テスト。
 *
 * 実機の Bluetooth アダプタに依存するため、環境によって結果は変わる。
 * ここでは「例外を投げずに判定が返る」ことだけを確認する。
 */
class WindowsBluetoothBindingTest {

    @Test
    fun `ラジオの列挙が例外を投げない`() {
        val result = runCatching {
            val params = BluetoothFindRadioParams().apply { dwSize = size() }
            val radio = com.sun.jna.Memory(com.sun.jna.Native.POINTER_SIZE.toLong())
            val find = BthProps.INSTANCE.BluetoothFindFirstRadio(params, radio)
            if (find != null) {
                BthProps.INSTANCE.BluetoothFindRadioClose(find)
                "found"
            } else {
                "not found"
            }
        }
        println("BluetoothFindFirstRadio => $result")
        result.getOrThrow()
    }

    @Test
    fun `デバイス列挙が例外を投げない`() {
        val result = runCatching {
            val params = BluetoothDeviceSearchParams().apply {
                dwSize = size()
                fReturnAuthenticated = 1
                fReturnRemembered = 1
                fReturnConnected = 1
                fReturnUnknown = 0
                fIssueInquiry = 0
            }
            val info = BluetoothDeviceInfo().apply { dwSize = size() }
            val find = BthProps.INSTANCE.BluetoothFindFirstDevice(params, info)
                ?: return@runCatching "no devices"
            val names = mutableListOf<String>()
            do {
                names += "${info.name()} connected=${info.isConnected()}"
            } while (BthProps.INSTANCE.BluetoothFindNextDevice(find, info))
            BthProps.INSTANCE.BluetoothFindDeviceClose(find)
            names.joinToString("; ")
        }
        println("BluetoothFindFirstDevice => $result")
        result.getOrThrow()
    }
}
