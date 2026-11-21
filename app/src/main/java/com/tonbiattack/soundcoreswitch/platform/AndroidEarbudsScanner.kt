package com.tonbiattack.soundcoreswitch.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.adapter.EarbudsScanner
import com.tonbiattack.soundcoreswitch.domain.adapter.ScanResult
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsModel
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog
import com.tonbiattack.soundcoreswitch.domain.model.SupportLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ペアリング済み端末から Soundcore イヤホンを列挙する。
 *
 * 仕様書 F-01「検出済み・登録済みのイヤホンを表示し、機種別の対応状況を分かるようにする」
 * に対応する。ペアリング済み一覧は [android.bluetooth.BluetoothAdapter.getBondedDevices] で
 * 取得できるが、これは **Android 側から見える端末一覧** であり、イヤホン内部のマルチポイント
 * 登録一覧とは別物である（仕様書 F-03）。ここで作る [EarbudsDevice] は前者だけを表す。
 *
 * 重要な原則:
 * - 対応レベルは必ず [ModelCatalog.supportLevelOf] から引く。ここで [SupportLevel.SUPPORTED] を
 *   決め打ちしてはならない（未検証機種の誤動作を防ぐため）。
 * - 接続状態は非公開 API の [BluetoothDevice.isConnected] を使わず、公開 API の
 *   [BluetoothManager.getConnectedDevices] で判定する（仕様書 §6「非公開OS APIへの依存は原則回避」）。
 * - 判定できなければ [ConnectionStatus.UNKNOWN] とし、[ConnectionStatus.DISCONNECTED] と混同しない
 *   （仕様書 F-02）。
 * - ログに Bluetooth アドレスを出さない（仕様書 §6）。
 *
 * @param context アプリケーションコンテキスト。
 * @param bluetooth Bluetooth の利用可否判定。オフ・権限不足の切り分けをここに委ねる。
 */
class AndroidEarbudsScanner(
    private val context: Context,
    private val bluetooth: BluetoothAvailabilityChecker,
) : EarbudsScanner {

    override suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        when (bluetooth.check()) {
            BluetoothAvailability.Ready -> scanBondedDevices()
            BluetoothAvailability.Off -> ScanResult.Unavailable(FailureReason.BluetoothOff)
            BluetoothAvailability.PermissionDenied ->
                ScanResult.Unavailable(FailureReason.PermissionDenied)
            // Bluetooth 非対応端末。FailureReason に対応する値が無いため汎用の理由に落とす。
            BluetoothAvailability.NotSupported -> ScanResult.Unavailable(FailureReason.Unknown)
            is BluetoothAvailability.Unknown -> ScanResult.Unavailable(FailureReason.Unknown)
        }
    }

    // 権限は scan() の冒頭で bluetooth.check() により確認済み。Lint はその関連を追えないため抑制する。
    @SuppressLint("MissingPermission")
    private fun scanBondedDevices(): ScanResult {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return ScanResult.Unavailable(FailureReason.Unknown)
        val adapter = manager.adapter ?: return ScanResult.Unavailable(FailureReason.Unknown)

        // 権限は呼び出し前に確認済みだが、直前に取り消される可能性があるため例外は握る。
        val bonded = try {
            adapter.bondedDevices
        } catch (e: Exception) {
            return ScanResult.Unavailable(FailureReason.PermissionDenied)
        }

        val connected = connectedAddresses(manager, bonded)

        val devices = bonded.mapNotNull { device -> toEarbudsDevice(device, connected) }
            .sortedBy { it.displayName.lowercase() }
        val withAudioOutput = markConnectedAudioOutputs(devices)

        Log.d(TAG, "ペアリング済み ${bonded.size} 件のうち Soundcore 候補 ${devices.size} 件")
        return ScanResult.Found(withAudioOutput)
    }

    /**
     * 1台を [EarbudsDevice] に変換する。Soundcore と判断できなければ null。
     *
     * 表示名が取得できない端末は機種を判定できないため対象から外す。
     */
    @SuppressLint("MissingPermission")
    private fun toEarbudsDevice(
        device: BluetoothDevice,
        connected: ConnectedSnapshot,
    ): EarbudsDevice? {
        // 表示名の取得は権限状態によっては失敗する。失敗した端末は判定不能として除外する。
        val displayName = runCatching { device.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val model = ModelCatalog.matchByDisplayName(displayName)
            ?: if (looksLikeSoundcore(displayName)) {
                // 既知の機種に一致しない Soundcore 製品。対応レベルは UNKNOWN のまま扱う。
                EarbudsModel(id = UNKNOWN_MODEL_ID, displayName = displayName)
            } else {
                return null
            }

        return EarbudsDevice(
            address = device.address,
            displayName = displayName,
            model = model,
            // 対応レベルは必ずカタログから引く。ここで決め打ちしない。
            supportLevel = ModelCatalog.supportLevelOf(model.id),
            connection = connectionStatusOf(device, connected),
        )
    }

    /**
     * 公開 AudioManager API の Bluetooth 音声出力を接続中として補完する。
     * BluetoothManager は A2DP / HEADSET を扱えない端末がある。
     * アドレスで照合できない場合は、一意に一致する名前だけを使う。
     * 同名のイヤホンが複数ある場合は推測しない。
     */
    private fun markConnectedAudioOutputs(devices: List<EarbudsDevice>): List<EarbudsDevice> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return devices
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return devices
        val audioOutputs = outputs.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val identities = audioOutputs.map { output ->
            AudioOutputIdentity(
                address = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    runCatching { output.address }.getOrNull()
                } else {
                    null
                },
                name = output.productName?.toString(),
            )
        }
        return markConnectedAudioOutputs(devices, identities)
    }

    /** 既知の機種に一致しなくても Soundcore 製品と推定できる表示名か。 */
    private fun looksLikeSoundcore(displayName: String): Boolean =
        displayName.lowercase().contains(VENDOR_KEYWORD)

    /**
     * 接続状態を公開 API から判定する。
     *
     * イヤホンは A2DP / HFP（クラシック）で接続される。BLE の GATT は別経路であり、
     * 音声接続の有無を表さない。
     *
     * **`getConnectedDevices()` だけに頼らない理由**（OPPO CPH2699 / Android 16 で実測）:
     * この端末では A2DP と HEADSET の `getConnectedDevices()` が失敗し、
     * `getConnectionState()` も `null` を返した。GATT だけが応答し、しかも
     * 接続中のイヤホンに対して `STATE_DISCONNECTED` を返した。
     * その結果、実際には A2DP で接続中のイヤホンを「切断中」と誤判定した。
     *
     * そのため、**クラシック音声プロファイル（A2DP / HEADSET）のいずれかを
     * 確認できた場合に限り**判定完了とみなす。GATT だけでは判定しない。
     * 判定できない場合は [ConnectionStatus.UNKNOWN] とし、切断と断定しない
     * （仕様書 F-02「取得不可は不明」）。
     *
     * @return 接続中アドレスの集合と、判定を完了できたかどうか。
     */
    @SuppressLint("MissingPermission")
    private fun connectedAddresses(
        manager: BluetoothManager,
        bonded: Set<BluetoothDevice>,
    ): ConnectedSnapshot {
        val addresses = mutableSetOf<String>()
        var classicProfileResolved = false

        // 1. プロファイル単位の一覧。取れれば最も確実。
        for (profile in MONITORED_PROFILES) {
            val result = runCatching { manager.getConnectedDevices(profile) }
            val devices = result.getOrNull()
            if (devices == null) {
                Log.d(
                    TAG,
                    "profile=$profile 取得失敗 ${result.exceptionOrNull()?.javaClass?.simpleName}",
                )
                continue
            }
            if (profile in CLASSIC_AUDIO_PROFILES) classicProfileResolved = true
            devices.mapNotNullTo(addresses) { runCatching { it.address }.getOrNull() }
        }

        // 2. デバイス単位の接続状態。プロファイル一覧が取れない端末向けの代替経路。
        for (device in bonded) {
            val address = runCatching { device.address }.getOrNull() ?: continue
            if (address in addresses) continue
            for (profile in MONITORED_PROFILES) {
                val state = runCatching { manager.getConnectionState(device, profile) }.getOrNull()
                if (state == null) continue
                if (profile in CLASSIC_AUDIO_PROFILES) classicProfileResolved = true
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    addresses += address
                    break
                }
            }
        }

        Log.d(
            TAG,
            "接続中=${addresses.size} 件 クラシック音声の判定可否=$classicProfileResolved",
        )
        return ConnectedSnapshot(
            addresses = addresses,
            // GATT しか確認できていない場合は、切断と断定できない。
            complete = classicProfileResolved,
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectionStatusOf(
        device: BluetoothDevice,
        connected: ConnectedSnapshot,
    ): ConnectionStatus {
        val address = runCatching { device.address }.getOrNull() ?: return ConnectionStatus.UNKNOWN
        // いずれかのプロファイルで接続中なら CONNECTED。
        if (address in connected.addresses) return ConnectionStatus.CONNECTED
        // 判定を完了できたうえで見つからない場合だけ DISCONNECTED と言える。
        // 判定できていない場合は、切断と断定せず UNKNOWN にする（仕様書 F-02）。
        return if (connected.complete) ConnectionStatus.DISCONNECTED else ConnectionStatus.UNKNOWN
    }

    /**
     * 接続中アドレスの集合。
     *
     * @property addresses いずれかのプロファイルで接続中だった端末のアドレス。
     * @property complete 対象プロファイルをすべて取得できたか。false なら判定は不完全。
     */
    private data class ConnectedSnapshot(
        val addresses: Set<String>,
        val complete: Boolean,
    )

    private companion object {
        const val TAG = "EarbudsScanner"

        /** 既知の機種に一致しない場合の機種 ID。対応レベルは UNKNOWN になる。 */
        const val UNKNOWN_MODEL_ID = "unknown"

        /** 表示名から Soundcore 製品と推定するためのキーワード。 */
        const val VENDOR_KEYWORD = "soundcore"

        /**
         * 接続状態の判定に使うプロファイル。
         * GATT だけではクラシック接続（A2DP/HFP）を取りこぼすため、音声系も含める。
         */
        val MONITORED_PROFILES = intArrayOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.GATT,
        )

        /**
         * クラシック音声プロファイル。
         *
         * イヤホンの接続はこのいずれかで表される。GATT は BLE の別経路であり、
         * 音声接続の有無を表さないため、これだけで「切断中」と断定してはならない。
         */
        val CLASSIC_AUDIO_PROFILES = intArrayOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
        )
    }
}
