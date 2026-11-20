package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 接続先の保護設定。
 *
 * 接続上限に達したとき、どの端末を切断してよいかをユーザーが決められるようにする。
 * 誤って必要な端末（操作元の PC など）を切らないための安全装置。
 *
 * **同一性は MAC アドレス（[MultipointTarget.id]）で判定する。**
 * 表示名は端末側で変更でき、同名の端末も存在し得るため、名前では判定しない。
 *
 * @property protectedTargetIds 切断してはならない接続先の ID（MAC）。
 * @property neverDisconnectUnknown 接続先を一意に特定できない場合に自動切断を禁止するか。
 *
 *   **既定は false。** Android 10 以降、アプリからローカル Bluetooth アドレスは
 *   取得できないため、true にすると Android では切り替えが一切できなくなる。
 *   代わりに「切断して通信が途切れたら、それが操作元だった」と判断して中止する
 *   （[com.tonbiattack.soundcoreswitch.adapter.SoundcoreMultipointAdapter] 参照）。
 *   保護したい端末は [protectedTargetIds] で明示する。
 */
data class ProtectionSettings(
    val protectedTargetIds: Set<String> = emptySet(),
    val neverDisconnectUnknown: Boolean = false,
) {
    /** この接続先を切断してよいか。 */
    fun allowsDisconnect(targetId: String): Boolean =
        targetId !in protectedTargetIds

    /** 保護対象を追加する。 */
    fun protect(targetId: String): ProtectionSettings =
        copy(protectedTargetIds = protectedTargetIds + targetId)

    /** 保護対象を解除する。 */
    fun unprotect(targetId: String): ProtectionSettings =
        copy(protectedTargetIds = protectedTargetIds - targetId)

    /** 保護対象か。 */
    fun isProtected(targetId: String): Boolean = targetId in protectedTargetIds
}
