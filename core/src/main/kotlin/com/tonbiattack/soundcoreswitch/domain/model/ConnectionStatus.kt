package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 接続状態。
 *
 * 取得できない場合は必ず [UNKNOWN] とし、[DISCONNECTED] と混同しない。
 * 仕様書 F-02「取得不可は『不明』とする」に対応する。
 */
enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}
