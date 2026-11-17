package com.tonbiattack.soundcoreswitch.desktop.platform

import com.tonbiattack.soundcoreswitch.adapter.SoundcoreMultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.EarbudsScanner
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.TargetKind
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.repository.SettingsRepository
import com.tonbiattack.soundcoreswitch.domain.repository.TargetCache
import com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import com.tonbiattack.soundcoreswitch.presentation.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * アプリ全体で共有する依存のまとまり（Windows 版）。
 *
 * 手書き DI。ライブラリを増やさず、生成箇所をここ1か所に集約する
 * （Android 版 `ServiceLocator` と同じ構成）。
 *
 * 各プロパティはアプリ生存期間中有効なインスタンスである。
 *
 * @property bluetooth Bluetooth の利用可否判定。
 * @property scanner ペアリング済み端末からイヤホンを列挙する。
 * @property targetCache 接続先一覧のローカルキャッシュ。
 * @property targetRepository キャッシュと機種アダプタをまとめた取得口。
 * @property settings ユーザー設定の永続化。
 * @property registry 機種 ID からアダプタを引くレジストリ。
 * @property switchTarget 接続先切り替えのユースケース。
 */
class DesktopContainer {

    /** 保存先ディレクトリ。`%APPDATA%\SoundcoreSwitch\`。 */
    private val storageDir: File = defaultStorageDir()

    val bluetooth: BluetoothAvailabilityChecker = WindowsBluetoothAvailabilityChecker()

    val scanner: EarbudsScanner = WindowsEarbudsScanner(bluetooth)

    val targetCache: TargetCache = PropertiesTargetCache(File(storageDir, CACHE_FILE_NAME))

    val settings: SettingsRepository =
        PropertiesSettingsRepository(File(storageDir, SETTINGS_FILE_NAME))

    /**
     * 機種 ID からアダプタを引くレジストリ。
     *
     * 実機検証が完了した機種だけを登録する。登録と
     * [com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] の対応レベルは
     * 必ず同時に更新すること（片方だけだと UI と実動作が食い違う）。
     *
     * トランスポートは機種に依存しない（ベンダー UUID は機種コードから組み立てる）。
     * そのため1つのインスタンスを全機種で共有する。
     */
    val registry: MultipointAdapterRegistry = run {
        val transport = SoundcoreRfcommTransport()
        // 接続上限に達したとき、この端末自身を切断しないために名前を渡す。
        val localName = { localBluetoothName() }
        DefaultMultipointAdapterRegistry(
            mapOf(
                // Liberty 4 NC (A3947)。
                SoundcoreMultipointAdapter.MODEL_ID to SoundcoreMultipointAdapter(
                    transport = transport,
                    localNameProvider = localName,
                ),
                // Space A40 (A3936)。2026-11-17 に実機で一覧取得・切断・接続を確認。
                SoundcoreMultipointAdapter.SPACE_A40_MODEL_ID to SoundcoreMultipointAdapter(
                    transport = transport,
                    localNameProvider = localName,
                ),
            ),
        )
    }

    val targetRepository: TargetRepository = DefaultTargetRepository(registry, targetCache)

    val switchTarget: SwitchTargetUseCase = SwitchTargetUseCase(registry, bluetooth)

    /**
     * 画面用の [AppViewModel] を組み立てる。
     *
     * @param scope 画面のライフサイクルに紐づくスコープ。呼び出し側が管理する。
     */
    fun createViewModel(scope: CoroutineScope): AppViewModel = AppViewModel(
        scanner = scanner,
        targetRepository = targetRepository,
        settings = settings,
        switchTarget = switchTarget,
        bluetooth = bluetooth,
        scope = scope,
    )

    /**
     * 保持しているリソースを解放する。
     *
     * ソケットは [SoundcoreRfcommTransport] が1回の通信ごとに閉じるため、
     * ここで明示的に閉じるものは無い。将来リソースを持つ実装を足したらここで解放する。
     */
    fun close() {
        // 現時点で解放が必要なリソースは無い。
    }

    /**
     * この PC の Bluetooth 名。
     *
     * 接続先一覧の表示名と照合して「操作元の端末」を特定するために使う。
     * 接続上限に達したとき、この PC 自身を切断しないための手掛かりになる。
     *
     * Windows の Bluetooth 名は既定でコンピューター名と同じ。
     * 取得できなければ null（呼び出し側は別の手掛かりに落ちる）。
     */
    private fun localBluetoothName(): String? = runCatching {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** 接続先一覧キャッシュのファイル名。設定とは分ける。 */
private const val CACHE_FILE_NAME = "target_cache.properties"

/** 設定のファイル名。 */
private const val SETTINGS_FILE_NAME = "settings.properties"

/**
 * 保存先。`%APPDATA%\SoundcoreSwitch\`。
 *
 * 取得できなければホーム配下に落とす（環境変数が無い環境でも起動できるようにする）。
 */
private fun defaultStorageDir(): File {
    val appData = System.getenv("APPDATA")
    return if (appData.isNullOrBlank()) {
        File(System.getProperty("user.home"), "SoundcoreSwitch")
    } else {
        File(appData, "SoundcoreSwitch")
    }
}

/**
 * 機種 ID からアダプタを引くレジストリの実装（Windows 版）。
 *
 * Android 版 `DefaultMultipointAdapterRegistry` と同じ契約。未登録の機種は null を返し、
 * 呼び出し側は null を「未対応」として扱う。
 *
 * @param adapters 機種 ID → アダプタ。
 */
private class DefaultMultipointAdapterRegistry(
    private val adapters: Map<String, MultipointAdapter>,
) : MultipointAdapterRegistry {

    override fun adapterFor(modelId: String): MultipointAdapter? = adapters[modelId]
}

/**
 * キャッシュと機種アダプタをまとめる [TargetRepository] の実装（Windows 版）。
 *
 * Android 版 `DefaultTargetRepository` と同じ契約:
 * - [readCached] はキャッシュをそのまま返す。無ければ null。
 * - [fetch] は機種アダプタが無ければ [FailureReason.UnsupportedModel] を返す。
 * - 取得成功時のみキャッシュを更新する。失敗時に書き換えると古い情報を最新として保存する。
 * - アダプタが例外を投げても落とさず [FailureReason.Unknown] に変換する。
 *
 * @param registry 機種 ID からアダプタを引くレジストリ。
 * @param cache 接続先一覧のローカルキャッシュ。
 * @param now 現在時刻の供給元。テストで固定するために注入する。
 */
private class DefaultTargetRepository(
    private val registry: MultipointAdapterRegistry,
    private val cache: TargetCache,
    private val now: () -> Long = System::currentTimeMillis,
) : TargetRepository {

    override suspend fun readCached(device: EarbudsDevice): TargetSnapshot? =
        cache.read(device.address)

    override suspend fun fetch(device: EarbudsDevice): TargetListResult {
        val adapter = registry.adapterFor(device.model.id)
            ?: return TargetListResult.Unavailable(FailureReason.UnsupportedModel)

        val result = try {
            adapter.readTargets(device)
        } catch (e: CancellationException) {
            // コルーチンのキャンセルは失敗理由に変換せず、そのまま伝播させる。
            throw e
        } catch (e: Exception) {
            TargetListResult.Unavailable(FailureReason.Unknown)
        }

        if (result is TargetListResult.Available) {
            // 取得できたときだけキャッシュを更新する。
            cache.write(
                device.address,
                TargetSnapshot(
                    targets = result.targets,
                    observedAt = now(),
                    fromCache = false,
                ),
            )
        }
        return result
    }
}

/**
 * `java.util.Properties` に接続先一覧を保存するローカルキャッシュ。
 *
 * 仕様書 F-06「初期画面はローカルキャッシュから即描画し、状態更新を非同期に実行する」に対応する。
 * 保存先は端末内に閉じ、外部送信しない（仕様書 §6）。
 *
 * 保存形式:
 * - キーは `targets.<address>.<連番>.<項目>`。表示名に区切り文字が入っても壊れないよう、
 *   表示名は値としてだけ書き、キーには使わない。
 * - [TargetSnapshot.observedAt] も保存する。
 *
 * 方針:
 * - 読み出し時は [TargetSnapshot.fromCache] を必ず true にする。キャッシュから読んだ値は
 *   常に「古い可能性がある」ものとして扱うため（仕様書 §5）。
 * - 壊れたファイルは例外を投げず「無い」として扱う。キャッシュは無くても動作する。
 * - コルーチンのキャンセルだけは握りつぶさず、そのまま伝播させる。
 *
 * @param file 保存先ファイル。親ディレクトリは必要に応じて作る。
 */
private class PropertiesTargetCache(
    private val file: File,
) : TargetCache {

    override suspend fun read(deviceAddress: String): TargetSnapshot? = withContext(Dispatchers.IO) {
        val properties = loadProperties(file) ?: return@withContext null
        try {
            decode(properties, deviceAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 壊れたデータは無いものとして扱う。アドレスはログに出さない。
            null
        }
    }

    override suspend fun write(
        deviceAddress: String,
        snapshot: TargetSnapshot,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            // 既存の他端末ぶんを消さないよう、読み直してから該当キーだけ差し替える。
            val properties = loadProperties(file) ?: Properties()
            clearDevice(properties, deviceAddress)
            encode(properties, deviceAddress, snapshot)
            storeProperties(file, properties)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保存失敗でアプリを止めない。次回起動時に復元できないだけである。
        }
    }

    override suspend fun clear(deviceAddress: String): Unit = withContext(Dispatchers.IO) {
        try {
            val properties = loadProperties(file) ?: return@withContext
            clearDevice(properties, deviceAddress)
            storeProperties(file, properties)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 削除失敗も致命的ではない。
        }
    }

    private fun encode(
        properties: Properties,
        deviceAddress: String,
        snapshot: TargetSnapshot,
    ) {
        val prefix = keyPrefix(deviceAddress)
        properties.setProperty("$prefix$FIELD_OBSERVED_AT", snapshot.observedAt.toString())
        properties.setProperty("$prefix$FIELD_COUNT", snapshot.targets.size.toString())
        snapshot.targets.forEachIndexed { index, target ->
            val entry = "$prefix$index."
            properties.setProperty("${entry}$FIELD_ID", target.id)
            properties.setProperty("${entry}$FIELD_DISPLAY_NAME", target.displayName)
            properties.setProperty("${entry}$FIELD_KIND", target.kind.name)
            properties.setProperty("${entry}$FIELD_STATUS", target.status.name)
            properties.setProperty(
                "${entry}$FIELD_IS_THIS_DEVICE",
                target.isThisDevice.toString(),
            )
        }
    }

    private fun decode(properties: Properties, deviceAddress: String): TargetSnapshot? {
        val prefix = keyPrefix(deviceAddress)
        val observedAt = properties.getProperty("$prefix$FIELD_OBSERVED_AT")
            ?.toLongOrNull()
            ?: return null
        val count = properties.getProperty("$prefix$FIELD_COUNT")?.toIntOrNull() ?: 0

        val targets = buildList {
            for (index in 0 until count) {
                val entry = "$prefix$index."
                // id が無い要素は識別できないため捨てる。
                val id = properties.getProperty("${entry}$FIELD_ID")
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                add(
                    MultipointTarget(
                        id = id,
                        displayName = properties.getProperty("${entry}$FIELD_DISPLAY_NAME")
                            ?: id,
                        kind = enumOrDefault(
                            properties.getProperty("${entry}$FIELD_KIND"),
                            TargetKind.UNKNOWN,
                        ),
                        status = enumOrDefault(
                            properties.getProperty("${entry}$FIELD_STATUS"),
                            ConnectionStatus.UNKNOWN,
                        ),
                        isThisDevice = properties.getProperty("${entry}$FIELD_IS_THIS_DEVICE")
                            ?.toBooleanStrictOrNull() ?: false,
                    ),
                )
            }
        }

        return TargetSnapshot(
            targets = targets,
            observedAt = observedAt,
            // キャッシュから読んだ値は常にキャッシュ扱い（仕様書 §5）。
            fromCache = true,
        )
    }

    /** 指定イヤホンぶんのキーを消す。 */
    private fun clearDevice(properties: Properties, deviceAddress: String) {
        val prefix = keyPrefix(deviceAddress)
        val keys = properties.stringPropertyNames().filter { it.startsWith(prefix) }
        keys.forEach { properties.remove(it) }
    }

    /** イヤホンごとのキー前置き。 */
    private fun keyPrefix(deviceAddress: String): String = "$KEY_PREFIX$deviceAddress."

    /** 未知の enum 名で落ちないよう、既定値に落とす。 */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        /** キーの前置き。イヤホンのアドレスと組み合わせて一意にする。 */
        const val KEY_PREFIX = "targets."

        const val FIELD_OBSERVED_AT = "observedAt"
        const val FIELD_COUNT = "count"
        const val FIELD_ID = "id"
        const val FIELD_DISPLAY_NAME = "displayName"
        const val FIELD_KIND = "kind"
        const val FIELD_STATUS = "status"
        const val FIELD_IS_THIS_DEVICE = "isThisDevice"
    }
}

/**
 * `java.util.Properties` にユーザー設定を保存する。
 *
 * 仕様書 F-07「前回選択機種を復元する」に対応する。
 * 保存するのは Bluetooth アドレスのみで、端末内に閉じる（仕様書 §6）。
 *
 * @param file 保存先ファイル。キャッシュとは分ける。
 */
private class PropertiesSettingsRepository(
    private val file: File,
) : SettingsRepository {

    override suspend fun lastSelectedAddress(): String? = withContext(Dispatchers.IO) {
        try {
            loadProperties(file)?.getProperty(KEY_LAST_SELECTED_ADDRESS)
                ?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 読めなければ「未設定」として扱う。復元できないだけで動作は継続できる。
            null
        }
    }

    override suspend fun setLastSelectedAddress(address: String): Unit =
        withContext(Dispatchers.IO) {
            try {
                val properties = loadProperties(file) ?: Properties()
                properties.setProperty(KEY_LAST_SELECTED_ADDRESS, address)
                storeProperties(file, properties)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存失敗でアプリを止めない。次回起動時に復元できないだけである。
            }
        }

    private companion object {
        const val KEY_LAST_SELECTED_ADDRESS = "last_selected_address"
    }
}

/**
 * Properties ファイルを読む。
 *
 * 壊れたファイルや読めないファイルは例外を投げず null を返す（「無い」として扱う）。
 */
private fun loadProperties(file: File): Properties? {
    if (!file.isFile) return null
    return try {
        Properties().apply {
            FileInputStream(file).use { load(it) }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Properties ファイルを書く。
 *
 * 親ディレクトリが無ければ作る。一時ファイルに書いてから置き換え、
 * 書き込み途中で落ちても既存の内容を壊さないようにする。
 */
private fun storeProperties(file: File, properties: Properties) {
    val parent = file.parentFile
    if (parent != null && !parent.isDirectory) parent.mkdirs()

    val temporary = File(parent, "${file.name}.tmp")
    FileOutputStream(temporary).use { properties.store(it, null) }

    // Windows の renameTo は既存ファイルがあると失敗するため、先に消してから置き換える。
    if (file.exists() && !file.delete()) {
        temporary.delete()
        FileOutputStream(file).use { properties.store(it, null) }
        return
    }
    if (!temporary.renameTo(file)) {
        // 置き換えに失敗したら直接書く。内容は失われない。
        FileOutputStream(file).use { properties.store(it, null) }
        temporary.delete()
    }
}
