package com.tonbiattack.soundcoreswitch.protocol

/**
 * Soundcore のマルチポイント制御プロトコル。
 *
 * 実機（Liberty 4 NC / A3947）で応答を取得して解読した仕様に基づく。
 * 詳細と実測ログは `docs/RESEARCH.md` を参照。
 *
 * このファイルは純粋な Kotlin で、Android にも Windows にも依存しない。
 * トランスポート（RFCOMM ソケット）だけがプラットフォーム依存になる。
 */
object SoundcoreProtocol {

    /** ホスト → デバイスの固定ヘッダ。 */
    val DIRECTION_HOST_TO_DEVICE = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)

    /** デバイス → ホストの固定ヘッダ。 */
    val DIRECTION_DEVICE_TO_HOST = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

    /** ヘッダ長。 */
    const val HEADER_LEN = 9

    /** チェックサムのバイト数。 */
    const val CHECKSUM_LEN = 1

    /** 接続先一覧の1件あたりの最小長（長さ1 + 接続フラグ1 + MAC 6）。 */
    const val MIN_DEVICE_ENTRY_LEN = 8

    /** コマンド。 */
    object Cmd {
        /** 状態取得。 */
        val GET_STATE = byteArrayOf(0x01, 0x01)

        /** 接続先一覧の取得。 */
        val GET_DEVICES = byteArrayOf(0x0B, 0x01)

        /** マルチポイントの有効/無効。body は 1 バイト。 */
        val SET_DUAL_ENABLED = byteArrayOf(0x0B, 0x84.toByte())

        /** 接続先を切断。body は MAC 6 バイト。 */
        val DISCONNECT_DEVICE = byteArrayOf(0x0B, 0x81.toByte())

        /** 接続先へ接続（切り替え）。body は MAC 6 バイト。 */
        val CONNECT_DEVICE = byteArrayOf(0x0B, 0x82.toByte())

        /** 接続先を削除。body は MAC 6 バイト。 */
        val FORGET_DEVICE = byteArrayOf(0x0B, 0x83.toByte())
    }

    /** 応答のコマンドコード（2バイトを 1 つの Int として扱う）。 */
    object Rsp {
        const val STATE = 0x0101
        const val DUAL_DEVICES = 0x0B01
    }

    /**
     * パケットを組み立てる。
     *
     * 長さフィールドは「ヘッダ9 + body + チェックサム1」の合計。
     * チェックサムはチェックサム自身を含まない全バイトの総和の下位8ビット。
     *
     * @param command 2バイトのコマンド。
     * @param body ボディ。空でもよい。
     */
    fun buildPacket(command: ByteArray, body: ByteArray = ByteArray(0)): ByteArray {
        require(command.size == 2) { "コマンドは2バイトである必要があります" }

        val totalLength = HEADER_LEN + body.size + CHECKSUM_LEN
        val packet = ByteArray(totalLength)
        var index = 0

        DIRECTION_HOST_TO_DEVICE.copyInto(packet, index)
        index += DIRECTION_HOST_TO_DEVICE.size
        command.copyInto(packet, index)
        index += command.size

        // 長さはリトルエンディアン。
        packet[index++] = (totalLength and 0xFF).toByte()
        packet[index++] = ((totalLength shr 8) and 0xFF).toByte()

        body.copyInto(packet, index)
        index += body.size

        packet[index] = checksum(packet, 0, index)
        return packet
    }

    /** チェックサムを計算する。 */
    fun checksum(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Byte {
        var sum = 0
        for (i in from until until) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    /**
     * 受信バッファから完全なフレームを取り出す。
     *
     * ストリームは任意の位置で切れるため、ヘッダを探して長さぶん揃ったものだけを返す。
     * チェックサムが合わないフレームは捨てる。
     *
     * @param buffer 受信バッファ。消費したぶんは呼び出し側で捨てる。
     * @return 取り出せたフレームのリスト。各フレームはヘッダ込み。
     */
    fun extractFrames(buffer: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var index = 0

        while (index + HEADER_LEN <= buffer.size) {
            // デバイス→ホストのヘッダを探す。
            if (!matchesAt(buffer, index, DIRECTION_DEVICE_TO_HOST)) {
                index++
                continue
            }

            val length = (buffer[index + 7].toInt() and 0xFF) or
                ((buffer[index + 8].toInt() and 0xFF) shl 8)

            // 長さが不正、またはまだ届いていない。
            if (length < HEADER_LEN + CHECKSUM_LEN) {
                index++
                continue
            }
            if (index + length > buffer.size) break

            val frame = buffer.copyOfRange(index, index + length)
            val expected = checksum(frame, 0, frame.size - CHECKSUM_LEN)
            if (expected == frame[frame.size - 1]) {
                frames += frame
                index += length
            } else {
                // チェックサム不一致。1バイト進めて再同期する。
                index++
            }
        }
        return frames
    }

    /** フレームのコマンドコード（2バイトを Int に）。 */
    fun commandOf(frame: ByteArray): Int {
        require(frame.size >= HEADER_LEN) { "フレームが短すぎます" }
        return ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
    }

    /** フレームの body（ヘッダとチェックサムを除く）。 */
    fun bodyOf(frame: ByteArray): ByteArray {
        require(frame.size >= HEADER_LEN + CHECKSUM_LEN) { "フレームが短すぎます" }
        return frame.copyOfRange(HEADER_LEN, frame.size - CHECKSUM_LEN)
    }

    /**
     * 接続先一覧の body をデコードする。
     *
     * body の先頭2バイトは `総パケット数, このパケットの番号(1始まり)`。
     * 以降は端末ごとに `[長さ1][接続フラグ1][MAC 6][名前]`。
     *
     * @return パケット番号と、含まれる端末のリスト。
     */
    fun decodeDeviceList(body: ByteArray): DeviceListChunk {
        if (body.size < 2) return DeviceListChunk(total = 0, index = 0, devices = emptyList())

        val total = body[0].toInt() and 0xFF
        val index = body[1].toInt() and 0xFF
        val devices = mutableListOf<RawDevice>()

        var pos = 2
        while (pos < body.size) {
            val length = body[pos].toInt() and 0xFF
            // 長さが不正、または body の終端を超える場合は打ち切る。
            if (length < MIN_DEVICE_ENTRY_LEN || pos + length > body.size) break

            val connected = (body[pos + 1].toInt() and 0xFF) == 1
            val mac = formatMac(body, pos + 2)

            // 名前は 0 埋めされている。0 の手前までを UTF-8 として読む。
            val nameStart = pos + 8
            val nameEnd = pos + length
            var nullAt = nameEnd
            for (i in nameStart until nameEnd) {
                if (body[i] == 0.toByte()) {
                    nullAt = i
                    break
                }
            }
            val name = String(body, nameStart, nullAt - nameStart, Charsets.UTF_8)

            devices += RawDevice(mac = mac, name = name, connected = connected)
            pos += length
        }

        return DeviceListChunk(total = total, index = index, devices = devices)
    }

    /** MAC アドレスを `AA:BB:CC:DD:EE:FF` 形式にする。 */
    fun formatMac(bytes: ByteArray, offset: Int): String =
        (0 until 6).joinToString(":") { i ->
            "%02X".format(bytes[offset + i].toInt() and 0xFF)
        }

    /** `AA:BB:CC:DD:EE:FF` 形式の MAC を 6 バイトにする。 */
    fun parseMac(mac: String): ByteArray {
        val cleaned = mac.replace(":", "").replace("-", "")
        require(cleaned.length == 12) { "MAC アドレスの形式が不正です" }
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun matchesAt(buffer: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > buffer.size) return false
        for (i in pattern.indices) {
            if (buffer[offset + i] != pattern[i]) return false
        }
        return true
    }
}

/** 接続先一覧の1パケットぶん。 */
data class DeviceListChunk(
    val total: Int,
    val index: Int,
    val devices: List<RawDevice>,
)

/** プロトコルが返す生の端末情報。 */
data class RawDevice(
    val mac: String,
    val name: String,
    val connected: Boolean,
)
