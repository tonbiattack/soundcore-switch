package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 機種ごとの技術情報。
 *
 * 機種を追加するときに必要な情報を1か所に集める。散らばっていると
 * 「UUID は登録したがアダプタを登録し忘れた」といった不整合が起きる。
 *
 * @property modelId `ModelCatalog` の id。アダプタ登録のキー。
 * @property modelCode ベンダー UUID の末尾4桁。型番の数字部分（例: A3947 → 3947）。
 * @property displayName 画面に出す名前。
 * @property supportLevel 対応レベル。実機検証が済むまで UNKNOWN。
 * @property verifiedOn 実機検証を完了した日付（ISO 8601）。未検証なら null。
 * @property notes 検証で分かった注意点。
 */
data class ModelProfile(
    val modelId: String,
    val modelCode: String,
    val displayName: String,
    val supportLevel: SupportLevel,
    val verifiedOn: String? = null,
    val notes: String = "",
) {
    /** ベンダー UUID。 */
    val vendorUuid: String
        get() = "0cf12d31-fac3-4553-bd80-d6832e7b$modelCode"

    /** 切り替えを許可してよいか。 */
    val allowsSwitching: Boolean
        get() = supportLevel.allowsSwitching

    /** 実機検証済みか。 */
    val isVerified: Boolean
        get() = verifiedOn != null
}

/**
 * 既知の機種の技術情報。
 *
 * **`ModelCatalog.supportLevels` と一致していなければならない。**
 * 一致はテスト（`ModelProfileConsistencyTest`）で検証する。
 *
 * 機種を追加する手順は `docs/ADDING_MODELS.md` を参照。
 */
object ModelProfiles {

    val LIBERTY_4_NC = ModelProfile(
        modelId = "liberty-4-nc",
        modelCode = "3947",
        displayName = "Liberty 4 NC",
        supportLevel = SupportLevel.SUPPORTED,
        verifiedOn = "2026-11-17",
        notes = "一覧取得・切り替え・完了検出を実機で確認。",
    )

    val SPACE_A40 = ModelProfile(
        modelId = "space-a40",
        modelCode = "3936",
        displayName = "Space A40",
        supportLevel = SupportLevel.SUPPORTED,
        verifiedOn = "2026-11-17",
        notes = "切断が対象によって拒否される場合がある（docs/VERIFICATION_A40.md）。",
    )

    /** 既知の全機種。 */
    val ALL: List<ModelProfile> = listOf(LIBERTY_4_NC, SPACE_A40)

    /** 機種 ID から引く。未登録なら null。 */
    fun byModelId(modelId: String): ModelProfile? =
        ALL.firstOrNull { it.modelId == modelId }

    /** 機種コードから引く。未登録なら null。 */
    fun byModelCode(modelCode: String): ModelProfile? =
        ALL.firstOrNull { it.modelCode.equals(modelCode, ignoreCase = true) }
}
