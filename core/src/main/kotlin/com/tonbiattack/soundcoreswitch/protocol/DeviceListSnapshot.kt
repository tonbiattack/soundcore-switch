package com.tonbiattack.soundcoreswitch.protocol

/**
 * 分割パケットから復元した接続先一覧。
 *
 * 接続先一覧は複数のパケットに分かれて届く。**すべて揃ったとは限らない。**
 * 表示用途では部分取得でも意味があるが、自動切断の対象を選ぶ処理や
 * 接続成功の判定に部分取得を使うと、存在しない端末を「無い」と誤認したり、
 * 実際には接続中の端末を「切断中」と誤認したりする。
 *
 * そのため「完全に取得できたか」を型で区別する。
 *
 * @property devices 取得できた接続先。順序はパケット番号順。
 * @property isComplete すべてのパケットが揃っているか。
 * @property expectedTotal パケットが申告した総数。取得できなければ 0。
 * @property receivedCount 実際に受け取れたパケット数。
 */
data class DeviceListSnapshot(
    val devices: List<RawDevice>,
    val isComplete: Boolean,
    val expectedTotal: Int,
    val receivedCount: Int,
) {
    /**
     * 完全に取得できた一覧だけを返す。
     *
     * 不完全な一覧で切り替え判断をしてはならない場面で使う。
     */
    fun completeOrNull(): List<RawDevice>? = if (isComplete) devices else null

    companion object {
        /** 1件も取得できなかった。 */
        val EMPTY = DeviceListSnapshot(
            devices = emptyList(),
            isComplete = false,
            expectedTotal = 0,
            receivedCount = 0,
        )
    }
}

/**
 * 分割パケットの整合性を検査して1つの一覧に組み立てる。
 *
 * 検査する内容:
 * - `total` が全パケットで一致するか
 * - `index` が 1..total の範囲に収まっているか
 * - 欠番が無いか
 * - 重複した `index` が無いか
 *
 * 1つでも破れていれば「不完全」として扱う。**推測で埋めない。**
 * 埋めると、存在しない端末を実在すると誤認させる危険がある。
 */
object DeviceListAssembler {

    /**
     * @param chunks 受信した分割パケット。順序は問わない。
     * @return 組み立てた一覧。整合性が取れなければ [DeviceListSnapshot.isComplete] が false。
     */
    fun assemble(chunks: List<DeviceListChunk>): DeviceListSnapshot {
        if (chunks.isEmpty()) return DeviceListSnapshot.EMPTY

        // total は全パケットで一致していなければならない。
        // 不一致は別々の応答が混ざった可能性があり、信用できない。
        val totals = chunks.map { it.total }.toSet()
        if (totals.size != 1) {
            return DeviceListSnapshot(
                devices = emptyList(),
                isComplete = false,
                expectedTotal = totals.maxOrNull() ?: 0,
                receivedCount = chunks.size,
            )
        }

        val total = totals.first()
        // total が 0 以下なら、そもそも一覧を持たない応答。
        if (total <= 0) {
            return DeviceListSnapshot(
                devices = emptyList(),
                isComplete = true,
                expectedTotal = 0,
                receivedCount = chunks.size,
            )
        }

        // index は 1..total。範囲外は壊れた応答として扱う。
        val inRange = chunks.filter { it.index in 1..total }
        if (inRange.size != chunks.size) {
            return DeviceListSnapshot(
                devices = emptyList(),
                isComplete = false,
                expectedTotal = total,
                receivedCount = chunks.size,
            )
        }

        // 重複した index があれば、どちらが正しいか決められない。
        val byIndex = inRange.groupBy { it.index }
        if (byIndex.any { (_, group) -> group.size > 1 }) {
            return DeviceListSnapshot(
                devices = emptyList(),
                isComplete = false,
                expectedTotal = total,
                receivedCount = chunks.size,
            )
        }

        // パケット番号順に連結する。受信順に依存しない。
        val ordered = byIndex.toSortedMap().values.map { it.single() }
        val devices = ordered.flatMap { it.devices }
        // 同じ接続先が複数の応答に混じると、異なる時点の状態が混在した可能性がある。
        // どちらが最新かは判断できないため、操作に使える完全な一覧として扱わない。
        val uniqueIds = devices.map { it.mac.uppercase() }.toSet()
        val complete = ordered.size == total && uniqueIds.size == devices.size

        return DeviceListSnapshot(
            devices = devices,
            isComplete = complete,
            expectedTotal = total,
            receivedCount = ordered.size,
        )
    }
}
