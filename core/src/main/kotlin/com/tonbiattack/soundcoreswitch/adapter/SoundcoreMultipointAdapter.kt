package com.tonbiattack.soundcoreswitch.adapter

import com.tonbiattack.soundcoreswitch.domain.adapter.MultipointAdapter
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.DisconnectBlockReason
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.FailureReason
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import com.tonbiattack.soundcoreswitch.domain.model.SwitchResult
import com.tonbiattack.soundcoreswitch.domain.model.TargetKind
import com.tonbiattack.soundcoreswitch.domain.model.TargetListResult
import com.tonbiattack.soundcoreswitch.domain.usecase.SelectDisconnectCandidateUseCase
import com.tonbiattack.soundcoreswitch.protocol.DeviceListAssembler
import com.tonbiattack.soundcoreswitch.protocol.DeviceListSnapshot
import com.tonbiattack.soundcoreswitch.protocol.LogRedaction
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
    /** このアダプタが担当する機種 ID。既定は Liberty 4 NC。 */
    override val modelId: String = MODEL_ID,
    private val maxConnections: Int = 2,
    private val timeoutMillis: Long = 4_000,
    /**
     * 切断コマンドを送ってから接続コマンドを送るまでの待ち時間。
     *
     * イヤホンは切断処理が終わるまで接続枠を空けない。待たずに接続を送ると無視される。
     * 実機（Liberty 4 NC）で調整した値。
     */
    private val disconnectSettleMillis: Long = 1_500,
    /**
     * 切断が反映されないときに再確認する間隔。
     *
     * イヤホンが切断処理を終えて一覧に反映するまで時間がかかる。
     * 1回の確認で諦めると、実際には切れているのに「切断できなかった」と
     * 誤判定して切り替えが不安定になる。
     */
    private val disconnectRetryIntervalMillis: Long = 1_000,
    /** 接続コマンド送信後、最初に状態を確認するまでの待ち時間。 */
    private val confirmInitialDelayMillis: Long = 1_500,
    /** 確認が取れないときに再試行する間隔。 */
    private val confirmRetryIntervalMillis: Long = 1_500,
    /**
     * 操作元（この端末）の接続先 ID（MAC）を返す。
     *
     * 接続上限に達したとき、この端末自身を切断しないために使う。
     * **名前ではなく ID で判定する**（端末名は変更でき、同名もあり得るため）。
     * 特定できなければ null。その場合の挙動は [ProtectionSettings] に従う。
     */
    private val thisDeviceIdProvider: () -> String? = { null },
    /**
     * 操作元（この端末）の Bluetooth 名を返す。
     *
     * [thisDeviceIdProvider] が null を返したときの代替。
     * Android 10 以降はローカル MAC を取得できないため、名前で照合するしかない。
     * 名前も取れなければ null。
     */
    private val thisDeviceNameProvider: () -> String? = { null },
    /** 保護設定の供給元。切断してよい端末を制限する。 */
    private val protectionProvider: () -> ProtectionSettings = { ProtectionSettings() },
    /** 診断用のログ出力。既定は何もしない（`:core` は Android に依存しないため）。 */
    private val logger: (String) -> Unit = {},
) : MultipointAdapter {

    private val selectCandidate = SelectDisconnectCandidateUseCase()

    override suspend fun readTargets(device: EarbudsDevice): TargetListResult {
        val modelCode = SoundcoreUuids.modelCodeOf(device.model.id)
            ?: return TargetListResult.Unavailable(FailureReason.UnsupportedModel)

        return try {
            val snapshot = fetchDevices(device.address, modelCode)
            when {
                snapshot == null ->
                    TargetListResult.Unavailable(FailureReason.TargetListUnavailable)
                // 部分取得は「取得不可」として扱う。不完全な一覧を
                // 「接続先がこれだけ」と表示すると、存在する端末を隠してしまう。
                !snapshot.isComplete ->
                    TargetListResult.Unavailable(FailureReason.TargetListIncomplete)
                else -> TargetListResult.Available(snapshot.devices.map { it.toTarget() })
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

        // 操作元（この端末）の接続先 ID。切断対象から除外するために使う。
        // 名前ではなく ID（MAC）で判定する。端末名は変更でき、同名もあり得るため。
        val thisDeviceId = runCatching { thisDeviceIdProvider() }.getOrNull()
        // MAC が取れない場合（Android 10 以降）は名前で照合する。
        val thisDeviceName = if (thisDeviceId == null) {
            runCatching { thisDeviceNameProvider() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val protection = runCatching { protectionProvider() }.getOrDefault(ProtectionSettings())

        return try {
            // 切り替えの判断には完全な一覧だけを使う。
            // 部分取得で判断すると、存在する端末を「無い」と誤認して
            // 誤った切断対象を選ぶ恐れがある。
            val before = fetchCompleteDevices(device.address, modelCode)
                ?: return SwitchResult.Failed(FailureReason.TargetListIncomplete)

            val wanted = before.firstOrNull { it.mac.equals(target.id, ignoreCase = true) }
                ?: return SwitchResult.Failed(FailureReason.TargetNotFound)

            // 既に対象が接続中なら、切断して繋ぎ直す必要はない。
            if (wanted.connected) {
                return SwitchResult.Confirmed(wanted.toTarget())
            }

            // 接続上限に達しているなら、先に1台を切断する。
            // これをしないと接続コマンドが無視される（実機で確認済み）。
            val connected = before.filter { it.connected }.map { it.toTarget() }
            if (connected.size >= maxConnections) {
                val selection = selectCandidate.select(
                    connected,
                    protection,
                    thisDeviceId,
                    thisDeviceName,
                )
                logger(
                    "切断候補=${selection.candidates.map { LogRedaction.deviceId(it.id) }} " +
                        "操作元=${LogRedaction.deviceId(thisDeviceId)} " +
                        "操作元名=${LogRedaction.deviceName(thisDeviceName)} " +
                        "保護=${protection.protectedTargetIds.size}件",
                )

                if (!selection.hasCandidates) {
                    // 安全に切断できる端末が無い。誤切断を避けて中止する。
                    val reason = selection.blockReason ?: DisconnectBlockReason.NoConnectedTargets
                    logger("切断候補を選べないため中止する: $reason")
                    return SwitchResult.Failed(FailureReason.CannotFreeConnectionSlot(reason))
                }

                // 切断は対象によって拒否されることがある（実機で確認）。
                // 拒否された場合は次の候補を試す。1台も切れなければ失敗として返す。
                var freed = false
                for (victim in selection.candidates) {
                    sendCommand(
                        mac = device.address,
                        modelCode = modelCode,
                        command = SoundcoreProtocol.Cmd.DISCONNECT_DEVICE,
                        body = SoundcoreProtocol.parseMac(victim.id),
                    )

                    // 実際に切断されたかを一覧の再取得で確認する。
                    // 応答の有無では判定できない（応答なしで成功する場合がある）。
                    //
                    // **1回の確認では足りない。** イヤホンが切断処理を終えて一覧に
                    // 反映するまで時間がかかるため、1回だけ見て「切断できなかった」と
                    // 判定すると、実際には切れているのに次の候補へ進んでしまう
                    // （実機で切り替えが不安定になる原因）。
                    val outcome = awaitDisconnected(device.address, modelCode, victim.id)
                    when (outcome) {
                        DisconnectOutcome.DISCONNECTED -> {
                            freed = true
                            break
                        }
                        // 切断後に一覧が取れない＝通信が途切れた。
                        // 操作元（この端末）を切ってしまった可能性が高い。
                        // これ以上進むと何もできなくなるため中止する。
                        DisconnectOutcome.LOST_CONNECTION -> {
                            logger("切断後に通信が途切れた。操作元を切った可能性があるため中止する")
                            return SwitchResult.Failed(
                                FailureReason.CannotFreeConnectionSlot(
                                    DisconnectBlockReason.CutThisDevice,
                                ),
                            )
                        }
                        DisconnectOutcome.STILL_CONNECTED -> Unit
                    }
                }

                if (!freed) {
                    // 1台も切断できなかった。上限が空かないため接続しても無駄になる。
                    logger("切断できた端末が無いため中止する")
                    return SwitchResult.Failed(FailureReason.SwitchUnconfirmed)
                }
            }

            sendCommand(
                mac = device.address,
                modelCode = modelCode,
                command = SoundcoreProtocol.Cmd.CONNECT_DEVICE,
                body = SoundcoreProtocol.parseMac(wanted.mac),
            )
            logger("接続コマンド送信 target=${LogRedaction.deviceId(wanted.mac)}")

            // 状態を再取得して、実際に接続中になったことを確認する。
            // イヤホンが接続を確立するまで時間がかかるため、反映されるまで数回試す。
            // 1回だけだと、まだ反映されていない瞬間を見て「失敗」と誤判定する。
            var confirmed: RawDevice? = null
            var lastList: List<RawDevice>? = null
            for (attempt in 0 until CONFIRM_ATTEMPTS) {
                delay(
                    if (attempt == 0) confirmInitialDelayMillis else confirmRetryIntervalMillis,
                )
                val current = fetchCompleteDevices(device.address, modelCode) ?: continue
                lastList = current
                confirmed = current.firstOrNull { it.mac.equals(wanted.mac, ignoreCase = true) }
                logger("確認${attempt + 1}回目 ${LogRedaction.deviceList(current)}")
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
            logger("通信失敗: ${LogRedaction.error(e)}")
            SwitchResult.Failed(FailureReason.Timeout)
        } catch (e: Exception) {
            logger("想定外の失敗: ${LogRedaction.error(e)}")
            SwitchResult.Failed(FailureReason.Unknown)
        }
    }

    /**
     * 接続先一覧を取得する。分割パケットをすべて集める。
     *
     * @return 取得できなければ null。
     */
    /**
     * 接続先一覧を取得する。分割パケットをすべて集めて組み立てる。
     *
     * **完全に取得できたかを区別する。** 部分取得を「一覧のすべて」として扱うと、
     * 実際には接続中の端末を「切断中」と誤認して自動切断の対象にしたり、
     * 接続成功の判定を誤ったりする。
     *
     * @return 取得できなければ null。部分取得の場合は [DeviceListSnapshot.isComplete] が false。
     */
    private suspend fun fetchDevices(mac: String, modelCode: String): DeviceListSnapshot? {
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

        val snapshot = DeviceListAssembler.assemble(chunks)
        if (!snapshot.isComplete) {
            logger(
                "接続先一覧が不完全: ${snapshot.receivedCount}/${snapshot.expectedTotal} パケット",
            )
        }
        // 1件も取れていない場合は失敗として扱う（空の一覧と区別する）。
        if (snapshot.devices.isEmpty() && snapshot.expectedTotal > 0) return null
        return snapshot
    }

    /**
     * 完全に取得できた一覧だけを返す。
     *
     * 切り替えの判断（切断対象の選択・接続成功の判定）に使う。
     * 不完全な一覧では判断しない。
     */
    private suspend fun fetchCompleteDevices(
        mac: String,
        modelCode: String,
    ): List<RawDevice>? = fetchDevices(mac, modelCode)?.completeOrNull()

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
        // body には MAC・端末名が含まれるため、長さだけを残す。
        frames.forEach { frame ->
            val cmd = SoundcoreProtocol.commandOf(frame)
            logger("応答 cmd=%04X %s".format(cmd, LogRedaction.body(SoundcoreProtocol.bodyOf(frame))))
        }
    }

    /**
     * 指定した接続先が実際に切断されるまで待つ。
     *
     * イヤホンは切断要求を受けてから一覧に反映するまで時間がかかる。
     * 1回だけ確認すると、まだ反映されていない瞬間を見て「切断できなかった」と
     * 誤判定し、無駄に次の候補へ進んでしまう（切り替えが不安定になる原因）。
     *
     * **通信が途切れた場合を区別する。** 操作元（この端末）を切ってしまうと
     * 以降の一覧取得がすべて失敗する。これを「まだ接続中」と扱って次の候補へ
     * 進むと、通信できないまま無駄に処理を続けることになる。
     *
     * @return 確認結果。
     */
    private suspend fun awaitDisconnected(
        mac: String,
        modelCode: String,
        targetId: String,
    ): DisconnectOutcome {
        var consecutiveFailures = 0
        for (attempt in 0 until DISCONNECT_CONFIRM_ATTEMPTS) {
            delay(
                if (attempt == 0) disconnectSettleMillis else disconnectRetryIntervalMillis,
            )
            // 完全な一覧でしか切断を確認しない。部分取得では
            // 「一覧に無い＝切断された」と誤認する恐れがある。
            val current = fetchCompleteDevices(mac, modelCode)
            if (current == null) {
                consecutiveFailures++
                logger("切断確認${attempt + 1}回目: 一覧を取得できない (${consecutiveFailures}回連続)")
                // 連続して取得できないなら、通信が途切れたと判断する。
                if (consecutiveFailures >= LOST_CONNECTION_THRESHOLD) {
                    return DisconnectOutcome.LOST_CONNECTION
                }
                continue
            }
            consecutiveFailures = 0
            val stillConnected = current
                .firstOrNull { it.mac.equals(targetId, ignoreCase = true) }
                ?.connected
            logger("切断確認${attempt + 1}回目: まだ接続中=$stillConnected")
            if (stillConnected == false) return DisconnectOutcome.DISCONNECTED
        }
        return DisconnectOutcome.STILL_CONNECTED
    }

    /** 切断確認の結果。 */
    private enum class DisconnectOutcome {
        /** 切断を確認できた。 */
        DISCONNECTED,

        /** まだ接続中のまま。別の候補を試す。 */
        STILL_CONNECTED,

        /** 一覧を取得できなくなった。操作元を切った可能性がある。 */
        LOST_CONNECTION,
    }

    companion object {
        /** Liberty 4 NC (A3947) の機種 ID。 */
        const val MODEL_ID = "liberty-4-nc"

        /** Space A40 (A3936) の機種 ID。 */
        const val SPACE_A40_MODEL_ID = "space-a40"

        /**
         * 接続後に状態を確認する回数。
         *
         * イヤホンが接続を確立するまで数秒かかるため、1回の確認では
         * 反映前の状態を見て「失敗」と誤判定し得る。
         *
         * 待ち時間は 1.5 秒 + 1.5 秒 × 5 = 最大 9 秒。
         * 実機（Space A40）では接続の反映に 5 秒近くかかることがあり、
         * 4回では足りずに失敗と誤判定していた。
         */
        const val CONFIRM_ATTEMPTS = 6

        /**
         * 切断を確認する回数。
         *
         * 切断の反映には時間がかかる。1回で諦めると誤判定するため複数回見る。
         * 待ち時間は 1.5 秒 + 1.0 秒 × 3 = 最大 4.5 秒。
         */
        const val DISCONNECT_CONFIRM_ATTEMPTS = 4

        /**
         * 一覧の取得が連続で失敗したら「通信が途切れた」と判断する回数。
         *
         * 操作元（この端末）を切ると、以降の一覧取得がすべて失敗する。
         * 1回の失敗では一時的な問題と区別できないため、連続を条件にする。
         */
        const val LOST_CONNECTION_THRESHOLD = 2
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
 * 短い部分一致（"pc" など）は "LAPTOP-B" のような名前を誤判定するため、
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
