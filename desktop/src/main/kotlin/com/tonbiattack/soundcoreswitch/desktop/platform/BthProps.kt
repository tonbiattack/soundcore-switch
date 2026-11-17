package com.tonbiattack.soundcoreswitch.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Windows の Bluetooth API（`bthprops.cpl`）への JNA バインディング。
 *
 * 使う API は公開されている Win32 API のみ。非公開 API やドライバ直接操作は行わない
 * （仕様書 §6「非公開OS APIへの依存は原則回避」）。
 *
 * 参照: https://learn.microsoft.com/windows/win32/api/bluetoothapis/
 */
internal object BthProps {

    /**
     * `bthprops.cpl` を読み込む。
     *
     * JNA は名前に拡張子を補うため、`bthprops.cpl` をそのまま渡すと
     * `bthprops.cpl.dll` を探して失敗する。実ファイルの絶対パスを渡す。
     * 32bit JVM では SysWOW64 側になるため、OS に応じて解決する。
     */
    val INSTANCE: BthPropsApi = Native.load(resolveLibraryPath(), BthPropsApi::class.java)

    /** `bthprops.cpl` の実パスを解決する。 */
    private fun resolveLibraryPath(): String {
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val is64Bit = System.getProperty("os.arch").contains("64")
        val candidates = buildList {
            // 64bit JVM は System32、32bit JVM は SysWOW64 の .cpl を読む。
            if (is64Bit) {
                add("$systemRoot\\System32\\bthprops.cpl")
            } else {
                add("$systemRoot\\SysWOW64\\bthprops.cpl")
                add("$systemRoot\\System32\\bthprops.cpl")
            }
        }
        return candidates.firstOrNull { java.io.File(it).isFile }
            ?: candidates.first()
    }

    /** `BLUETOOTH_MAX_NAME_SIZE`。 */
    const val MAX_NAME_SIZE = 248

    /** `BLUETOOTH_DEVICE_INFO` の `dwSize` に渡す値。 */
    val DEVICE_INFO_SIZE: Int = BluetoothDeviceInfo().size()

    /** `BLUETOOTH_DEVICE_SEARCH_PARAMS` の `dwSize` に渡す値。 */
    val SEARCH_PARAMS_SIZE: Int = BluetoothDeviceSearchParams().size()

    /** `BLUETOOTH_RADIO_INFO` の `dwSize` に渡す値。 */
    val RADIO_INFO_SIZE: Int = BluetoothRadioInfo().size()
}

/**
 * `bthprops.cpl` の関数群。
 *
 * 戻り値の `HBLUETOOTH_*_FIND` は不透明ハンドルなので [Pointer] で受ける。
 * JNA の制約により [Library] を継承する必要がある。
 */
internal interface BthPropsApi : Library {
    fun BluetoothFindFirstRadio(
        params: BluetoothFindRadioParams,
        radio: Pointer,
    ): Pointer?

    fun BluetoothFindNextRadio(find: Pointer, radio: Pointer): Boolean

    fun BluetoothFindRadioClose(find: Pointer): Boolean

    fun BluetoothGetRadioInfo(radio: Pointer, info: BluetoothRadioInfo): Int

    fun BluetoothFindFirstDevice(
        params: BluetoothDeviceSearchParams,
        info: BluetoothDeviceInfo,
    ): Pointer?

    fun BluetoothFindNextDevice(find: Pointer, info: BluetoothDeviceInfo): Boolean

    fun BluetoothFindDeviceClose(find: Pointer): Boolean
}

/** `BLUETOOTH_FIND_RADIO_PARAMS`。 */
@Structure.FieldOrder("dwSize")
internal class BluetoothFindRadioParams : Structure() {
    @JvmField
    var dwSize: Int = 0

    init {
        dwSize = size()
    }
}

/** `BLUETOOTH_ADDRESS`。6 バイトのアドレスと 8 バイトの整数の共用体。 */
@Structure.FieldOrder("ullLong")
internal class BluetoothAddress : Structure() {
    @JvmField
    var ullLong: Long = 0L

    /**
     * `AA:BB:CC:DD:EE:FF` 形式に整形する。
     *
     * Win32 のアドレスは下位 6 バイトにリトルエンディアンで入るため、
     * バイトを取り出して逆順に並べる。
     */
    fun toCanonicalString(): String {
        val bytes = ByteArray(6)
        for (i in 0 until 6) {
            bytes[i] = ((ullLong shr (8 * i)) and 0xFF).toByte()
        }
        return bytes.reversed().joinToString(":") { "%02X".format(it) }
    }
}

/** `BLUETOOTH_RADIO_INFO`。 */
@Structure.FieldOrder("dwSize", "address", "szName", "ulClassofDevice", "lmpSubversion", "manufacturer")
internal class BluetoothRadioInfo : Structure() {
    @JvmField
    var dwSize: Int = 0

    @JvmField
    var address: BluetoothAddress = BluetoothAddress()

    @JvmField
    var szName: CharArray = CharArray(BthProps.MAX_NAME_SIZE)

    @JvmField
    var ulClassofDevice: Int = 0

    @JvmField
    var lmpSubversion: Short = 0

    @JvmField
    var manufacturer: Short = 0

    init {
        dwSize = size()
    }

    /** ラジオ名。 */
    fun name(): String = szName.toNullTerminatedString()
}

/** `SYSTEMTIME`。 */
@Structure.FieldOrder(
    "wYear", "wMonth", "wDayOfWeek", "wDay", "wHour", "wMinute", "wSecond", "wMilliseconds",
)
internal class SystemTime : Structure() {
    @JvmField
    var wYear: Short = 0

    @JvmField
    var wMonth: Short = 0

    @JvmField
    var wDayOfWeek: Short = 0

    @JvmField
    var wDay: Short = 0

    @JvmField
    var wHour: Short = 0

    @JvmField
    var wMinute: Short = 0

    @JvmField
    var wSecond: Short = 0

    @JvmField
    var wMilliseconds: Short = 0
}

/**
 * `BLUETOOTH_DEVICE_INFO`。
 *
 * フィールド順は Win32 の定義に厳密に従う。順序を変えるとサイズが変わり、
 * API が `ERROR_REVISION_MISMATCH` を返す。
 */
@Structure.FieldOrder(
    "dwSize",
    "address",
    "ulClassofDevice",
    "fConnected",
    "fRemembered",
    "fAuthenticated",
    "stLastSeen",
    "stLastUsed",
    "szName",
)
internal class BluetoothDeviceInfo : Structure() {
    @JvmField
    var dwSize: Int = 0

    @JvmField
    var address: BluetoothAddress = BluetoothAddress()

    @JvmField
    var ulClassofDevice: Int = 0

    /** 接続中なら 0 以外。 */
    @JvmField
    var fConnected: Int = 0

    /** ペアリング済みなら 0 以外。 */
    @JvmField
    var fRemembered: Int = 0

    @JvmField
    var fAuthenticated: Int = 0

    @JvmField
    var stLastSeen: SystemTime = SystemTime()

    @JvmField
    var stLastUsed: SystemTime = SystemTime()

    @JvmField
    var szName: CharArray = CharArray(BthProps.MAX_NAME_SIZE)

    init {
        dwSize = size()
    }

    /** 表示名。 */
    fun name(): String = szName.toNullTerminatedString()

    /** 接続中か。 */
    fun isConnected(): Boolean = fConnected != 0

    /** ペアリング済みか。 */
    fun isRemembered(): Boolean = fRemembered != 0
}

/** `BLUETOOTH_DEVICE_SEARCH_PARAMS`。 */
@Structure.FieldOrder(
    "dwSize",
    "fReturnAuthenticated",
    "fReturnRemembered",
    "fReturnUnknown",
    "fReturnConnected",
    "fIssueInquiry",
    "cTimeoutMultiplier",
    "hRadio",
)
internal class BluetoothDeviceSearchParams : Structure() {
    @JvmField
    var dwSize: Int = 0

    @JvmField
    var fReturnAuthenticated: Int = 0

    @JvmField
    var fReturnRemembered: Int = 0

    @JvmField
    var fReturnUnknown: Int = 0

    @JvmField
    var fReturnConnected: Int = 0

    @JvmField
    var fIssueInquiry: Int = 0

    @JvmField
    var cTimeoutMultiplier: Byte = 0

    @JvmField
    var hRadio: Pointer? = null

    init {
        dwSize = size()
    }
}

/** null 終端の `WCHAR[]` を String にする。 */
internal fun CharArray.toNullTerminatedString(): String {
    val end = indexOfFirst { it == '\u0000' }
    val length = if (end < 0) size else end
    return String(this, 0, length)
}
