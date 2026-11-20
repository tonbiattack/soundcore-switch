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
            migrateLegacyProtectionDefault()
            val prefs = dataStore.data.first()
            ProtectionSettings(
                protectedTargetIds = prefs[KEY_PROTECTED_TARGETS].orEmpty(),
                neverDisconnectUnknown = prefs[KEY_NEVER_DISCONNECT_UNKNOWN] ?: false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProtectionSettings()
        }

    /**
     * 旧既定値（`neverDisconnectUnknown = true`）を新しい既定値へ移行する。
     *
     * 以前のバージョンはこの値を true で保存していた。true のままだと、
     * Android では操作元の MAC を取得できないため**切り替えが一切できない**。
     *
     * この設定を変更する UI は無いため、保存されている true は
     * 「利用者が明示的に選んだ値」ではなく旧既定値と判断できる。
     * 一度だけ false に書き換える（[KEY_PROTECTION_MIGRATED] で再実行を防ぐ）。
     */
    private suspend fun migrateLegacyProtectionDefault() {
        try {
            dataStore.edit { prefs ->
                // 移行済みなら何もしない。
                if (prefs[KEY_PROTECTION_MIGRATED] != true) {
                    if (prefs[KEY_NEVER_DISCONNECT_UNKNOWN] == true) {
                        prefs[KEY_NEVER_DISCONNECT_UNKNOWN] = false
                    }
                    prefs[KEY_PROTECTION_MIGRATED] = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 移行できなくても動作は継続できる。次回起動時に再試行する。
        }
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

        /** 保護設定の移行を完了したか。旧既定値の書き換えを1回だけ行うためのフラグ。 */
        val KEY_PROTECTION_MIGRATED = booleanPreferencesKey("protection_migrated_v2")
    }
}
