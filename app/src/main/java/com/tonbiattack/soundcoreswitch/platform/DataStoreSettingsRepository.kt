package com.tonbiattack.soundcoreswitch.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tonbiattack.soundcoreswitch.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * DataStore にユーザー設定を保存する。
 *
 * 仕様書 F-07「前回選択機種を復元する」に対応する。
 * 保存するのは Bluetooth アドレスのみで、端末内に閉じる（仕様書 §6）。
 *
 * @param dataStore 設定専用の DataStore。キャッシュと同居させると
 *   キャッシュ削除時に設定まで消える恐れがあるため、インスタンスを分ける。
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override suspend fun lastSelectedAddress(): String? =
        try {
            dataStore.data.first()[KEY_LAST_SELECTED_ADDRESS]?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 読めなければ「未設定」として扱う。復元できないだけで動作は継続できる。
            null
        }

    override suspend fun setLastSelectedAddress(address: String) {
        try {
            dataStore.edit { it[KEY_LAST_SELECTED_ADDRESS] = address }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保存失敗でアプリを止めない。次回起動時に復元できないだけである。
        }
    }

    private companion object {
        val KEY_LAST_SELECTED_ADDRESS = stringPreferencesKey("last_selected_address")
    }
}
