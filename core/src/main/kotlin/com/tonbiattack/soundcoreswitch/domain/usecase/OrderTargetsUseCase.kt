package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget

/**
 * 接続先を表示順に並べる。
 *
 * お気に入りを先頭に出し、それ以外は元の順序（イヤホンが返した順）を保つ。
 * 並び順が毎回変わると目的の端末を探しにくくなるため、**安定した順序**にする。
 *
 * お気に入りの判定は ID（MAC）で行う。表示名は変更され得るため。
 */
class OrderTargetsUseCase {

    /**
     * @param targets イヤホンが返した接続先。
     * @param favoriteIds お気に入りの ID 集合。
     * @return お気に入りを先頭にしたリスト。お気に入り同士・通常同士は元の順序を保つ。
     */
    fun order(targets: List<MultipointTarget>, favoriteIds: Set<String>): List<MultipointTarget> {
        if (favoriteIds.isEmpty()) return targets

        // partition は元の順序を保つ。
        val (favorites, others) = targets.partition { it.id in favoriteIds }
        return favorites + others
    }
}
