package com.tonbiattack.soundcoreswitch.domain.adapter

import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult

/**
 * 機種ごとのマルチポイント制御アダプタ。
 *
 * 仕様書 §8「機種ごとの差異をアダプタ層で隔離し、テスト可能にする」に対応する。
 * 実装は機種固有のプロトコル差分を吸収し、上位層にはこのインターフェースだけを見せる。
 *
 * 実装上の禁止事項:
 * - 未検証の機種で「成功した」と返してはならない。
 * - 例外を投げず、必ず [TargetListResult.Unavailable] / [SwitchResult.Failed] に変換する。
 * - 端末識別子やペアリング鍵をログ・例外メッセージに含めない。
 */
interface MultipointAdapter {
    /** このアダプタが担当する機種 ID（[com.tonbiattack.soundcoreswitch.domain.model.EarbudsModel.id]）。 */
    val modelId: String

    /**
     * イヤホン内部の登録済み接続先一覧を取得する。
     * 取得できない場合は [TargetListResult.Unavailable] を返す。空リストで代用しない。
     */
    suspend fun readTargets(device: EarbudsDevice): TargetListResult

    /**
     * 指定した接続先へ切り替える。
     *
     * 実装は要求送信後に状態を再取得し、対象が接続中になったことを確認できた場合のみ
     * [SwitchResult.Confirmed] を返すこと。確認できなければ [SwitchResult.Failed] を返す。
     */
    suspend fun switchTo(device: EarbudsDevice, target: MultipointTarget): SwitchResult
}
