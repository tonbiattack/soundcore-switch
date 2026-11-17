package com.tonbiattack.soundcoreswitch.protocol

/**
 * Soundcore プロトコルのトランスポート。
 *
 * RFCOMM ソケットの実装だけがプラットフォーム依存になる。
 * Android は `BluetoothSocket`、Windows は Winsock の `AF_BTH` を使う。
 *
 * 実装はベンダー固有 UUID に接続すること。標準 SPP UUID では別のプロトコルが
 * 返り、目的のコマンドは使えない（`docs/RESEARCH.md` §3.1）。
 */
interface SoundcoreTransport {

    /**
     * 接続してコマンドを送り、応答フレームを返す。
     *
     * 接続先一覧は複数パケットに分かれるため、[SoundcoreProtocol.Rsp.DUAL_DEVICES] の
     * 応答が揃うまで待つ実装が望ましい。
     *
     * @param mac イヤホンの MAC アドレス（`AA:BB:CC:DD:EE:FF`）。
     * @param modelCode 機種コード4桁（例: `3947`）。ベンダー UUID の末尾に使う。
     * @param packet 送信するパケット。
     * @param expectedCommand 待つ応答のコマンドコード。null なら任意の応答を待つ。
     * @param timeoutMillis 応答を待つ最大時間。
     * @return 受信したフレーム。応答が無ければ空。
     */
    suspend fun exchange(
        mac: String,
        modelCode: String,
        packet: ByteArray,
        expectedCommand: Int?,
        timeoutMillis: Long,
    ): List<ByteArray>
}

/** トランスポートが失敗したことを表す。 */
class SoundcoreTransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
