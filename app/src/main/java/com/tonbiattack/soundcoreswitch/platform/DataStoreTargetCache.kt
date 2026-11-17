package com.tonbiattack.soundcoreswitch.platform

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.TargetKind
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.repository.TargetCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * DataStore に接続先一覧を保存するローカルキャッシュ。
 *
 * 仕様書 F-06「初期画面はローカルキャッシュから即描画し、状態更新を非同期に実行する」に対応する。
 * 保存先は端末内に閉じ、外部送信しない（仕様書 §6）。
 *
 * 保存形式:
 * - キーは `targets_<address>`。値は JSON 文字列。
 * - 表示名に区切り文字が含まれても壊れないよう、手書きの区切り文字方式ではなく
 *   Android 標準の [org.json.JSONObject] を使う（kotlinx.serialization は依存に無い）。
 * - [TargetSnapshot.observedAt] と [TargetSnapshot.fromCache] も保存する。
 *
 * 方針:
 * - 読み出し時は [TargetSnapshot.fromCache] を必ず true にする。キャッシュから読んだ値は
 *   常に「古い可能性がある」ものとして扱うため（仕様書 §5）。
 * - 壊れたデータは例外を投げず null を返す。キャッシュは無くても動作するため、
 *   復元失敗でアプリを止めない（仕様書 §8）。
 * - コルーチンのキャンセルだけは握りつぶさず、そのまま伝播させる。
 *
 * @param dataStore 設定と共有しないよう、キャッシュ専用のインスタンスを渡す。
 */
class DataStoreTargetCache(
    private val dataStore: DataStore<Preferences>,
) : TargetCache {

    override suspend fun read(deviceAddress: String): TargetSnapshot? {
        val raw = try {
            dataStore.data.first()[keyOf(deviceAddress)]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 読めなければキャッシュ無しとして扱う。
            return null
        }
        if (raw == null) return null

        return try {
            decode(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 壊れたデータは無いものとして扱う。アドレスはログに出さない。
            Log.w(TAG, "キャッシュの復元に失敗したため破棄する")
            null
        }
    }

    override suspend fun write(deviceAddress: String, snapshot: TargetSnapshot) {
        val encoded = try {
            encode(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        try {
            dataStore.edit { it[keyOf(deviceAddress)] = encoded }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "キャッシュの保存に失敗した")
        }
    }

    override suspend fun clear(deviceAddress: String) {
        try {
            dataStore.edit { it.remove(keyOf(deviceAddress)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "キャッシュの削除に失敗した")
        }
    }

    private fun keyOf(deviceAddress: String) = stringPreferencesKey(KEY_PREFIX + deviceAddress)

    private fun encode(snapshot: TargetSnapshot): String {
        val targets = JSONArray()
        snapshot.targets.forEach { target ->
            targets.put(
                JSONObject().apply {
                    put(FIELD_ID, target.id)
                    put(FIELD_DISPLAY_NAME, target.displayName)
                    put(FIELD_KIND, target.kind.name)
                    put(FIELD_STATUS, target.status.name)
                    put(FIELD_IS_THIS_DEVICE, target.isThisDevice)
                },
            )
        }
        return JSONObject().apply {
            put(FIELD_OBSERVED_AT, snapshot.observedAt)
            put(FIELD_FROM_CACHE, snapshot.fromCache)
            put(FIELD_TARGETS, targets)
        }.toString()
    }

    private fun decode(raw: String): TargetSnapshot? {
        val root = JSONObject(raw)
        val array = root.optJSONArray(FIELD_TARGETS) ?: return null
        val targets = buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                // id が無い要素は識別できないため捨てる。
                val id = obj.optString(FIELD_ID).takeIf { it.isNotEmpty() } ?: continue
                add(
                    MultipointTarget(
                        id = id,
                        displayName = obj.optString(FIELD_DISPLAY_NAME),
                        kind = enumOrDefault(obj.optString(FIELD_KIND), TargetKind.UNKNOWN),
                        status = enumOrDefault(
                            obj.optString(FIELD_STATUS),
                            ConnectionStatus.UNKNOWN,
                        ),
                        isThisDevice = obj.optBoolean(FIELD_IS_THIS_DEVICE, false),
                    ),
                )
            }
        }
        return TargetSnapshot(
            targets = targets,
            observedAt = root.optLong(FIELD_OBSERVED_AT, 0L),
            // キャッシュから読んだ値は常にキャッシュ扱い（仕様書 §5）。
            fromCache = true,
        )
    }

    /** 未知の enum 名で落ちないよう、既定値に落とす。 */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)

    private companion object {
        const val TAG = "TargetCache"

        /** キーの前置き。イヤホンのアドレスと組み合わせて一意にする。 */
        const val KEY_PREFIX = "targets_"

        const val FIELD_OBSERVED_AT = "observedAt"
        const val FIELD_FROM_CACHE = "fromCache"
        const val FIELD_TARGETS = "targets"
        const val FIELD_ID = "id"
        const val FIELD_DISPLAY_NAME = "displayName"
        const val FIELD_KIND = "kind"
        const val FIELD_STATUS = "status"
        const val FIELD_IS_THIS_DEVICE = "isThisDevice"
    }
}
