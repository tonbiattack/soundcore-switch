package com.tonbiattack.soundcoreswitch.protocol

import com.tonbiattack.soundcoreswitch.domain.model.ModelProfiles

/**
 * 機種コードからベンダー UUID を組み立てる。
 *
 * 実機で確認した UUID の形:
 * `0cf12d31-fac3-4553-bd80-d6832e7b` + 機種コード4桁
 *
 * 例: Liberty 4 NC (A3947) → `0cf12d31-fac3-4553-bd80-d6832e7b3947`
 *     Space A40    (A3936) → `0cf12d31-fac3-4553-bd80-d6832e7b3936`
 *
 * 機種コードは [ModelProfiles] が唯一の正。ここで二重管理しない。
 */
object SoundcoreUuids {

    /** ベンダー UUID の共通前置き。 */
    const val VENDOR_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7b"

    /** 機種コードから UUID を作る。 */
    fun vendorUuid(modelCode: String): String =
        VENDOR_PREFIX + modelCode.lowercase()

    /**
     * 機種 ID（`ModelCatalog` の id）から機種コードを引く。
     *
     * 未登録の機種は null。呼び出し側は「未対応」として扱うこと。
     * **未検証の機種でもコードが分かれば返す。** 対応可否は
     * `ModelCatalog.supportLevels` で判断する（ここでは判定しない）。
     */
    fun modelCodeOf(modelId: String): String? =
        ModelProfiles.byModelId(modelId)?.modelCode
}
