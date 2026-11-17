package com.tonbiattack.soundcoreswitch.domain.adapter

/**
 * 機種 ID からアダプタを引くレジストリ。
 *
 * 未登録の機種は null を返す。呼び出し側は null を「未対応」として扱い、
 * 機能を有効化してはならない。
 */
interface MultipointAdapterRegistry {
    fun adapterFor(modelId: String): MultipointAdapter?
}
