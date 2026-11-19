package com.tonbiattack.soundcoreswitch.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tonbiattack.soundcoreswitch.desktop.platform.DesktopContainer
import com.tonbiattack.soundcoreswitch.desktop.tray.AppTray
import com.tonbiattack.soundcoreswitch.desktop.ui.DesktopApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.imageio.ImageIO

/**
 * Windows 版のエントリポイント。
 *
 * Android 版と同じドメイン層・ViewModel を使う。画面も同じ構成
 * （イヤホン選択 → 接続先一覧 → 切り替え）を1画面で提供する。
 *
 * システムトレイに常駐し、画面を開かずに接続先を切り替えられる。
 * ウィンドウを閉じてもトレイに残る（終了はトレイメニューから）。
 */
fun main() = application {
    val container = DesktopContainer()
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val viewModel = remember { container.createViewModel(scope) }

    // ウィンドウを閉じてもトレイに残す。終了はトレイメニューから行う。
    var windowVisible by remember { mutableStateOf(true) }

    val state by viewModel.state.collectAsState()

    if (windowVisible) {
        Window(
            onCloseRequest = { windowVisible = false },
            title = "Soundcore Switch",
            state = rememberWindowState(size = DpSize(520.dp, 720.dp)),
            // ウィンドウとタスクバーのアイコン。未指定だと Kotlin の既定アイコンになる。
            icon = rememberAppIcon(),
        ) {
            DesktopApp(container = container, scope = scope, viewModel = viewModel)
        }
    }

    AppTray(
        container = container,
        scope = scope,
        viewModel = viewModel,
        state = state,
        onShowWindow = { windowVisible = true },
        onExit = {
            scope.cancel()
            container.close()
            exitApplication()
        },
    )
}

/**
 * アプリのアイコンを読み込む。
 *
 * `src/main/resources/icon.ico` は `scripts/generate-icon.ps1` が生成する。
 * 読み込めなくても起動を止めない（アイコンは本質的な機能ではない）。
 */
private fun rememberAppIcon(): Painter? = runCatching {
    val image = object {}.javaClass.getResourceAsStream("/icon.ico")?.use {
        ImageIO.read(it)
    } ?: return null
    BitmapPainter(image.toComposeImageBitmap())
}.getOrNull()
