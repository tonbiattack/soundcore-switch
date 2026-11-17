package com.tonbiattack.soundcoreswitch.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransport
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransportException
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreUuids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/** `AF_BTH`。Bluetooth のアドレスファミリ。 */
private const val AF_BTH = 32

/** `SOCK_STREAM`。 */
private const val SOCK_STREAM = 1

/** `BTHPROTO_RFCOMM`。 */
private const val BTHPROTO_RFCOMM = 3

/** `SOL_SOCKET`。 */
private const val SOL_SOCKET = 0xFFFF

/** `SO_RCVTIMEO`。Windows では値がミリ秒の `DWORD`。 */
private const val SO_RCVTIMEO = 0x1006

/** `SOCKET_ERROR`。 */
private const val SOCKET_ERROR = -1

/** `INVALID_SOCKET`。 */
private const val INVALID_SOCKET = -1L

/** `WSAETIMEDOUT`。 */
private const val WSAETIMEDOUT = 10060

/** `WSAEWOULDBLOCK`。 */
private const val WSAEWOULDBLOCK = 10035

/** `MAKEWORD(2, 2)`。Winsock 2.2 を要求する。 */
private const val WSA_VERSION: Short = 0x0202

/** `WSADATA` を受ける領域。実際のサイズは 400 バイト弱なので余裕を持たせる。 */
private const val WSADATA_SIZE = 512L

/** 1回の `recv` で受ける最大バイト数。 */
private const val RECEIVE_BUFFER_SIZE = 1024

/** 受信バッファの上限。壊れたストリームで無限に伸びるのを防ぐ。 */
private const val MAX_ACCUMULATED_BYTES = 64 * 1024

/** 応答を使わないコマンドで待つ上限。 */
private const val FIRE_AND_FORGET_TIMEOUT_MILLIS = 1_000L

/** MAC アドレスの16進桁数。 */
private const val MAC_HEX_LENGTH = 12

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * `SOCKADDR_BTH` のサイズ。
 *
 * `addressFamily`(2) + `btAddr`(8) + `serviceClassId`(16) + `port`(4) = 30 バイト。
 * 実機検証済みの定義（`Pack = 1`）に合わせ、アラインメントの詰め物は入れない。
 */
private const val SOCKADDR_BTH_SIZE = 30

/** `SOCKADDR_BTH.btAddr` のオフセット。 */
private const val OFFSET_BT_ADDR = 2L

/** `SOCKADDR_BTH.serviceClassId` のオフセット。 */
private const val OFFSET_SERVICE_CLASS_ID = 10L

/** Win32 の `GUID` のバイト数。 */
private const val GUID_SIZE = 16

/**
 * Winsock の `AF_BTH` ソケットで RFCOMM 接続する [SoundcoreTransport] の実装。
 *
 * 標準 SPP UUID ではなく**ベンダー固有 UUID** に接続する（`docs/RESEARCH.md` §3.1）。
 * 接続手順は実機で検証済みのものに合わせている:
 *
 * ```c
 * SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
 * SOCKADDR_BTH sa = { AF_BTH, <MAC を16進として解釈した ulong>, <ベンダー UUID>, 0 };
 * connect(s, &sa, sizeof(sa));
 * ```
 *
 * 方針:
 * - 1回の [exchange] ごとに接続を開いて閉じる。使い回すとストリームの読み残しで
 *   次の応答がずれるため、必ず新しいソケットにする。
 * - ソケットは `try/finally` で必ず `closesocket` する。
 * - 失敗は [SoundcoreTransportException] に変換する。例外メッセージに MAC アドレスを
 *   含めない（仕様書 §6「通信ログには秘密情報を記録しない」）。
 * - 受信は `SO_RCVTIMEO` と全体の期限の二重で打ち切る。無限ループを作らない。
 */
class SoundcoreRfcommTransport : SoundcoreTransport {

    override suspend fun exchange(
        mac: String,
        modelCode: String,
        packet: ByteArray,
        expectedCommand: Int?,
        timeoutMillis: Long,
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        val socket = openSocket(mac, modelCode)
        try {
            sendPacket(socket, packet)
            receiveFrames(socket, expectedCommand, timeoutMillis)
        } finally {
            // 失敗経路でも必ず閉じる。閉じ忘れると RFCOMM のチャネルが枯渇する。
            Winsock.api.closesocket(socket)
        }
    }

    /**
     * RFCOMM の接続を開く。
     *
     * @return 接続済みのソケット。失敗時は [SoundcoreTransportException]。
     */
    private fun openSocket(mac: String, modelCode: String): Long {
        if (Winsock.startupError != 0) {
            throw SoundcoreTransportException(
                "Winsock を初期化できませんでした (code=${Winsock.startupError})",
            )
        }

        val socket = Winsock.api.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (socket == INVALID_SOCKET) {
            throw SoundcoreTransportException(
                "RFCOMM ソケットを作成できませんでした (code=${Winsock.api.WSAGetLastError()})",
            )
        }

        // 以降の失敗では必ずソケットを閉じる。開いたまま例外を抜けるとチャネルが残る。
        try {
            val endpoint = buildSockaddrBth(
                mac = mac,
                serviceClassId = SoundcoreUuids.vendorUuid(modelCode),
            )

            val result = Winsock.api.connect(socket, endpoint, SOCKADDR_BTH_SIZE)
            if (result != 0) {
                val error = Winsock.api.WSAGetLastError()
                throw SoundcoreTransportException("RFCOMM 接続に失敗しました (code=$error)")
            }
        } catch (e: Throwable) {
            Winsock.api.closesocket(socket)
            throw e
        }
        return socket
    }

    /**
     * `SOCKADDR_BTH` を組み立てる。
     *
     * 実機検証済みの定義（`Pack = 1`）に厳密に合わせ、バイト列を直接書く。
     * JNA の `Structure` はアラインメントを挿入するため、`btAddr` の位置がずれて
     * `connect` が失敗し得る。ここでは自前で並べる。
     *
     * ```
     * ushort addressFamily;  // offset 0
     * ulong  btAddr;         // offset 2
     * GUID   serviceClassId; // offset 10
     * uint   port;           // offset 26
     * ```
     */
    private fun buildSockaddrBth(mac: String, serviceClassId: String): Memory {
        val buffer = Memory(SOCKADDR_BTH_SIZE.toLong())
        buffer.clear()

        // addressFamily（リトルエンディアンの ushort）。
        buffer.setShort(0L, AF_BTH.toShort())
        // btAddr（リトルエンディアンの ulong）。
        buffer.setLong(OFFSET_BT_ADDR, parseBluetoothAddress(mac))
        // serviceClassId（GUID 16 バイト）。
        buffer.write(OFFSET_SERVICE_CLASS_ID, guidBytes(serviceClassId), 0, GUID_SIZE)
        // port は 0 のまま（RFCOMM はサービス UUID で解決する）。
        return buffer
    }

    /** パケットを送り切る。`send` は部分書き込みがあり得るためループする。 */
    private fun sendPacket(socket: Long, packet: ByteArray) {
        if (packet.isEmpty()) return

        val buffer = Memory(packet.size.toLong())
        buffer.write(0L, packet, 0, packet.size)

        var offset = 0
        while (offset < packet.size) {
            val written = Winsock.api.send(
                socket,
                buffer.share(offset.toLong()),
                packet.size - offset,
                0,
            )
            if (written == SOCKET_ERROR) {
                throw SoundcoreTransportException(
                    "送信に失敗しました (code=${Winsock.api.WSAGetLastError()})",
                )
            }
            if (written <= 0) throw SoundcoreTransportException("送信できませんでした")
            offset += written
        }
    }

    /**
     * 応答フレームを集める。
     *
     * [expectedCommand] が指定された場合、そのコマンドのフレームが揃うまで受信を続ける。
     * 接続先一覧は複数パケットに分かれるため、[SoundcoreProtocol.Rsp.DUAL_DEVICES] では
     * `DeviceListChunk.total` に達するまで待つ。
     *
     * [expectedCommand] が null のコマンド（切断・接続要求）は応答を使わないため、
     * 最初のフレームが届いた時点で打ち切る。届かなければ短い待ち時間で諦める。
     * 待ち時間を [timeoutMillis] いっぱいにすると、切り替え1回で十数秒かかってしまう。
     */
    private fun receiveFrames(
        socket: Long,
        expectedCommand: Int?,
        timeoutMillis: Long,
    ): List<ByteArray> {
        val waitMillis = if (expectedCommand == null) {
            minOf(timeoutMillis, FIRE_AND_FORGET_TIMEOUT_MILLIS)
        } else {
            timeoutMillis
        }
        val deadline = System.nanoTime() + waitMillis * NANOS_PER_MILLI

        val accumulator = ByteArrayOutputStream()
        val received = mutableListOf<ByteArray>()
        var processedFrames = 0

        while (true) {
            val remainingMillis = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (remainingMillis <= 0) break
            applyReceiveTimeout(socket, remainingMillis)

            val chunk = Memory(RECEIVE_BUFFER_SIZE.toLong())
            val read = Winsock.api.recv(socket, chunk, RECEIVE_BUFFER_SIZE, 0)
            if (read == SOCKET_ERROR) {
                val error = Winsock.api.WSAGetLastError()
                // 受信タイムアウトは失敗ではなく「これ以上来なかった」として扱う。
                if (error == WSAETIMEDOUT || error == WSAEWOULDBLOCK) break
                throw SoundcoreTransportException("受信に失敗しました (code=$error)")
            }
            // 0 は相手からの正常な切断。
            if (read == 0) break

            val bytes = chunk.getByteArray(0L, read)
            accumulator.write(bytes, 0, bytes.size)
            if (accumulator.size() > MAX_ACCUMULATED_BYTES) break

            // バッファは先頭から再解析する。既に取り出したぶんは件数で読み飛ばす。
            // 解析は前置きに対して安定なので、件数は単調に増える。
            val frames = SoundcoreProtocol.extractFrames(accumulator.toByteArray())
            for (frame in frames.drop(processedFrames)) {
                if (expectedCommand == null ||
                    SoundcoreProtocol.commandOf(frame) == expectedCommand
                ) {
                    received += frame
                }
            }
            processedFrames = frames.size

            if (isSatisfied(received, expectedCommand)) break
        }
        return received
    }

    /** これ以上待つ必要があるか。 */
    private fun isSatisfied(received: List<ByteArray>, expectedCommand: Int?): Boolean {
        if (received.isEmpty()) return false
        if (expectedCommand != SoundcoreProtocol.Rsp.DUAL_DEVICES) return true

        val chunks = received.map {
            SoundcoreProtocol.decodeDeviceList(SoundcoreProtocol.bodyOf(it))
        }
        val total = chunks.first().total
        // 総数が読めない応答は分割されていないとみなす。
        if (total <= 0) return true
        return chunks.map { it.index }.toSet().size >= total
    }

    /** 受信タイムアウトを設定する。失敗しても致命的ではないため戻り値は見ない。 */
    private fun applyReceiveTimeout(socket: Long, timeoutMillis: Long) {
        val value = IntByReference(
            timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
        )
        Winsock.api.setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, value, Int.SIZE_BYTES)
    }

    /**
     * `AA:BB:CC:DD:EE:FF` 形式の MAC を `SOCKADDR_BTH.btAddr` の値にする。
     *
     * 実機で検証したとおり、MAC の16進表記をそのまま整数として解釈する。
     */
    private fun parseBluetoothAddress(mac: String): Long {
        val cleaned = mac.replace(":", "").replace("-", "")
        if (cleaned.length != MAC_HEX_LENGTH) {
            throw SoundcoreTransportException("MAC アドレスの形式が不正です")
        }
        return try {
            cleaned.toLong(16)
        } catch (e: NumberFormatException) {
            throw SoundcoreTransportException("MAC アドレスの形式が不正です", e)
        }
    }

    /**
     * ベンダー UUID を Win32 の `GUID` のバイト列にする。
     *
     * `GUID` は `Data1`(uint32) / `Data2`(uint16) / `Data3`(uint16) を
     * **リトルエンディアン**で並べ、続けて `Data4`(8 バイト) をそのまま置く。
     * UUID 文字列の16進をそのまま並べると `Data1` が逆順になり、
     * Windows 側で別のサービスとして扱われる。
     */
    private fun guidBytes(uuid: String): ByteArray {
        val parsed = try {
            UUID.fromString(uuid)
        } catch (e: IllegalArgumentException) {
            throw SoundcoreTransportException("ベンダー UUID の形式が不正です", e)
        }
        val most = parsed.mostSignificantBits
        val least = parsed.leastSignificantBits

        val data1 = (most shr 32).toInt()
        val data2 = ((most shr 16) and 0xFFFF).toInt()
        val data3 = (most and 0xFFFF).toInt()

        return ByteArray(GUID_SIZE) { index ->
            when (index) {
                // Data1: リトルエンディアンの 4 バイト。
                in 0..3 -> ((data1 shr (8 * index)) and 0xFF).toByte()
                // Data2: リトルエンディアンの 2 バイト。
                in 4..5 -> ((data2 shr (8 * (index - 4))) and 0xFF).toByte()
                // Data3: リトルエンディアンの 2 バイト。
                in 6..7 -> ((data3 shr (8 * (index - 6))) and 0xFF).toByte()
                // Data4: 文字列の後半16桁をそのまま並べる。
                else -> ((least shr (8 * (15 - index))) and 0xFF).toByte()
            }
        }
    }
}

/**
 * Winsock（`ws2_32.dll`）の関数群。
 *
 * `SOCKET` は `UINT_PTR` なので 64bit 環境を前提に `Long` で受ける。
 * このアプリの配布対象は Windows x64 のみ。
 */
internal interface Ws2_32 : Library {
    fun WSAStartup(wVersionRequested: Short, lpWSAData: Pointer): Int

    fun WSAGetLastError(): Int

    fun socket(af: Int, type: Int, protocol: Int): Long

    fun connect(s: Long, name: Pointer, namelen: Int): Int

    fun send(s: Long, buf: Pointer, len: Int, flags: Int): Int

    fun recv(s: Long, buf: Pointer, len: Int, flags: Int): Int

    fun closesocket(s: Long): Int

    fun setsockopt(s: Long, level: Int, optname: Int, optval: IntByReference, optlen: Int): Int
}

/**
 * Winsock の初期化を1回だけ行う。
 *
 * `WSAStartup` はプロセスで1回呼べばよい。`WSACleanup` はプロセス終了時に OS が
 * 解放するため呼ばない。
 */
private object Winsock {

    /** `ws2_32.dll` を読み込む。 */
    val api: Ws2_32 = Native.load("ws2_32", Ws2_32::class.java)

    /** `WSAStartup` の戻り値。0 以外ならソケットを使えない。 */
    val startupError: Int = run {
        val data = Memory(WSADATA_SIZE)
        api.WSAStartup(WSA_VERSION, data)
    }
}
