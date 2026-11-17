package com.tonbiattack.soundcoreswitch.platform

import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.repository.TargetCache
import com.tonbiattack.soundcoreswitch.domain.repository.TargetRepository
import kotlinx.coroutines.CancellationException

/**
 * キャッシュと機種アダプタをまとめる [TargetRepository] の実装。
 *
 * 上位層（ViewModel）はこのクラスだけに依存し、機種差分を知らない（仕様書 §8）。
 *
 * 方針:
 * - [readCached] はキャッシュをそのまま返す。無ければ null。
 * - [fetch] は機種アダプタが無ければ [FailureReason.UnsupportedModel] を返す。
 *   未対応機種で通信を試みない。
 * - 取得成功時のみキャッシュを更新する。失敗時に書き換えると、古い情報を最新として
 *   保存してしまうため（[TargetRepository.fetch] の契約）。
 * - アダプタが例外を投げても落とさず [FailureReason.Unknown] に変換する。
 *
 * @param registry 機種 ID からアダプタを引くレジストリ。
 * @param cache 接続先一覧のローカルキャッシュ。
 * @param now 現在時刻の供給元。テストで固定するために注入する。
 */
class DefaultTargetRepository(
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
