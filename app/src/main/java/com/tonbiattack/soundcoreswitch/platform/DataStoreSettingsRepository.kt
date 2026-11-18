package com.tonbiattack.soundcoreswitch.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import com.tonbiattack.soundcoreswitch.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * DataStore にユーザー設定を保存する。
 *
 * 仕様書 F-07「前回選択機種を復元する」に対応する。
 * 保存するのは Bluetooth アドレス（端末識別子）のみで、端末内に閉じる（仕様書 §6）。
 *
 * お気に入りと保護設定は **MAC アドレスで保持する**。
 * 表示名は端末側で変更でき、同名の端末もあり得るため、名前で保持すると設定が失われる。
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

    override suspend fun favoriteTargetIds(): Set<String> =
        try {
            dataStore.data.first()[KEY_FAVORITE_TARGETS].orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

    override suspend fun setFavorite(targetId: String, favorite: Boolean) {
        try {
            dataStore.edit { prefs ->
                val current = prefs[KEY_FAVORITE_TARGETS].orEmpty()
                prefs[KEY_FAVORITE_TARGETS] =
                    if (favorite) current + targetId else current - targetId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保存失敗でアプリを止めない。
        }
    }

    override suspend fun protectionSettings(): ProtectionSettings =
        try {
            val prefs = dataStore.data.first()
            ProtectionSettings(
                protectedTargetIds = prefs[KEY_PROTECTED_TARGETS].orEmpty(),
                neverDisconnectUnknown = prefs[KEY_NEVER_DISCONNECT_UNKNOWN] ?: true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProtectionSettings()
        }

    override suspend fun setProtectionSettings(settings: ProtectionSettings) {
        try {
            dataStore.edit { prefs ->
                prefs[KEY_PROTECTED_TARGETS] = settings.protectedTargetIds
                prefs[KEY_NEVER_DISCONNECT_UNKNOWN] = settings.neverDisconnectUnknown
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保存失敗でアプリを止めない。
        }
    }

    private companion object {
        val KEY_LAST_SELECTED_ADDRESS = stringPreferencesKey("last_selected_address")

        /** お気に入りの接続先 ID（MAC）。 */
        val KEY_FAVORITE_TARGETS = stringSetPreferencesKey("favorite_targets")

        /** 保護された接続先 ID（MAC）。 */
        val KEY_PROTECTED_TARGETS = stringSetPreferencesKey("protected_targets")

        /** 操作元を特定できない場合に自動切断を禁止するか。 */
        val KEY_NEVER_DISCONNECT_UNKNOWN = booleanPreferencesKey("never_disconnect_unknown")
    }
}
