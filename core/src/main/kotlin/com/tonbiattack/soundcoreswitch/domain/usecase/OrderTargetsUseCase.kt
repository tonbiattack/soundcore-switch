package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget

/**
 * 接続先を表示順に並べる。
 *
 * 並び順の優先度:
 * 1. **接続中の端末**（今つながっているものを最初に見せる）
 * 2. お気に入りの端末
 * 3. それ以外（イヤホンが返した順）
 *
 * 同じ優先度の中では元の順序を保つ。並び順が毎回変わると目的の端末を探しにくくなるため、
 * **安定した順序**にする。
 *
 * 接続中が複数ある場合（マルチポイントの上限は2台）は、その中でも元の順序を保つ。
 * どちらを先にしても実害はないが、毎回入れ替わると押し間違えるため。
 *
 * 判定は ID（MAC）で行う。表示名は変更され得るため。
 */
class OrderTargetsUseCase {

    /**
     * @param targets イヤホンが返した接続先。
     * @param favoriteIds お気に入りの ID 集合。
     * @return 接続中 → お気に入り → その他 の順に並べたリスト。
     */
    fun order(targets: List<MultipointTarget>, favoriteIds: Set<String>): List<MultipointTarget> {
        if (targets.isEmpty()) return targets

        // 3段階に分ける。partition は元の順序を保つため、安定した並びになる。
        val (connected, rest) = targets.partition {
            it.status == ConnectionStatus.CONNECTED
        }
        if (rest.isEmpty()) return connected

        val (favorites, others) = rest.partition { it.id in favoriteIds }
        return connected + favorites + others
    }
}
