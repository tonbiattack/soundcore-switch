package com.tonbiattack.soundcoreswitch.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransport
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransportException
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreUuids
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android の [BluetoothSocket] で RFCOMM 接続するトランスポート。
 *
 * 実機（Liberty 4 NC / A3947）で確認した接続方法に従う（`docs/RESEARCH.md` §3.1）。
 * - **ベンダー固有 UUID** に接続する。標準 SPP UUID (`00001101-...`) では別のプロトコルが
 *   返り、目的のコマンドは使えない。
 * - ペアリング済みを前提とするため、安全な接続
 *   （[android.bluetooth.BluetoothDevice.createRfcommSocketToServiceRecord]）を使う。
 *   非安全な接続（`createInsecureRfcommSocketToServiceRecord`）は使わない。
 * - 接続前に [BluetoothAdapter.cancelDiscovery] を呼ぶ。発見中は接続が遅くなる、または失敗する。
 *
 * 通信は [exchange] ごとに接続を開いて閉じる。イヤホンはコマンドごとに接続を受け付けるため、
 * ソケットを状態として持ち越さない。
 *
 * **権限**: 呼び出し前に `BLUETOOTH_CONNECT` が許可されていることを確認済みであること。
 * 確認は [com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker]
 * （[AndroidBluetoothAvailabilityChecker]）と
 * [com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase] が行う。
 * 本クラスは権限を確認しないため、Lint の `MissingPermission` を抑制している。
 *
 * **ログ**: MAC アドレスは出力しない（仕様書 §6「通信ログには秘密情報を記録しない」）。
 *
 * @param context アプリケーションコンテキスト。Activity を渡すとリークする。
 */
class AndroidSoundcoreTransport(
    private val context: Context,
) : SoundcoreTransport {

    override suspend fun exchange(
        mac: String,
        modelCode: String,
        packet: ByteArray,
        expectedCommand: Int?,
        timeoutMillis: Long,
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        try {
            exchangeBlocking(mac, modelCode, packet, expectedCommand, timeoutMillis)
        } catch (e: CancellationException) {
            // コルーチンのキャンセルは失敗理由に変換せず、そのまま伝播させる。
            throw e
        } catch (e: SoundcoreTransportException) {
            throw e
        } catch (e: Exception) {
            // 例外メッセージに MAC アドレスや内部情報を混ぜない。
            throw SoundcoreTransportException("RFCOMM 通信に失敗しました", e)
        }
    }

    // 権限は呼び出し前に確認済み（クラス KDoc 参照）。Lint はその関連を追えないため抑制する。
    @SuppressLint("MissingPermission")
    private suspend fun exchangeBlocking(
        mac: String,
        modelCode: String,
        packet: ByteArray,
        expectedCommand: Int?,
        timeoutMillis: Long,
    ): List<ByteArray> {
        val adapter = bluetoothAdapter()
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            throw SoundcoreTransportException("MAC アドレスの形式が不正です", e)
        }
        val uuid = vendorUuidOf(modelCode)

        // 発見中は接続が遅くなる、または失敗するため必ず止める。
        // cancelDiscovery は Android 12 以降 BLUETOOTH_SCAN を要求するが、未許可でも
        // 接続自体は続行できるため、失敗は無視する（本アプリは位置情報目的で使わない）。
        runCatching { adapter.cancelDiscovery() }

        val socket = try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            throw SoundcoreTransportException("RFCOMM ソケットを作成できません", e)
        }

        return try {
            try {
                socket.connect()
            } catch (e: IOException) {
                throw SoundcoreTransportException("RFCOMM 接続に失敗しました", e)
            }

            try {
                val output = socket.outputStream
                output.write(packet)
                output.flush()
            } catch (e: IOException) {
                throw SoundcoreTransportException("コマンドを送信できません", e)
            }

            receiveFrames(socket, expectedCommand, timeoutMillis)
        } finally {
            // 受信を止めるためにも必ず閉じる。閉じ忘れると読み取りが残り続ける。
            runCatching { socket.close() }
        }
    }

    /**
     * 応答フレームを受信する。
     *
     * [BluetoothSocket] の読み取りはブロッキングで、コルーチンのキャンセルでは中断できない。
     * そのためタイムアウトを見張るコルーチンからソケットを閉じ、`read` を [IOException] で
     * 戻すことで受信を打ち切る。無限に待たないための仕組みである。
     *
     * @param expectedCommand 待つ応答のコマンドコード。null なら応答を待たない。
     * @param timeoutMillis 応答を待つ最大時間。
     * @return 受信したフレーム。応答を待たない場合は空になり得る。
     */
    private suspend fun receiveFrames(
        socket: BluetoothSocket,
        expectedCommand: Int?,
        timeoutMillis: Long,
    ): List<ByteArray> = coroutineScope {
        // 応答を待たないコマンドでも、送信直後に切断すると届かない実装があるため
        // 短い猶予だけ受信する。待ちすぎると切り替えが遅くなるので上限を設ける。
        val waitMillis = if (expectedCommand == null) {
            minOf(timeoutMillis, FIRE_AND_FORGET_GRACE_MILLIS)
        } else {
            timeoutMillis
        }

        val watchdog = launch {
            delay(waitMillis)
            runCatching { socket.close() }
        }

        val accumulated = ByteArrayOutputStream()
        val frames = mutableListOf<ByteArray>()
        var seen = 0

        try {
            val input = socket.inputStream
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    // タイムアウトで閉じた、または相手から切断された。
                    // 受信済みのフレームは活かす。
                    break
                }
                if (read < 0) break
                if (read == 0) continue

                seen = appendFrames(buffer.copyOf(read), accumulated, frames, seen)

                val done = if (expectedCommand == null) {
                    frames.isNotEmpty()
                } else {
                    isResponseComplete(expectedCommand, frames)
                }
                if (done) break
            }
        } finally {
            watchdog.cancel()
        }

        if (expectedCommand != null && frames.none { commandOf(it) == expectedCommand }) {
            // 待っていた応答が1件も無い。部分的な結果も無いため失敗として扱う。
            throw SoundcoreTransportException("応答が時間内に返りませんでした")
        }

        Log.d(TAG, "受信フレーム ${frames.size} 件")
        frames.toList()
    }

    /**
     * 受信した断片を蓄積し、新たに取り出せたフレームを [frames] に追加する。
     *
     * [SoundcoreProtocol.extractFrames] はバッファの先頭から走査するため、バッファを
     * 伸ばしていけば「前回までに取れたフレーム」は必ず先頭からの同じ並びになる。
     * そのため既出の件数だけ読み飛ばせばよい。
     *
     * @return 次回に読み飛ばすべきフレーム数。
     */
    private fun appendFrames(
        chunk: ByteArray,
        accumulated: ByteArrayOutputStream,
        frames: MutableList<ByteArray>,
        alreadySeen: Int,
    ): Int {
        accumulated.write(chunk)
        val all = SoundcoreProtocol.extractFrames(accumulated.toByteArray())
        if (all.size > alreadySeen) {
            frames.addAll(all.subList(alreadySeen, all.size))
        }
        return all.size
    }

    /**
     * 待っている応答が揃ったか。
     *
     * 接続先一覧は複数パケットに分かれるため、[com.tonbiattack.soundcoreswitch.protocol.DeviceListChunk.total]
     * に達するまで待つ。それ以外のコマンドは1件届けば揃ったとみなす。
     */
    private fun isResponseComplete(expectedCommand: Int, frames: List<ByteArray>): Boolean {
        val matching = frames.filter { commandOf(it) == expectedCommand }
        if (matching.isEmpty()) return false
        if (expectedCommand != SoundcoreProtocol.Rsp.DUAL_DEVICES) return true

        val chunks = matching.map {
            SoundcoreProtocol.decodeDeviceList(SoundcoreProtocol.bodyOf(it))
        }
        val total = chunks.first().total
        if (total <= 0) return true

        val received = chunks.mapTo(mutableSetOf()) { it.index }
        return (1..total).all { it in received }
    }

    /** フレームのコマンドコード。 */
    private fun commandOf(frame: ByteArray): Int = SoundcoreProtocol.commandOf(frame)

    /** Bluetooth アダプタを取得する。無ければ失敗として扱う。 */
    private fun bluetoothAdapter(): BluetoothAdapter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
            ?: throw SoundcoreTransportException("Bluetooth を利用できません")
    }

    /** 機種コードからベンダー UUID を組み立てる。 */
    private fun vendorUuidOf(modelCode: String): UUID = try {
        UUID.fromString(SoundcoreUuids.vendorUuid(modelCode))
    } catch (e: IllegalArgumentException) {
        throw SoundcoreTransportException("ベンダー UUID を組み立てられません", e)
    }

    private companion object {
        const val TAG = "SoundcoreTransport"

        /** 受信バッファのサイズ。1フレームは最大でも数百バイト。 */
        const val READ_BUFFER_SIZE = 1024

        /**
         * 応答を待たないコマンドで、応答が来ない場合に待つ猶予。
         *
         * 実機では切り替えコマンドにも応答が返るため、通常は最初のフレームで即座に終わる。
         * 応答が無い機種・コマンドで毎回タイムアウトぶん待つと切り替えが遅くなるため上限を設ける。
         */
        const val FIRE_AND_FORGET_GRACE_MILLIS = 500L
    }
}
