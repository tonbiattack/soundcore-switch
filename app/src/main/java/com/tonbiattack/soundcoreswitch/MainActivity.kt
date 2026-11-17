package com.tonbiattack.soundcoreswitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.tonbiattack.soundcoreswitch.platform.ServiceLocator
import com.tonbiattack.soundcoreswitch.ui.AppRoute
import com.tonbiattack.soundcoreswitch.ui.theme.SoundcoreSwitchTheme

/**
 * 唯一の Activity。
 *
 * 画面遷移を持たないため、1つの Activity に1つの Composable を載せるだけにする
 * （仕様書 §5「画面遷移を最小化」）。
 *
 * ViewModel は [ServiceLocator] から取得する。依存の組み立ては
 * [com.tonbiattack.soundcoreswitch.SoundcoreSwitchApp] が済ませている。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel = ServiceLocator.createAppViewModel(lifecycleScope)

        setContent {
            SoundcoreSwitchTheme {
                AppRoute(viewModel)
            }
        }
    }
}
