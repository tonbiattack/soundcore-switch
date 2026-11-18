package com.tonbiattack.soundcoreswitch.domain.model

/**
 * 切断対象を選べなかった理由。
 *
 * 「切断できなかった」と一括りにせず、利用者が次に何をすればよいか分かるようにする。
 */
sealed interface DisconnectBlockReason {
    /** 接続中の端末がすべて保護されている。 */
    data class AllProtected(val protectedNames: List<String>) : DisconnectBlockReason

    /** 操作元の端末しか接続されておらず、それを切ると通信が途切れる。 */
    data object OnlyThisDevice : DisconnectBlockReason

    /** どの端末が操作元か特定できず、安全に切断できない。 */
    data object CannotIdentifyThisDevice : DisconnectBlockReason

    /** 接続中の端末が無い（上限には達していない）。 */
    data object NoConnectedTargets : DisconnectBlockReason
}
