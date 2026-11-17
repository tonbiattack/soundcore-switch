package com.tonbiattack.soundcoreswitch.domain.repository

import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot

/**
 * 接続先一覧の取得口。キャッシュと機種アダプタをまとめる。
 *
 * 上位層（ViewModel）はこのインターフェースだけに依存し、機種差分を知らない。
 */
interface TargetRepository {
    /**
     * キャッシュを読む。無ければ null。
     * 起動直後の即描画に使う（仕様書 F-06）。
     */
    suspend fun readCached(device: EarbudsDevice): TargetSnapshot?

    /**
     * 機種アダプタから最新の接続先一覧を取得し、成功したらキャッシュを更新する。
     * 失敗時はキャッシュを書き換えない（古い情報を最新として保存しないため）。
     */
    suspend fun fetch(device: EarbudsDevice): TargetListResult
}
