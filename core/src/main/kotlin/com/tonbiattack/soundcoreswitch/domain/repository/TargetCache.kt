package com.tonbiattack.soundcoreswitch.domain.repository

import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot

/**
 * 接続先一覧のローカルキャッシュ。
 *
 * 仕様書 F-06「初期画面はローカルキャッシュから即描画し、状態更新を非同期に実行する」に対応する。
 * キャッシュは「古い可能性がある」ことを示すために [TargetSnapshot.observedAt] を保持する。
 *
 * 実装は端末内に閉じること。外部送信してはならない（仕様書 §6）。
 */
interface TargetCache {
    /** キャッシュを読む。無ければ null。 */
    suspend fun read(deviceAddress: String): TargetSnapshot?

    /** キャッシュを書く。 */
    suspend fun write(deviceAddress: String, snapshot: TargetSnapshot)

    /** 指定イヤホンのキャッシュを消す。 */
    suspend fun clear(deviceAddress: String)
}
