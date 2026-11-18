package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.testing.target
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接続先の並べ替えを検証する。
 *
 * お気に入りを先頭に出しつつ、それ以外の順序は変えない（安定した並び）。
 * 毎回順序が変わると目的の端末を探しにくくなるため。
 */
class OrderTargetsUseCaseTest {

    private val useCase = OrderTargetsUseCase()

    private val a = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val b = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")
    private val c = target(id = "AA:AA:AA:AA:AA:03", displayName = "端末C")

    @Test
    fun `お気に入りが無ければ元の順序を保つ`() {
        val result = useCase.order(listOf(a, b, c), emptySet())

        assertEquals(listOf(a, b, c), result)
    }

    @Test
    fun `お気に入りを先頭に出す`() {
        val result = useCase.order(listOf(a, b, c), setOf(c.id))

        assertEquals(listOf(c, a, b), result)
    }

    @Test
    fun `お気に入り同士は元の順序を保つ`() {
        val result = useCase.order(listOf(a, b, c), setOf(c.id, a.id))

        assertEquals(listOf(a, c, b), result)
    }

    @Test
    fun `通常の端末は元の順序を保つ`() {
        val result = useCase.order(listOf(a, b, c), setOf(b.id))

        assertEquals(listOf(b, a, c), result)
    }

    @Test
    fun `存在しない ID がお気に入りにあっても壊れない`() {
        // 端末が登録から外れた場合など。お気に入り設定は残るが一覧には無い。
        val result = useCase.order(listOf(a, b), setOf("ZZ:ZZ:ZZ:ZZ:ZZ:ZZ"))

        assertEquals(listOf(a, b), result)
    }

    @Test
    fun `空の一覧でも壊れない`() {
        val result = useCase.order(emptyList(), setOf(a.id))

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `1件でも壊れない`() {
        val result = useCase.order(listOf(a), setOf(a.id))

        assertEquals(listOf(a), result)
    }

    @Test
    fun `同名の端末でも ID で区別して並べ替える`() {
        // 表示名が同じでも、お気に入りは ID で判定する。
        val sameName1 = target(id = "AA:AA:AA:AA:AA:01", displayName = "soundcore Liberty 4 NC")
        val sameName2 = target(id = "AA:AA:AA:AA:AA:02", displayName = "soundcore Liberty 4 NC")

        val result = useCase.order(listOf(sameName1, sameName2), setOf(sameName2.id))

        assertEquals(listOf(sameName2, sameName1), result)
    }
}
