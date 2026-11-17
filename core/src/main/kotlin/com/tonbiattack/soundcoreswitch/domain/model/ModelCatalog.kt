package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 既知の機種と、その対応レベル。
 *
 * **この表がアプリ全体の唯一の正である。** UI の有効/無効、アダプタの登録、
 * ドキュメントの対応表はすべてここから導出する。
 *
 * 重要な原則:
 * 実機での技術検証（仕様書 §7）が完了するまで、どの機種も [SupportLevel.UNKNOWN] のままにする。
 * 検証していない機種を [SupportLevel.SUPPORTED] にすると、
 * 「切り替えたつもりで切り替わっていない」という最悪の誤動作につながる。
 *
 * 検証が完了した機種は、[KNOWN_MODELS] の該当エントリの [SupportLevel] を更新し、
 * 同時に `docs/SUPPORT_MATRIX.md` を更新すること。
 */
object ModelCatalog {

    /** Space A40 (A3936)。所有機種。 */
    val SPACE_A40 = EarbudsModel(id = "space-a40", displayName = "Space A40")

    /**
     * Liberty 4 NC (A3947)。
     *
     * 2026-11-17 に実機でプロトコルを解読し、接続先一覧の取得と
     * 切り替えコマンドの送信を確認した。
     */
    val LIBERTY_4_NC = EarbudsModel(id = "liberty-4-nc", displayName = "Liberty 4 NC")

    /**
     * 機種 ID → 対応レベル。
     *
     * ここに無い機種は [SupportLevel.UNKNOWN] として扱う。
     * 未検証の機種を誤って有効化しないため、既定値は必ず UNKNOWN にする。
     */
    private val supportLevels: Map<String, SupportLevel> = mapOf(
        // 実機で一覧取得・切り替えコマンド送信・完了検出まで確認済み。
        LIBERTY_4_NC.id to SupportLevel.SUPPORTED,
        // 2026-11-17 に実機（Windows）で一覧取得・切断・接続を確認した。
        // ただし切断は対象によって拒否される場合がある（docs/VERIFICATION_A40.md）。
        SPACE_A40.id to SupportLevel.SUPPORTED,
    )

    /** 既知の機種一覧。 */
    val KNOWN_MODELS: List<EarbudsModel> = listOf(LIBERTY_4_NC, SPACE_A40)

    /** 機種 ID から対応レベルを引く。未知の機種は UNKNOWN。 */
    fun supportLevelOf(modelId: String): SupportLevel =
        supportLevels[modelId] ?: SupportLevel.UNKNOWN

    /**
     * Bluetooth の表示名から機種を推定する。
     *
     * 表示名は端末やファームウェアで揺れるため、部分一致で判定する。
     * 一致しなければ null を返し、呼び出し側は「未知の機種＝UNKNOWN」として扱う。
     */
    fun matchByDisplayName(displayName: String): EarbudsModel? {
        val normalized = displayName.lowercase()
        return KNOWN_MODELS.firstOrNull { model ->
            val key = model.displayName.lowercase()
            normalized.contains(key) || key.contains(normalized)
        }
    }
}
