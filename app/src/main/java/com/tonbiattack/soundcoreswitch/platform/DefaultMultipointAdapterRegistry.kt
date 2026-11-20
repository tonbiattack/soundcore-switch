package com.tonbiattack.soundcoreswitch.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.tonbiattack.soundcoreswitch.adapter.SoundcoreMultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapterRegistry
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings

/**
 * 機種 ID からアダプタを引くレジストリの実装。
 *
 * **実機検証が完了した機種だけを登録する。** 仕様書 §7 の合格基準を満たしていない機種を
 * ここに登録してはならない。未検証の機種で切り替えを有効にすると
 * 「切り替えたつもりで切り替わっていない」という最悪の誤動作につながる。
 *
 * 登録の判断は [com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] の
 * [com.tonbiattack.soundcoreswitch.domain.model.SupportLevel] と必ず一致させること。
 * 片方だけ更新すると「UI 上は有効なのに切り替えられない」または
 * 「切り替えられるのに UI が無効」という不整合が起きる。
 *
 * 現在の登録状況:
 * - `liberty-4-nc` (A3947): 登録する。一覧取得・切り替え・完了検出まで実機で確認済み
 *   （`docs/SUPPORT_MATRIX.md`）。
 * - `space-a40` (A3936): **登録しない。** ベンダー UUID は検出済みだがコマンドの実機検証が
 *   未完了であり、[com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] でも
 *   [com.tonbiattack.soundcoreswitch.domain.model.SupportLevel.UNKNOWN] のため。
 *   未登録でも UI 側で無効化されるが、二重の安全策として登録もしない。
 *
 * 機種を追加する手順:
 * 1. 仕様書 §7 の合格基準を満たす実機検証を行う。
 * 2. [com.tonbiattack.soundcoreswitch.domain.model.ModelCatalog] の該当機種の
 *    [com.tonbiattack.soundcoreswitch.domain.model.SupportLevel] を更新する。
 * 3. [verifiedAdapters] に `modelId to 実装` を登録する。
 * 4. `docs/SUPPORT_MATRIX.md` を更新する。
 *
 * @param context アプリケーションコンテキスト。RFCOMM 接続に使う。
 *   Activity を渡すとリークするため、呼び出し側は `applicationContext` を渡すこと。
 * @param adapters 機種 ID → アダプタ。既定は実機検証済みの機種のみ。
 */
class DefaultMultipointAdapterRegistry(
    context: Context,
    /**
     * 保護設定の供給元。
     *
     * 接続上限に達したとき、保護された端末を自動切断しないために使う。
     * 設定は非同期に読むため、同期的に取得できるキャッシュを渡す想定。
     */
    protectionProvider: () -> ProtectionSettings = { ProtectionSettings() },
    private val adapters: Map<String, MultipointAdapter> = verifiedAdapters(
        context,
        protectionProvider,
    ),
) : MultipointAdapterRegistry {

    override fun adapterFor(modelId: String): MultipointAdapter? = adapters[modelId]

    private companion object {
        /**
         * 実機検証が完了した機種のアダプタを組み立てる。
         *
         * ここに追加できるのは `docs/SUPPORT_MATRIX.md` で `supported` と記録された機種だけ。
         */
        fun verifiedAdapters(
            context: Context,
            protectionProvider: () -> ProtectionSettings,
        ): Map<String, MultipointAdapter> {
            // トランスポートは機種に依存しない（ベンダー UUID は機種コードから組み立てる）。
            // そのため1つのインスタンスを全機種で共有する。
            val transport = AndroidSoundcoreTransport(context)
            // 接続上限に達したとき、この端末自身を切断しないために ID を渡す。
            val thisDeviceId = { localBluetoothAddress(context) }
            // MAC が取れない場合（Android 10 以降）は名前で照合する。
            val thisDeviceName = { localBluetoothName(context) }
            val logger: (String) -> Unit = { message -> Log.d("SoundcoreAdapter", message) }

            return mapOf(
                // Liberty 4 NC (A3947)。MODEL_ID は "liberty-4-nc"。
                SoundcoreMultipointAdapter.MODEL_ID to SoundcoreMultipointAdapter(
                    transport = transport,
                    thisDeviceIdProvider = thisDeviceId,
                    thisDeviceNameProvider = thisDeviceName,
                    protectionProvider = protectionProvider,
                    logger = logger,
                ),
                // Space A40 (A3936)。2026-11-17 に実機で一覧取得・切断・接続を確認。
                SoundcoreMultipointAdapter.SPACE_A40_MODEL_ID to SoundcoreMultipointAdapter(
                    transport = transport,
                    thisDeviceIdProvider = thisDeviceId,
                    thisDeviceNameProvider = thisDeviceName,
                    protectionProvider = protectionProvider,
                    logger = logger,
                ),
            )
        }

        /**
         * この端末の Bluetooth アドレス（MAC）。
         *
         * 接続先一覧の ID と照合して「操作元の端末」を特定するために使う。
         * **名前ではなく MAC で判定する**（端末名は変更でき、同名もあり得るため）。
         *
         * Android 6.0 以降、`BluetoothAdapter.getAddress()` はアプリに
         * `02:00:00:00:00:00` を返すため使えない。取得できなければ null を返し、
         * 呼び出し側は [localBluetoothName] にフォールバックする。
         */
        @SuppressLint("MissingPermission", "HardwareIds")
        fun localBluetoothAddress(context: Context): String? = runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: return null
            // 公開 API では取得できないため、非公開メソッドを読む。
            // 失敗しても安全側に倒れるだけなので、例外は握る。
            val method = adapter.javaClass.getMethod("getAddress")
            (method.invoke(adapter) as? String)
                ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        }.getOrNull()

        /**
         * この端末の Bluetooth 名。
         *
         * MAC を取得できない場合の代替。`Settings.Secure` の `bluetooth_name` に
         * 保存されている値を使う（`BluetoothAdapter.getName()` は権限や実装により
         * null を返すことがある）。
         *
         * 名前は変更でき同名もあり得るため、**MAC が取れない場合の最後の手段**として使う。
         */
        @SuppressLint("MissingPermission")
        fun localBluetoothName(context: Context): String? = runCatching {
            // まず Settings.Secure を読む（権限不要で確実）。
            val fromSettings = Settings.Secure.getString(
                context.contentResolver,
                "bluetooth_name",
            )?.takeIf { it.isNotBlank() }
            if (fromSettings != null) return fromSettings

            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.name?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
