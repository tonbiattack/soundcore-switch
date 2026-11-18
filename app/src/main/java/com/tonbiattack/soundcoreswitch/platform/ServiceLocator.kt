package com.tonbiattack.soundcoreswitch.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.EarbudsScanner
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry
import com.tonbiattack.soundcoreswitch.domain.repository.ProtectionCache
import com.tonbiattack.soundcoreswitch.domain.repository.SettingsRepository
import com.tonbiattack.soundcoreswitch.domain.repository.TargetCache
import com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository
import com.tonbiattack.soundcoreswitch.domain.usecase.SwitchTargetUseCase
import com.tonbiattack.soundcoreswitch.presentation.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * アプリ全体で共有する依存のまとまり。
 *
 * 手書き DI。ライブラリを増やさず、生成箇所をここ1か所に集約する。
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
class AppContainer(
    val bluetooth: BluetoothAvailabilityChecker,
    val scanner: EarbudsScanner,
    val targetCache: TargetCache,
    val targetRepository: TargetRepository,
    val settings: SettingsRepository,
    val registry: MultipointAdapterRegistry,
    val switchTarget: SwitchTargetUseCase,
    /** 保護設定のメモリキャッシュ。アダプタが同期的に参照する。 */
    val protectionCache: ProtectionCache,
)

/**
 * 依存の生成と保持を行うサービスロケータ。
 *
 * [initialize] は [com.tonbiattack.soundcoreswitch.SoundcoreSwitchApp] の `onCreate` から
 * 一度だけ呼ぶ。二重初期化は無視する（DataStore は同一ファイルに対して複数インスタンスを
 * 作れないため、作り直すと例外になる）。
 */
object ServiceLocator {

    /** キャッシュ用 DataStore のファイル名。設定用とは分ける。 */
    private const val CACHE_DATASTORE_NAME = "target_cache"

    /** 設定用 DataStore のファイル名。 */
    private const val SETTINGS_DATASTORE_NAME = "settings"

    @Volatile
    private var appContainer: AppContainer? = null

    /** 初期化済みの依存。未初期化で触ると [IllegalStateException]。 */
    val container: AppContainer
        get() = appContainer ?: error(
            "ServiceLocator が初期化されていません。" +
                "SoundcoreSwitchApp.onCreate で initialize を呼んでください。",
        )

    /** Bluetooth の利用可否判定。 */
    val bluetooth: BluetoothAvailabilityChecker
        get() = container.bluetooth

    /** イヤホン検出。 */
    val scanner: EarbudsScanner
        get() = container.scanner

    /** 接続先一覧のローカルキャッシュ。 */
    val targetCache: TargetCache
        get() = container.targetCache

    /** 接続先一覧の取得口。 */
    val targetRepository: TargetRepository
        get() = container.targetRepository

    /** ユーザー設定の永続化。 */
    val settings: SettingsRepository
        get() = container.settings

    /** 接続先切り替えのユースケース。 */
    val switchTarget: SwitchTargetUseCase
        get() = container.switchTarget

    /**
     * 依存を組み立てる。アプリ起動時に一度だけ呼ぶ。
     *
     * @param context アプリケーションコンテキスト。Activity を渡すとリークする。
     */
    @Synchronized
    fun initialize(context: Context) {
        if (appContainer != null) return
        val appContext = context.applicationContext

        // DataStore はキャッシュと設定でファイルを分ける。
        // 同居させるとキャッシュ削除時に設定まで消える恐れがある。
        val cacheStore = createDataStore(appContext, CACHE_DATASTORE_NAME)
        val settingsStore = createDataStore(appContext, SETTINGS_DATASTORE_NAME)

        val bluetooth = AndroidBluetoothAvailabilityChecker(appContext)
        val scanner = AndroidEarbudsScanner(appContext, bluetooth)
        val targetCache = DataStoreTargetCache(cacheStore)
        val settings = DataStoreSettingsRepository(settingsStore)

        // 保護設定はアダプタが同期的に参照する必要があるため、
        // DataStore の内容をメモリにキャッシュし、変更時に更新する。
        val protectionCache = ProtectionCache(settings)

        // 登録されるのは実機検証が完了した機種だけ（DefaultMultipointAdapterRegistry 参照）。
        // RFCOMM 接続に Context を使うため、リークしないよう applicationContext を渡す。
        val registry = DefaultMultipointAdapterRegistry(
            context = appContext,
            protectionProvider = { protectionCache.current() },
        )
        val targetRepository = DefaultTargetRepository(registry, targetCache)

        appContainer = AppContainer(
            bluetooth = bluetooth,
            scanner = scanner,
            targetCache = targetCache,
            targetRepository = targetRepository,
            settings = settings,
            registry = registry,
            switchTarget = SwitchTargetUseCase(registry, bluetooth),
            protectionCache = protectionCache,
        )
    }

    /**
     * 画面用の [AppViewModel] を組み立てる。
     *
     * @param scope 画面のライフサイクルに紐づくスコープ。呼び出し側が管理する。
     */
    fun createAppViewModel(scope: CoroutineScope): AppViewModel {
        val dependencies = container
        return AppViewModel(
            scanner = dependencies.scanner,
            targetRepository = dependencies.targetRepository,
            settings = dependencies.settings,
            switchTarget = dependencies.switchTarget,
            bluetooth = dependencies.bluetooth,
            scope = scope,
        )
    }

    private fun createDataStore(context: Context, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(name) },
        )
}
