package com.tonbiattack.soundcoreswitch.platform

import android.content.Context
import com.tonbiattack.soundcoreswitch.adapter.SoundcoreMultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry

/**
 * 機種 ID からアダプタを引くレジストリの実装。
 *
 * **実機検証が完了した機種だけを登録する。** 仕様書 §7 の合格基準を満たしていない機種を
 * ここに登録してはならない。未検証の機種で切り替えを有効にすると
 * 「切り替えたつもりで切り替わっていない」という最悪の誤動作につながる。
 *
 * 登録の判断は [com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] の
 * [com.tonbiattack.soundcoreswitch.domain.model.SupportLevel] と必ず一致させること。
 * 片方だけ更新すると「UI 上は有効なのに切り替えられない」または
 * 「切り替えられるのに UI が無効」という不整合が起きる。
 *
 * 現在の登録状況:
 * - `liberty-4-nc` (A3947): 登録する。一覧取得・切り替え・完了検出まで実機で確認済み
 *   （`docs/SUPPORT_MATRIX.md`）。
 * - `space-a40` (A3936): **登録しない。** ベンダー UUID は検出済みだがコマンドの実機検証が
 *   未完了であり、[com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] でも
 *   [com.tonbiattack.soundcoreswitch.domain.model.SupportLevel.UNKNOWN] のため。
 *   未登録でも UI 側で無効化されるが、二重の安全策として登録もしない。
 *
 * 機種を追加する手順:
 * 1. 仕様書 §7 の合格基準を満たす実機検証を行う。
 * 2. [com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] の該当機種の
 *    [com.tonbiattack.soundcoreswitch.domain.model.SupportLevel] を更新する。
 * 3. [verifiedAdapters] に `modelId to 実装` を登録する。
 * 4. `docs/SUPPORT_MATRIX.md` を更新する。
 *
 * @param context アプリケーションコンテキスト。RFCOMM 接続に使う。
 *   Activity を渡すとリークするため、呼び出し側は `applicationContext` を渡すこと。
 * @param adapters 機種 ID → アダプタ。既定は実機検証済みの機種のみ。
 */
class DefaultMultipointAdapterRegistry(
    context: Context,
    private val adapters: Map<String, MultipointAdapter> = verifiedAdapters(context),
) : MultipointAdapterRegistry {

    override fun adapterFor(modelId: String): MultipointAdapter? = adapters[modelId]

    private companion object {
        /**
         * 実機検証が完了した機種のアダプタを組み立てる。
         *
         * ここに追加できるのは `docs/SUPPORT_MATRIX.md` で `supported` と記録された機種だけ。
         */
        fun verifiedAdapters(context: Context): Map<String, MultipointAdapter> {
            // トランスポートは機種に依存しない（ベンダー UUID は機種コードから組み立てる）。
            // そのため1つのインスタンスを全機種で共有する。
            val transport = AndroidSoundcoreTransport(context)
            return mapOf(
                // Liberty 4 NC (A3947)。MODEL_ID は "liberty-4-nc"。
                SoundcoreMultipointAdapter.MODEL_ID to SoundcoreMultipointAdapter(transport),
            )
        }
    }
}
