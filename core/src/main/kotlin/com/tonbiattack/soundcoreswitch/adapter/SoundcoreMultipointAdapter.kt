package com.tonbiattack.soundcoreswitch.adapter

import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetKind
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.protocol.RawDevice
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreProtocol
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransport
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreTransportException
import com.tonbiattack.soundcoreswitch.protocol.SoundcoreUuids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Soundcore のマルチポイント制御アダプタ。
 *
 * 実機で解読したプロトコル（`docs/RESEARCH.md`）を使って、接続先一覧の取得と
 * 切り替えを行う。Android と Windows で共通に使える。
 *
 * 切り替えの手順（実機で確認した制約に基づく）:
 * 1. 現在の接続先一覧を取得する。
 * 2. 対象が既に接続中なら、何もせず成功として扱う。
 * 3. 接続上限（通常2台）に達している場合、**先に1台を切断する**。
 *    これを省くと接続コマンドが効かない（実機で確認済み）。
 * 4. 対象へ接続コマンドを送る。
 * 5. 一覧を再取得し、対象が接続中になったことを確認する。
 *
 * @param transport プラットフォームごとの RFCOMM 実装。
 * @param maxConnections 同時接続の上限。実測では 2。
 * @param timeoutMillis 応答を待つ時間。
 */
class SoundcoreMultipointAdapter(
    private val transport: SoundcoreTransport,
    private val maxConnections: Int = 2,
    private val timeoutMillis: Long = 4_000,
    /**
     * 切断コマンドを送ってから接続コマンドを送るまでの待ち時間。
     *
     * イヤホンは切断処理が終わるまで接続枠を空けない。待たずに接続を送ると無視される。
     * 実機（Liberty 4 NC）で調整した値。
     */
    private val disconnectSettleMillis: Long = 1_500,
    /** 接続コマンド送信後、最初に状態を確認するまでの待ち時間。 */
    private val confirmInitialDelayMillis: Long = 1_500,
    /** 確認が取れないときに再試行する間隔。 */
    private val confirmRetryIntervalMillis: Long = 1_500,
    /**
     * 操作元（この端末）の Bluetooth 名を返す。
     *
     * 接続上限に達したとき、この端末自身を切断しないために使う。
     * プロトコルは「どの接続先が操作元か」を返さないため、名前の照合に頼る。
     * 取得できなければ null を返してよい（その場合も結果は状態の再取得で確認する）。
     */
    private val localNameProvider: () -> String? = { null },
    /** 診断用のログ出力。既定は何もしない（`:core` は Android に依存しないため）。 */
    private val logger: (String) -> Unit = {},
) : MultipointAdapter {

    override val modelId: String = MODEL_ID

    override suspend fun readTargets(device: EarbudsDevice): TargetListResult {
        val modelCode = SoundcoreUuids.modelCodeOf(device.model.id)
            ?: return TargetListResult.Unavailable(FailureReason.UnsupportedModel)

        return try {
            val devices = fetchDevices(device.address, modelCode)
            if (devices == null) {
                TargetListResult.Unavailable(FailureReason.TargetListUnavailable)
            } else {
                TargetListResult.Available(devices.map { it.toTarget() })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SoundcoreTransportException) {
            TargetListResult.Unavailable(FailureReason.TargetListUnavailable)
        } catch (e: Exception) {
            TargetListResult.Unavailable(FailureReason.Unknown)
        }
    }

    override suspend fun switchTo(
        device: EarbudsDevice,
        target: MultipointTarget,
    ): SwitchResult {
        val modelCode = SoundcoreUuids.modelCodeOf(device.model.id)
            ?: return SwitchResult.Failed(FailureReason.UnsupportedModel)

        // 操作元（この端末）の Bluetooth 名。切断対象から除外するために使う。
        val localName = runCatching { localDeviceName() }.getOrNull()

        return try {
            val before = fetchDevices(device.address, modelCode)
                ?: return SwitchResult.Failed(FailureReason.TargetListUnavailable)

            val wanted = before.firstOrNull { it.mac.equals(target.id, ignoreCase = true) }
                ?: return SwitchResult.Failed(FailureReason.TargetNotFound)

            // 既に対象が接続中なら、切断して繋ぎ直す必要はない。
            if (wanted.connected) {
                return SwitchResult.Confirmed(wanted.toTarget())
            }

            // 接続上限に達しているなら、先に1台を切断する。
            // これをしないと接続コマンドが無視される（実機で確認済み）。
            val connected = before.filter { it.connected }
            if (connected.size >= maxConnections) {
                // この端末自身（＝操作元）は切らない。切ると通信が途切れて切り替えが成立しない。
                val victim = selectDisconnectCandidate(connected, localName)
                logger("切断対象=${victim.name} 操作元名=$localName 接続中=${connected.map { it.name }}")
                sendCommand(
                    mac = device.address,
                    modelCode = modelCode,
                    command = SoundcoreProtocol.Cmd.DISCONNECT_DEVICE,
                    body = SoundcoreProtocol.parseMac(victim.mac),
                )
                // イヤホンが切断を処理し終えるのを待つ。待たずに接続コマンドを送ると、
                // まだ枠が空いていないため無視される（実機で確認）。
                delay(disconnectSettleMillis)
            }

            sendCommand(
                mac = device.address,
                modelCode = modelCode,
                command = SoundcoreProtocol.Cmd.CONNECT_DEVICE,
                body = SoundcoreProtocol.parseMac(wanted.mac),
            )
            logger("接続コマンド送信 target=${wanted.name}")

            // 状態を再取得して、実際に接続中になったことを確認する。
            // イヤホンが接続を確立するまで時間がかかるため、反映されるまで数回試す。
            // 1回だけだと、まだ反映されていない瞬間を見て「失敗」と誤判定する。
            var confirmed: RawDevice? = null
            var lastList: List<RawDevice>? = null
            for (attempt in 0 until CONFIRM_ATTEMPTS) {
                delay(
                    if (attempt == 0) confirmInitialDelayMillis else confirmRetryIntervalMillis,
                )
                val current = fetchDevices(device.address, modelCode) ?: continue
                lastList = current
                confirmed = current.firstOrNull { it.mac.equals(wanted.mac, ignoreCase = true) }
                logger("確認${attempt + 1}回目 接続中=${current.filter { it.connected }.map { it.name }}")
                if (confirmed?.connected == true) break
            }

            if (lastList == null) return SwitchResult.Failed(FailureReason.SwitchUnconfirmed)
            val finalTarget = confirmed
                ?: return SwitchResult.Failed(FailureReason.TargetNotFound)

            if (finalTarget.connected) {
                SwitchResult.Confirmed(finalTarget.toTarget())
            } else {
                SwitchResult.Failed(FailureReason.SwitchUnconfirmed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SoundcoreTransportException) {
            logger("通信失敗: ${e.message}")
            SwitchResult.Failed(FailureReason.Timeout)
        } catch (e: Exception) {
            logger("想定外の失敗: ${e.javaClass.simpleName} ${e.message}")
            SwitchResult.Failed(FailureReason.Unknown)
        }
    }

    /**
     * 切断する端末を選ぶ。
     *
     * **操作元（この端末）を切ってはいけない。** 切ると通信が途切れ、
     * 続く接続コマンドが届かず切り替えが成立しない（実機で確認）。
     *
     * 操作元の判定は名前の照合で行う。プロトコルは「どの接続先が操作元か」を
     * 返さないため、これが唯一の手掛かりである。名前が一致しない場合は
     * 先頭を選ぶが、その場合でも切り替え結果は状態の再取得で確認するため、
     * 誤って成功と表示することはない。
     *
     * @param connected 現在接続中の端末。
     * @param localName 操作元の Bluetooth 名。取得できなければ null。
     */
    private fun selectDisconnectCandidate(
        connected: List<RawDevice>,
        localName: String?,
    ): RawDevice {
        if (localName != null) {
            val normalized = localName.trim().lowercase()
            val notLocal = connected.filter { it.name.trim().lowercase() != normalized }
            // 操作元以外が残っていれば、その中から選ぶ。
            if (notLocal.isNotEmpty()) return notLocal.first()
        }
        // 名前で判別できない場合は、名前から PC らしいものを優先して切る。
        return connected.firstOrNull { !it.isLikelyThisDevice() } ?: connected.first()
    }

    /** 操作元（この端末）の Bluetooth 名。 */
    private fun localDeviceName(): String? = localNameProvider()

    /**
     * 接続先一覧を取得する。分割パケットをすべて集める。
     *
     * @return 取得できなければ null。
     */
    private suspend fun fetchDevices(mac: String, modelCode: String): List<RawDevice>? {
        val packet = SoundcoreProtocol.buildPacket(SoundcoreProtocol.Cmd.GET_DEVICES)
        val frames = transport.exchange(
            mac = mac,
            modelCode = modelCode,
            packet = packet,
            expectedCommand = SoundcoreProtocol.Rsp.DUAL_DEVICES,
            timeoutMillis = timeoutMillis,
        )

        val chunks = frames
            .filter { SoundcoreProtocol.commandOf(it) == SoundcoreProtocol.Rsp.DUAL_DEVICES }
            .map { SoundcoreProtocol.decodeDeviceList(SoundcoreProtocol.bodyOf(it)) }

        if (chunks.isEmpty()) return null

        // パケット番号順に並べ、重複を除いて連結する。
        val total = chunks.first().total
        val ordered = chunks.sortedBy { it.index }
        val devices = ordered.flatMap { it.devices }

        // 全パケットが揃っていなくても、取れたぶんは返す。
        // ただし1件も取れていなければ失敗として扱う。
        if (devices.isEmpty() && total > 0) return null
        return devices
    }

    /** 応答を待たないコマンドを送る。 */
    private suspend fun sendCommand(
        mac: String,
        modelCode: String,
        command: ByteArray,
        body: ByteArray,
    ) {
        val packet = SoundcoreProtocol.buildPacket(command, body)
        val frames = transport.exchange(
            mac = mac,
            modelCode = modelCode,
            packet = packet,
            expectedCommand = null,
            timeoutMillis = timeoutMillis,
        )
        // 応答の内容を記録する。イヤホンが要求を拒否した場合、ここに理由が現れる。
        frames.forEach { frame ->
            val cmd = SoundcoreProtocol.commandOf(frame)
            val hex = SoundcoreProtocol.bodyOf(frame).joinToString(" ") {
                "%02X".format(it.toInt() and 0xFF)
            }
            logger("応答 cmd=%04X body=[%s]".format(cmd, hex))
        }
    }

    companion object {
        /** このアダプタが担当する機種 ID。 */
        const val MODEL_ID = "liberty-4-nc"

        /**
         * 接続後に状態を確認する回数。
         *
         * イヤホンが接続を確立するまで数秒かかるため、1回の確認では
         * 反映前の状態を見て「失敗」と誤判定し得る。
         */
        const val CONFIRM_ATTEMPTS = 4
    }
}

/** プロトコルの生データをドメインの接続先に変換する。 */
internal fun RawDevice.toTarget(): MultipointTarget = MultipointTarget(
    id = mac,
    displayName = name.ifBlank { mac },
    kind = guessKind(name),
    status = if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED,
    // この端末自身かどうかはプロトコルからは分からない。
    // 表示名の推定に留め、断定しない。
    isThisDevice = false,
)

/**
 * 表示名から端末種別を推定する。
 *
 * あくまで表示の補助であり、判定できなければ [TargetKind.UNKNOWN] にする。
 * 短い部分一致（"pc" など）は "MPC144" のような名前を誤判定するため、
 * 単語境界を意識した手掛かりだけを使う。
 */
internal fun guessKind(name: String): TargetKind {
    val lower = name.lowercase()
    return when {
        lower.contains("pad") || lower.contains("tab") -> TargetKind.TABLET
        lower.contains("desktop") || lower.contains("laptop") ||
            lower.contains("tower") || lower.contains("windows") -> TargetKind.PC
        lower.contains("phone") || lower.contains("reno") ||
            lower.contains("pixel") || lower.contains("galaxy") -> TargetKind.PHONE
        else -> TargetKind.UNKNOWN
    }
}

/**
 * この端末自身らしいか。
 *
 * プロトコルは「どの接続先が操作元か」を返さないため、名前から推定するしかない。
 * 誤判定を避けるため、切断対象の選択にだけ使い、UI には出さない。
 */
internal fun RawDevice.isLikelyThisDevice(): Boolean {
    val lower = name.lowercase()
    return lower.contains("desktop") || lower.contains("pc") || lower.contains("windows")
}
