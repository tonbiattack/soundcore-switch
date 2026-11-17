package com.tonbiattack.soundcoreswitch

import android.app.Application
import com.tonbiattack.soundcoreswitch.platform.ServiceLocator

/**
 * アプリのエントリポイント。
 *
 * 依存の組み立ては [ServiceLocator] に集約し、ここでは起動時に一度だけ初期化する。
 * 画面側は [ServiceLocator.createAppViewModel] で ViewModel を得る。
 */
class SoundcoreSwitchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)
    }
}
