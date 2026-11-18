package com.tonbiattack.soundcoreswitch.testing

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.EarbudsScanner
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsModel
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import com.tonbiattack.soundcoreswitch.domain.model.SupportLevel
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.repository.SettingsRepository
import com.tonbiattack.soundcoreswitch.domain.repository.TargetCache
import com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository

/**
 * テスト用のフェイク実装。
 *
 * ドメイン層は Android フレームワークに依存しないため、モックライブラリ無しで
 * 手書きのフェイクだけでテストできる。ここに集約して各テストから再利用する。
 */

/** 固定の Bluetooth 状態を返す。 */
class FakeBluetoothChecker(
    var availability: BluetoothAvailability = BluetoothAvailability.Ready,
) : BluetoothAvailabilityChecker {
    override fun check(): BluetoothAvailability = availability
}

/** 固定の検出結果を返す。 */
class FakeScanner(
    var result: ScanResult = ScanResult.Found(emptyList()),
) : EarbudsScanner {
    var scanCount: Int = 0
        private set

    override suspend fun scan(): ScanResult {
        scanCount++
        return result
    }
}

/**
 * 呼び出しを記録するアダプタ。
 *
 * [switchResult] と [targetsAfterSwitch] を差し替えることで、
 * 「要求は成功したが状態が変わっていない」ケースを再現できる。
 */
class FakeMultipointAdapter(
    override val modelId: String,
    var targets: TargetListResult = TargetListResult.Available(emptyList()),
    var switchResult: SwitchResult = SwitchResult.Confirmed(
        MultipointTarget(id = "t1", displayName = "PC"),
    ),
    var targetsAfterSwitch: TargetListResult? = null,
    var throwOnSwitch: Boolean = false,
) : MultipointAdapter {
    var switchCalls: Int = 0
        private set
    var readCalls: Int = 0
        private set

    override suspend fun readTargets(device: EarbudsDevice): TargetListResult {
        readCalls++
        // 切り替え後の再取得では、指定があればそちらを返す。
        val after = targetsAfterSwitch
        if (switchCalls > 0 && after != null) return after
        return targets
    }

    override suspend fun switchTo(device: EarbudsDevice, target: MultipointTarget): SwitchResult {
        switchCalls++
        if (throwOnSwitch) error("boom")
        return switchResult
    }
}

/** 機種 ID でアダプタを引く。未登録なら null。 */
class FakeRegistry(
    private val adapters: Map<String, MultipointAdapter> = emptyMap(),
) : MultipointAdapterRegistry {
    override fun adapterFor(modelId: String): MultipointAdapter? = adapters[modelId]
}

/** メモリ上のキャッシュ。 */
class InMemoryTargetCache : TargetCache {
    private val store = mutableMapOf<String, TargetSnapshot>()

    override suspend fun read(deviceAddress: String): TargetSnapshot? = store[deviceAddress]

    override suspend fun write(deviceAddress: String, snapshot: TargetSnapshot) {
        store[deviceAddress] = snapshot
    }

    override suspend fun clear(deviceAddress: String) {
        store.remove(deviceAddress)
    }

    /** テストから直接中身を確認するため。 */
    fun peek(deviceAddress: String): TargetSnapshot? = store[deviceAddress]
}

/** メモリ上の設定。 */
class InMemorySettings(
    private var address: String? = null,
    private var favorites: Set<String> = emptySet(),
    private var protection: ProtectionSettings = ProtectionSettings(),
) : SettingsRepository {
    override suspend fun lastSelectedAddress(): String? = address

    override suspend fun setLastSelectedAddress(address: String) {
        this.address = address
    }

    override suspend fun favoriteTargetIds(): Set<String> = favorites

    override suspend fun setFavorite(targetId: String, favorite: Boolean) {
        favorites = if (favorite) favorites + targetId else favorites - targetId
    }

    override suspend fun protectionSettings(): ProtectionSettings = protection

    override suspend fun setProtectionSettings(settings: ProtectionSettings) {
        protection = settings
    }
}

/**
 * キャッシュとアダプタをまとめる本番同等のリポジトリ。
 * テストでは [fetchResult] を差し替えて失敗経路を作る。
 */
class FakeTargetRepository(
    private val cache: TargetCache = InMemoryTargetCache(),
    var fetchResult: TargetListResult = TargetListResult.Available(emptyList()),
    private val clock: () -> Long = { 0L },
) : TargetRepository {
    var fetchCount: Int = 0
        private set

    override suspend fun readCached(device: EarbudsDevice): TargetSnapshot? =
        cache.read(device.address)

    override suspend fun fetch(device: EarbudsDevice): TargetListResult {
        fetchCount++
        val result = fetchResult
        if (result is TargetListResult.Available) {
            cache.write(
                device.address,
                TargetSnapshot(
                    targets = result.targets,
                    observedAt = clock(),
                    fromCache = false,
                ),
            )
        }
        return result
    }
}

/** テスト用のイヤホンを作る。 */
fun earbuds(
    address: String = "AA:BB:CC:DD:EE:FF",
    displayName: String = "Space A40",
    modelId: String = "space-a40",
    supportLevel: SupportLevel = SupportLevel.SUPPORTED,
    connection: ConnectionStatus = ConnectionStatus.CONNECTED,
): EarbudsDevice = EarbudsDevice(
    address = address,
    displayName = displayName,
    model = EarbudsModel(id = modelId, displayName = displayName),
    supportLevel = supportLevel,
    connection = connection,
)

/** テスト用の接続先を作る。 */
fun target(
    id: String = "t1",
    displayName: String = "Windows PC",
    status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    isThisDevice: Boolean = false,
): MultipointTarget = MultipointTarget(
    id = id,
    displayName = displayName,
    status = status,
    isThisDevice = isThisDevice,
)
