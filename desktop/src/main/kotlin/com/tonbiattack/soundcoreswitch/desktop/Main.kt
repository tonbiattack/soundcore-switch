package com.tonbiattack.soundcoreswitch.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tonbiattack.soundcoreswitch.desktop.platform.DesktopContainer
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
 */
fun main() = application {
    val container = DesktopContainer()
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    Window(
        onCloseRequest = {
            scope.cancel()
            container.close()
            exitApplication()
        },
        title = "Soundcore Switch",
        state = rememberWindowState(size = DpSize(520.dp, 720.dp)),
        // ウィンドウとタスクバーのアイコン。未指定だと Kotlin の既定アイコンになる。
        icon = rememberAppIcon(),
    ) {
        DesktopApp(container = container, scope = scope)
    }
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
