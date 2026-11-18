package com.tonbiattack.soundcoreswitch.domain.repository

import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 保護設定のメモリキャッシュ。
 *
 * 保護設定は DataStore に保存されるが、アダプタは切断候補を選ぶ際に
 * **同期的に**参照する必要がある（suspend 関数の中から呼べない箇所があるため）。
 * そのため、起動時に読み込んでメモリに保持し、変更時に更新する。
 *
 * 読み込みに失敗しても既定値（保護なし・不明時は切断しない）で動作する。
 */
class ProtectionCache(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val cached = AtomicReference(ProtectionSettings())

    /** 現在の保護設定。同期的に読める。 */
    fun current(): ProtectionSettings = cached.get()

    /** DataStore から読み込んでキャッシュを更新する。 */
    fun refresh() {
        scope.launch {
            runCatching { settings.protectionSettings() }
                .onSuccess { cached.set(it) }
        }
    }

    /** 設定を更新して保存する。 */
    fun update(newSettings: ProtectionSettings) {
        cached.set(newSettings)
        scope.launch {
            runCatching { settings.setProtectionSettings(newSettings) }
        }
    }
}
