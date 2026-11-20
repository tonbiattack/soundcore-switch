package com.tonbiattack.soundcoreswitch.domain.model

import com.tonbiattack.soundcoreswitch.protocol.SoundcoreUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 機種情報の整合性を検証する。
 *
 * 機種を追加するとき、次の3つを同時に更新しなければならない。
 * 1. `ModelCatalog.supportLevels`
 * 2. `DefaultMultipointAdapterRegistry` の登録
 * 3. `docs/SUPPORT_MATRIX.md`
 *
 * ここでは 1 と `ModelProfiles` の一致を機械的に検証する。
 * 手作業の更新漏れをテストで防ぐ。
 */
class ModelProfileConsistencyTest {

    @Test
    fun `ModelProfiles と ModelCatalog の対応レベルが一致する`() {
        ModelProfiles.ALL.forEach { profile ->
            assertEquals(
                "${profile.modelId} の対応レベルが ModelCatalog と一致しない",
                profile.supportLevel,
                ModelCatalog.supportLevelOf(profile.modelId),
            )
        }
    }

    @Test
    fun `ModelCatalog の既知機種はすべて ModelProfiles にある`() {
        ModelCatalog.KNOWN_MODELS.forEach { model ->
            assertNotNull(
                "${model.id} が ModelProfiles に無い",
                ModelProfiles.byModelId(model.id),
            )
        }
    }

    @Test
    fun `機種 ID が重複しない`() {
        val ids = ModelProfiles.ALL.map { it.modelId }

        assertEquals("機種 ID が重複している", ids.size, ids.toSet().size)
    }

    @Test
    fun `機種コードが重複しない`() {
        val codes = ModelProfiles.ALL.map { it.modelCode }

        assertEquals("機種コードが重複している", codes.size, codes.toSet().size)
    }

    @Test
    fun `機種コードは4桁の数字である`() {
        ModelProfiles.ALL.forEach { profile ->
            assertTrue(
                "${profile.modelId} の機種コードが4桁の数字でない: ${profile.modelCode}",
                profile.modelCode.matches(Regex("^\\d{4}$")),
            )
        }
    }

    @Test
    fun `ベンダー UUID が機種コードから組み立てられる`() {
        ModelProfiles.ALL.forEach { profile ->
            assertEquals(
                "${profile.modelId} の UUID が一致しない",
                SoundcoreUuids.VENDOR_PREFIX + profile.modelCode,
                profile.vendorUuid,
            )
        }
    }

    @Test
    fun `SoundcoreUuids が ModelProfiles から機種コードを引ける`() {
        ModelProfiles.ALL.forEach { profile ->
            assertEquals(
                "${profile.modelId} の機種コードが引けない",
                profile.modelCode,
                SoundcoreUuids.modelCodeOf(profile.modelId),
            )
        }
    }

    @Test
    fun `未登録の機種は機種コードを返さない`() {
        assertNull(SoundcoreUuids.modelCodeOf("unknown-model"))
        assertNull(ModelProfiles.byModelId("unknown-model"))
        assertNull(ModelProfiles.byModelCode("0000"))
    }

    @Test
    fun `SUPPORTED の機種は実機検証日を持つ`() {
        // 検証していない機種を SUPPORTED にすると
        // 「切り替えたつもりで切り替わっていない」最悪の誤動作になる。
        ModelProfiles.ALL
            .filter { it.supportLevel == SupportLevel.SUPPORTED }
            .forEach { profile ->
                assertTrue(
                    "${profile.modelId} は SUPPORTED だが検証日が無い",
                    profile.isVerified,
                )
            }
    }

    @Test
    fun `未検証の機種は切り替えを許可しない`() {
        ModelProfiles.ALL
            .filter { !it.isVerified }
            .forEach { profile ->
                assertFalse(
                    "${profile.modelId} は未検証だが切り替えを許可している",
                    profile.allowsSwitching,
                )
            }
    }

    @Test
    fun `表示名から機種を引ける`() {
        // Bluetooth の表示名は端末やファームウェアで揺れるため部分一致で判定する。
        assertEquals(
            ModelCatalog.LIBERTY_4_NC.id,
            ModelCatalog.matchByDisplayName("soundcore Liberty 4 NC")?.id,
        )
        assertEquals(
            ModelCatalog.SPACE_A40.id,
            ModelCatalog.matchByDisplayName("Soundcore Space A40")?.id,
        )
    }

    @Test
    fun `未知の表示名は機種を返さない`() {
        assertNull(ModelCatalog.matchByDisplayName("Some Other Headphones"))
    }
}
