package com.tonbiattack.soundcoreswitch.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailabilityChecker
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason

/**
 * Android 標準 API で Bluetooth の利用可否を判定する。
 *
 * 仕様書 F-05「Bluetoothオフ、権限不足、イヤホン未接続、タイムアウト、非対応を区別して通知する」
 * のうち、Bluetooth 非対応・オフ・権限不足の3つをここで切り分ける。
 *
 * 方針:
 * - 例外は投げない。判定に失敗したら [BluetoothAvailability.Unknown] に落とす
 *   （仕様書 §8「権限不足でクラッシュしない」）。
 * - 権限は Android 12 (API 31) 以降が [Manifest.permission.BLUETOOTH_CONNECT]、
 *   それ未満が [Manifest.permission.BLUETOOTH] である（仕様書 §6「最小限で要求する」）。
 * - 権限確認は [android.bluetooth.BluetoothAdapter.isEnabled] より**先**に行う。
 *   権限が無い状態で呼ぶと SecurityException になるため。
 *
 * @param context アプリケーションコンテキスト。Activity を渡すとリークするため、
 *   呼び出し側は `applicationContext` を渡すこと。
 */
class AndroidBluetoothAvailabilityChecker(
    private val context: Context,
) : BluetoothAvailabilityChecker {

    override fun check(): BluetoothAvailability =
        runCatching { resolve() }
            .getOrElse { BluetoothAvailability.Unknown(FailureReason.Unknown) }

    // 権限は hasConnectPermission() で確認済み。Lint はその関連を追えないため抑制する。
    @SuppressLint("MissingPermission")
    private fun resolve(): BluetoothAvailability {
        // Bluetooth 非対応端末ではマネージャ自体が取得できない。
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return BluetoothAvailability.NotSupported
        val adapter = manager.adapter ?: return BluetoothAvailability.NotSupported

        // 権限が無い状態で isEnabled を呼ぶと SecurityException になるため、必ず先に確認する。
        if (!hasConnectPermission()) return BluetoothAvailability.PermissionDenied

        return if (adapter.isEnabled) {
            BluetoothAvailability.Ready
        } else {
            BluetoothAvailability.Off
        }
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    /** Android バージョンごとに要求すべき Bluetooth 権限を返す。 */
    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
}
