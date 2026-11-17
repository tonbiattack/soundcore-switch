package com.tonbiattack.soundcoreswitch.desktop.platform

import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.EarbudsScanner
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsModel
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ペアリング済み端末から Soundcore イヤホンを列挙する（Windows 版）。
 *
 * 仕様書 F-01「検出済み・登録済みのイヤホンを表示し、機種別の対応状況を分かるようにする」
 * に対応する。`BluetoothFindFirstDevice` / `BluetoothFindNextDevice` で
 * **Windows 側から見える端末一覧**を取得する。これはイヤホン内部のマルチポイント
 * 登録一覧とは別物である（仕様書 F-03）。
 *
 * 重要な原則:
 * - 対応レベルは必ず [ModelCatalog.supportLevelOf] から引く。ここで決め打ちしてはならない。
 * - 接続状態は `BLUETOOTH_DEVICE_INFO.fConnected` で判定する。取得できなければ
 *   [ConnectionStatus.UNKNOWN] とし、[ConnectionStatus.DISCONNECTED] と混同しない（仕様書 F-02）。
 * - 検索ハンドルは必ず `BluetoothFindDeviceClose` で閉じる。
 * - ログに Bluetooth アドレスを出さない（仕様書 §6）。
 *
 * @param bluetooth Bluetooth の利用可否判定。オフ・非対応の切り分けをここに委ねる。
 */
class WindowsEarbudsScanner(
    private val bluetooth: BluetoothAvailabilityChecker,
) : EarbudsScanner {

    override suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        when (bluetooth.check()) {
            BluetoothAvailability.Ready -> scanPairedDevices()
            BluetoothAvailability.Off -> ScanResult.Unavailable(FailureReason.BluetoothOff)
            // Windows に権限の概念は無いが、契約上あり得るため対称に扱う。
            BluetoothAvailability.PermissionDenied ->
                ScanResult.Unavailable(FailureReason.PermissionDenied)
            BluetoothAvailability.NotSupported -> ScanResult.Unavailable(FailureReason.Unknown)
            is BluetoothAvailability.Unknown -> ScanResult.Unavailable(FailureReason.Unknown)
        }
    }

    /**
     * ペアリング済み・接続中の端末を列挙する。
     *
     * 検索条件は仕様どおり「認証済み・記憶済み・接続中を返し、未知の端末は返さない」。
     * `fIssueInquiry=0` なので問い合わせは行わず、既知の端末だけを返す（高速）。
     */
    private fun scanPairedDevices(): ScanResult {
        val params = BluetoothDeviceSearchParams().apply {
            dwSize = BthProps.SEARCH_PARAMS_SIZE
            fReturnAuthenticated = 1
            fReturnRemembered = 1
            fReturnConnected = 1
            fReturnUnknown = 0
            fIssueInquiry = 0
            cTimeoutMultiplier = 0.toByte()
            // null なら全ラジオを対象にする。
            hRadio = null
        }

        val info = BluetoothDeviceInfo().apply { dwSize = BthProps.DEVICE_INFO_SIZE }

        val find = try {
            BthProps.INSTANCE.BluetoothFindFirstDevice(params, info)
        } catch (e: Exception) {
            // Win32 呼び出し自体が失敗する環境（Bluetooth サービス停止など）。
            return ScanResult.Unavailable(FailureReason.Unknown)
        }

        // ハンドルが返らないのは「該当端末が無い」場合がほとんどである。
        // ここを失敗扱いにすると「見つかりません」ではなく理由付きのエラーを出してしまう。
        // Bluetooth 自体の可否は呼び出し前に確認済みなので、0件として返す。
        if (find == null) return ScanResult.Found(emptyList())

        val devices = mutableListOf<EarbudsDevice>()
        try {
            var hasNext = true
            while (hasNext) {
                toEarbudsDevice(info)?.let { devices += it }
                // 次の端末を同じ構造体に受ける。dwSize は毎回設定し直す必要がある。
                info.dwSize = BthProps.DEVICE_INFO_SIZE
                hasNext = try {
                    BthProps.INSTANCE.BluetoothFindNextDevice(find, info)
                } catch (e: Exception) {
                    false
                }
            }
        } finally {
            // ハンドルは必ず閉じる。閉じ忘れると Bluetooth サービス側のリソースが残る。
            BthProps.INSTANCE.BluetoothFindDeviceClose(find)
        }

        return ScanResult.Found(devices.sortedBy { it.displayName.lowercase() })
    }

    /**
     * 1台を [EarbudsDevice] に変換する。Soundcore と判断できなければ null。
     *
     * 表示名が空の端末は機種を判定できないため対象から外す。
     */
    private fun toEarbudsDevice(info: BluetoothDeviceInfo): EarbudsDevice? {
        val displayName = info.name().trim()
        if (displayName.isEmpty()) return null

        val model = ModelCatalog.matchByDisplayName(displayName)
            ?: if (looksLikeSoundcore(displayName)) {
                // 既知の機種に一致しない Soundcore 製品。対応レベルは UNKNOWN のまま扱う。
                EarbudsModel(id = UNKNOWN_MODEL_ID, displayName = displayName)
            } else {
                return null
            }

        return EarbudsDevice(
            address = info.address.toCanonicalString(),
            displayName = displayName,
            model = model,
            // 対応レベルは必ずカタログから引く。ここで決め打ちしない。
            supportLevel = ModelCatalog.supportLevelOf(model.id),
            connection = if (info.isConnected()) {
                ConnectionStatus.CONNECTED
            } else {
                ConnectionStatus.DISCONNECTED
            },
        )
    }

    /** 既知の機種に一致しなくても Soundcore 製品と推定できる表示名か。 */
    private fun looksLikeSoundcore(displayName: String): Boolean =
        displayName.lowercase().contains(VENDOR_KEYWORD)

    private companion object {
        /** 既知の機種に一致しない場合の機種 ID。対応レベルは UNKNOWN になる。 */
        const val UNKNOWN_MODEL_ID = "unknown"

        /** 表示名から Soundcore 製品と推定するためのキーワード。 */
        const val VENDOR_KEYWORD = "soundcore"
    }
}
