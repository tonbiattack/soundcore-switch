package com.tonbiattack.soundcoreswitch.presentation

import com.tonbiattack.soundcoreswitch.domain.model.DisconnectBlockReason
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason

/**
 * 失敗理由を画面の文言に変換する。
 *
 * 仕様書 F-05「Bluetoothオフ、権限不足、イヤホン未接続、タイムアウト、非対応を区別して通知する」
 * に対応する。各理由に「何が起きたか」と「どうすればよいか」を必ず添える。
 *
 * 例外メッセージをそのまま画面に出さない。端末識別子や内部情報が混ざる恐れがあるため、
 * 表示文言は必ずこの型から組み立てる。
 *
 * @property title 何が起きたか。
 * @property detail どうすればよいか。
 */
data class FailureMessage(
    val title: String,
    val detail: String,
)

/** [FailureReason] を表示文言に変換する。 */
fun FailureReason.toMessage(): FailureMessage = when (this) {
    FailureReason.BluetoothOff -> FailureMessage(
        title = "Bluetoothがオフです",
        detail = "端末の設定からBluetoothをオンにしてから、もう一度お試しください。",
    )

    FailureReason.PermissionDenied -> FailureMessage(
        title = "Bluetoothの権限がありません",
        detail = "近くのデバイスへのアクセスを許可すると、イヤホンを検出できます。",
    )

    FailureReason.EarbudsNotConnected -> FailureMessage(
        title = "イヤホンが接続されていません",
        detail = "イヤホンの電源を入れ、端末のBluetooth設定から接続してください。",
    )

    FailureReason.UnsupportedModel -> FailureMessage(
        title = "この機種は未対応です",
        detail = "実機での動作確認が完了していないため、切り替えは行いません。" +
            "接続状態の表示のみ利用できます。",
    )

    FailureReason.Timeout -> FailureMessage(
        title = "応答がありませんでした",
        detail = "イヤホンからの応答を待ちきれませんでした。" +
            "イヤホンを再接続してから、もう一度お試しください。",
    )

    FailureReason.TargetListUnavailable -> FailureMessage(
        title = "接続先を取得できませんでした",
        detail = "この機種からは接続先の一覧を取得できません。" +
            "純正アプリで現在の接続先を確認してください。",
    )

    // 一部しか取れなかった。不完全な一覧を「これだけ」と見せない。
    FailureReason.TargetListIncomplete -> FailureMessage(
        title = "接続先を最後まで取得できませんでした",
        detail = "イヤホンからの応答が途中で途切れました。" +
            "誤った切り替えを避けるため、取得できた分だけを表示することはしません。" +
            "イヤホンを再接続してから、もう一度お試しください。",
    )

    // 成功と誤認させないことが最重要。要求は送れたが結果を確認できていない状態。
    FailureReason.SwitchUnconfirmed -> FailureMessage(
        title = "切り替えを確認できませんでした",
        detail = "切り替えの指示は送りましたが、実際に切り替わったことを確認できませんでした。" +
            "純正アプリで現在の接続先を確認してください。",
    )

    FailureReason.TargetNotFound -> FailureMessage(
        title = "接続先が見つかりません",
        detail = "指定した接続先がイヤホンに登録されていません。" +
            "接続先一覧を更新してから、もう一度お試しください。",
    )

    // 接続上限に達しているが、安全に切断できる端末が無い。
    // 誤って必要な端末を切るより、切り替えを中止するほうが安全。
    is FailureReason.CannotFreeConnectionSlot -> when (val reason = this.reason) {
        is DisconnectBlockReason.AllProtected -> FailureMessage(
            title = "切り替えできません",
            detail = "接続中の端末がすべて保護されています。" +
                "切り替えるには、保護を解除するか手動で切断してください。",
        )
        DisconnectBlockReason.OnlyThisDevice -> FailureMessage(
            title = "切り替えできません",
            detail = "この端末しか接続されていません。" +
                "この端末を切断すると操作できなくなるため、切り替えを中止しました。",
        )
        DisconnectBlockReason.CannotIdentifyThisDevice -> FailureMessage(
            title = "切り替えできません",
            detail = "どの接続先がこの端末か特定できませんでした。" +
                "誤って必要な端末を切断しないため、切り替えを中止しました。" +
                "純正アプリで現在の接続先を確認してください。",
        )
        DisconnectBlockReason.CutThisDevice -> FailureMessage(
            title = "この端末の接続を切ってしまいました",
            detail = "切り替えの途中で、この端末自身の接続を切断しました。" +
                "イヤホンと再接続してから、もう一度お試しください。" +
                "切り替えたい端末を保護しておくと、次回から切られません。",
        )
        DisconnectBlockReason.NoConnectedTargets -> FailureMessage(
            title = "切り替えできません",
            detail = "接続中の端末が見つかりませんでした。" +
                "イヤホンの接続状態を確認してから、もう一度お試しください。",
        )
    }

    FailureReason.Unknown -> FailureMessage(
        title = "処理できませんでした",
        detail = "原因を特定できませんでした。イヤホンを再接続してから、もう一度お試しください。",
    )
}
