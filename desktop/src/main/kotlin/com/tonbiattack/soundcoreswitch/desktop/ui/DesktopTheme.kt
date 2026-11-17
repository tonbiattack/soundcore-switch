package com.tonbiattack.soundcoreswitch.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Windows 版の配色。
 *
 * Android 版（`app/src/main/java/.../ui/theme/Theme.kt`）と揃える。
 * 動的カラーは使わない。端末の壁紙によって接続状態の色（緑/灰）の意味が変わると、
 * 状態の判別が難しくなるため。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6D9DC),
    onPrimaryContainer = Color(0xFF0B2B2E),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8EA),
    onSecondaryContainer = Color(0xFF051F21),
    background = Color(0xFFF7FAFA),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFF7FAFA),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ACDD1),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF164E52),
    onPrimaryContainer = Color(0xFFB6D9DC),
    secondary = Color(0xFFB0CCCE),
    onSecondary = Color(0xFF1B3436),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8EA),
    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E3),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E3),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C9),
    outline = Color(0xFF899393),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * 接続状態を表す色。
 *
 * 色だけで意味を伝えると色覚特性や白黒表示で判別できないため、
 * UI 側では必ずテキスト（「接続中」「切断中」「不明」）も併記する。
 */
object StatusColors {
    /** 接続中。 */
    val Connected = Color(0xFF2E7D32)

    /** 切断中。 */
    val Disconnected = Color(0xFF757575)

    /** 不明。取得できていないことを、切断中と区別して示す。 */
    val Unknown = Color(0xFF9E9E9E)
}

/** 見出しの字面を少し詰めて、情報密度を上げる。 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/** アプリ全体のテーマ。 */
@Composable
fun DesktopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
