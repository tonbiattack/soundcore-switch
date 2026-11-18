package com.tonbiattack.soundcoreswitch.desktop.tray

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.rememberTrayState
import com.tonbiattack.soundcoreswitch.desktop.platform.DesktopContainer
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.SwitchUiState
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState
import com.tonbiattack.soundcoreswitch.presentation.AppUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.imageio.ImageIO

/**
 * システムトレイから接続先を切り替える。
 *
 * メインウィンドウを開かずに操作できるようにする。常駐しても
 * **不要な Bluetooth ポーリングは行わない**（メニューを開いたときだけ状態を使う）。
 *
 * 切り替えは既存の [com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase]
 * を経由するため、安全性（成功の確定は状態の再取得で行う）は画面と同じ。
 *
 * @param container 依存のまとまり。
 * @param scope アプリのスコープ。
 * @param state 現在の画面状態（ViewModel と共有）。
 * @param onShowWindow メインウィンドウを表示する。
 * @param onExit アプリを終了する。
 */
@androidx.compose.runtime.Composable
fun ApplicationScope.AppTray(
    container: DesktopContainer,
    scope: CoroutineScope,
    state: AppUiState,
    onShowWindow: () -> Unit,
    onExit: () -> Unit,
) {
    val trayState: TrayState = rememberTrayState()
    var lastResult by mutableStateOf<String?>(null)

    Tray(
        icon = rememberTrayIcon(),
        state = trayState,
        tooltip = "Soundcore Switch",
        onAction = onShowWindow,
        menu = {
            // 接続先の一覧。接続中は先頭に出す。
            val targets = (state.targets as? TargetsUiState.Ready)?.snapshot?.targets.orEmpty()
            if (targets.isEmpty()) {
                Item("接続先を取得できません", onClick = onShowWindow)
            } else {
                val connected = targets.filter { it.status == ConnectionStatus.CONNECTED }
                val others = targets.filter { it.status != ConnectionStatus.CONNECTED }

                if (connected.isNotEmpty()) {
                    Item("接続中", onClick = {}, enabled = false)
                    connected.forEach { target ->
                        Item(
                            text = "  ${target.displayName}",
                            onClick = { switchFromTray(container, scope, target) { lastResult = it } },
                            enabled = state.canSwitch,
                        )
                    }
                    Separator()
                }
                if (others.isNotEmpty()) {
                    Item("切り替え先", onClick = {}, enabled = false)
                    others.forEach { target ->
                        Item(
                            text = "  ${target.displayName}",
                            onClick = { switchFromTray(container, scope, target) { lastResult = it } },
                            enabled = state.canSwitch,
                        )
                    }
                }
            }

            Separator()
            Item("更新", onClick = { container.refreshFromTray(scope) })
            Item("画面を開く", onClick = onShowWindow)
            Separator()
            Item("終了", onClick = onExit)
        },
    )
}

/**
 * トレイから切り替える。
 *
 * 画面と同じユースケースを通すため、成功の判定は状態の再取得で行われる。
 */
private fun switchFromTray(
    container: DesktopContainer,
    scope: CoroutineScope,
    target: MultipointTarget,
    onResult: (String) -> Unit,
) {
    scope.launch {
        val device = container.currentDevice() ?: run {
            onResult("イヤホンが選択されていません")
            return@launch
        }
        val result = container.switchTarget.execute(device, target)
        onResult(
            when (result) {
                is SwitchResult.Confirmed -> "${result.target.displayName} に切り替えました"
                is SwitchResult.Failed -> "切り替えできませんでした"
            },
        )
    }
}

/** トレイのアイコン。読み込めなければ単色の四角で代替する（起動を止めない）。 */
@androidx.compose.runtime.Composable
private fun rememberTrayIcon(): androidx.compose.ui.graphics.painter.Painter {
    val loaded = runCatching {
        val image = object {}.javaClass.getResourceAsStream("/icon.ico")?.use {
            ImageIO.read(it)
        } ?: return@runCatching null
        BitmapPainter(image.toComposeImageBitmap())
    }.getOrNull()

    return loaded ?: rememberFallbackIcon()
}

/** アイコンが読めないときの代替。テーマ色の四角を描く。 */
@androidx.compose.runtime.Composable
private fun rememberFallbackIcon(): androidx.compose.ui.graphics.painter.Painter {
    val size = 32
    val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.color = java.awt.Color(0x2F, 0x5D, 0x62)
    g.fillRect(0, 0, size, size)
    g.color = java.awt.Color(0xF7, 0xFA, 0xFA)
    g.fillOval(10, 10, 12, 12)
    g.dispose()
    return BitmapPainter(image.toComposeImageBitmap())
}
