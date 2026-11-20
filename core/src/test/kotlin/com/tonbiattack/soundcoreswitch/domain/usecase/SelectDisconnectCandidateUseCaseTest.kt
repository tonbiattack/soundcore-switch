package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.model.DisconnectBlockReason
import com.tonbiattack.soundcoreswitch.domain.model.ProtectionSettings
import com.tonbiattack.soundcoreswitch.testing.target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切断候補の選択を検証する。
 *
 * 最重要の性質は「誤って必要な端末を切らないこと」。
 * 切断してよいと確信できない場合は、候補を返さずに理由を返す。
 */
class SelectDisconnectCandidateUseCaseTest {

    private val useCase = SelectDisconnectCandidateUseCase()

    private val thisDevice = target(id = "7A:A7:6A:03:DD:08", displayName = "OPPO Reno13 A")
    private val pc = target(id = "90:FB:C5:9D:28:F4", displayName = "DELLTOWER")
    private val tablet = target(id = "8F:A5:32:05:AE:D0", displayName = "Redmi Pad SE 8.7")

    @Test
    fun `操作元を特定できていれば候補から除外する`() {
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(),
            thisDeviceId = thisDevice.id,
        )

        assertTrue(selection.hasCandidates)
        assertEquals(listOf(pc.id), selection.candidates.map { it.id })
    }

    @Test
    fun `保護された端末は候補にしない`() {
        val selection = useCase.select(
            connected = listOf(thisDevice, pc, tablet),
            protection = ProtectionSettings(protectedTargetIds = setOf(pc.id)),
            thisDeviceId = thisDevice.id,
        )

        assertTrue(selection.hasCandidates)
        assertEquals(listOf(tablet.id), selection.candidates.map { it.id })
    }

    @Test
    fun `すべて保護されていれば候補を返さない`() {
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(protectedTargetIds = setOf(thisDevice.id, pc.id)),
            thisDeviceId = thisDevice.id,
        )

        assertFalse(selection.hasCandidates)
        assertTrue(selection.blockReason is DisconnectBlockReason.AllProtected)
    }

    @Test
    fun `操作元しか接続されていなければ候補を返さない`() {
        val selection = useCase.select(
            connected = listOf(thisDevice),
            protection = ProtectionSettings(),
            thisDeviceId = thisDevice.id,
        )

        assertFalse(selection.hasCandidates)
        assertEquals(DisconnectBlockReason.OnlyThisDevice, selection.blockReason)
    }

    @Test
    fun `操作元を特定できない場合でも保護対象以外は候補になる`() {
        // Android 10 以降、アプリからローカル Bluetooth アドレスは取得できない。
        // 「特定できない＝中止」にすると Android では切り替えが一切できないため、
        // 保護対象以外を候補にする。操作元を切った場合は通信断で検出して中止する。
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(),
            thisDeviceId = null,
        )

        assertTrue(selection.hasCandidates)
        assertEquals(2, selection.candidates.size)
    }

    @Test
    fun `MAC が取れなくても名前で操作元を除外できる`() {
        // Android 10 以降はローカル MAC を取得できないため、名前で照合する。
        // これが実質的な操作元の特定手段になる。
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(),
            thisDeviceId = null,
            thisDeviceName = "OPPO Reno13 A",
        )

        assertTrue(selection.hasCandidates)
        assertEquals(listOf(pc.id), selection.candidates.map { it.id })
    }

    @Test
    fun `名前で照合するとき大文字小文字と前後の空白を無視する`() {
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(),
            thisDeviceId = null,
            thisDeviceName = "  oppo reno13 a  ",
        )

        assertEquals(listOf(pc.id), selection.candidates.map { it.id })
    }

    @Test
    fun `名前で照合して操作元しか残らなければ候補を返さない`() {
        val selection = useCase.select(
            connected = listOf(thisDevice),
            protection = ProtectionSettings(),
            thisDeviceId = null,
            thisDeviceName = "OPPO Reno13 A",
        )

        assertFalse(selection.hasCandidates)
        assertEquals(DisconnectBlockReason.OnlyThisDevice, selection.blockReason)
    }

    @Test
    fun `MAC が取れていれば名前より優先する`() {
        // 同名の端末があっても、MAC が分かっていれば正確に除外できる。
        val sameName = target(id = "AA:AA:AA:AA:AA:09", displayName = "OPPO Reno13 A")

        val selection = useCase.select(
            connected = listOf(thisDevice, sameName),
            protection = ProtectionSettings(),
            thisDeviceId = thisDevice.id,
            thisDeviceName = "OPPO Reno13 A",
        )

        // MAC で除外するため、同名の別端末は候補に残る。
        assertEquals(listOf(sameName.id), selection.candidates.map { it.id })
    }

    @Test
    fun `操作元を特定できず自動切断を禁止する設定なら候補を返さない`() {
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(neverDisconnectUnknown = true),
            thisDeviceId = null,
        )

        assertFalse(selection.hasCandidates)
        assertEquals(DisconnectBlockReason.CannotIdentifyThisDevice, selection.blockReason)
    }

    @Test
    fun `操作元を特定できなくても自動切断を許容する設定なら候補を返す`() {
        val selection = useCase.select(
            connected = listOf(thisDevice, pc),
            protection = ProtectionSettings(neverDisconnectUnknown = false),
            thisDeviceId = null,
        )

        assertTrue(selection.hasCandidates)
        assertEquals(2, selection.candidates.size)
    }

    @Test
    fun `接続中の端末が無ければ候補を返さない`() {
        val selection = useCase.select(
            connected = emptyList(),
            protection = ProtectionSettings(),
            thisDeviceId = thisDevice.id,
        )

        assertFalse(selection.hasCandidates)
        assertEquals(DisconnectBlockReason.NoConnectedTargets, selection.blockReason)
    }

    @Test
    fun `同一性は MAC で判定し、同名でも別端末として扱う`() {
        // 同名の端末が2台あっても、ID が違えば別物として扱う。
        val sameNameA = target(id = "AA:AA:AA:AA:AA:01", displayName = "soundcore Liberty 4 NC")
        val sameNameB = target(id = "AA:AA:AA:AA:AA:02", displayName = "soundcore Liberty 4 NC")

        val selection = useCase.select(
            connected = listOf(sameNameA, sameNameB),
            protection = ProtectionSettings(protectedTargetIds = setOf(sameNameA.id)),
            thisDeviceId = null,
        )

        // 保護された A だけが除外される。
        assertTrue(selection.hasCandidates)
        assertEquals(listOf(sameNameB.id), selection.candidates.map { it.id })
    }

    @Test
    fun `端末名が変わっても保護設定は ID で維持される`() {
        // 保護設定は ID で保持するため、表示名が変わっても有効。
        val renamed = target(id = pc.id, displayName = "DELLTOWER（改名後）")

        // 操作元と保護対象の2台だけが接続中。保護対象は切れず、操作元も切れない。
        val selection = useCase.select(
            connected = listOf(thisDevice, renamed),
            protection = ProtectionSettings(protectedTargetIds = setOf(pc.id)),
            thisDeviceId = thisDevice.id,
        )

        assertFalse(selection.hasCandidates)
        assertEquals(DisconnectBlockReason.OnlyThisDevice, selection.blockReason)
    }

    @Test
    fun `保護設定の追加と解除ができる`() {
        val settings = ProtectionSettings()
        assertFalse(settings.isProtected(pc.id))

        val protected = settings.protect(pc.id)
        assertTrue(protected.isProtected(pc.id))
        assertFalse(protected.allowsDisconnect(pc.id))

        val unprotected = protected.unprotect(pc.id)
        assertFalse(unprotected.isProtected(pc.id))
        assertTrue(unprotected.allowsDisconnect(pc.id))
    }
}
