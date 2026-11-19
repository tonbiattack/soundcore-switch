package com.tonbiattack.soundcoreswitch.domain.usecase

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.testing.target
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接続先の並べ替えを検証する。
 *
 * 優先度は「接続中 → お気に入り → その他」。
 * 同じ優先度の中では元の順序を保つ（毎回順序が変わると探しにくいため）。
 */
class OrderTargetsUseCaseTest {

    private val useCase = OrderTargetsUseCase()

    private val a = target(id = "AA:AA:AA:AA:AA:01", displayName = "端末A")
    private val b = target(id = "AA:AA:AA:AA:AA:02", displayName = "端末B")
    private val c = target(id = "AA:AA:AA:AA:AA:03", displayName = "端末C")

    private fun connected(id: String, name: String) =
        target(id = id, displayName = name, status = ConnectionStatus.CONNECTED)

    @Test
    fun `接続中が無ければ元の順序を保つ`() {
        val result = useCase.order(listOf(a, b, c), emptySet())

        assertEquals(listOf(a, b, c), result)
    }

    @Test
    fun `接続中の端末を先頭に出す`() {
        val connectedC = connected(c.id, "端末C")

        val result = useCase.order(listOf(a, b, connectedC), emptySet())

        assertEquals(listOf(connectedC, a, b), result)
    }

    @Test
    fun `接続中が複数あれば元の順序を保つ`() {
        // マルチポイントの上限は2台。どちらを先にしても実害はないが、
        // 毎回入れ替わると押し間違えるため順序を固定する。
        val connectedA = connected(a.id, "端末A")
        val connectedC = connected(c.id, "端末C")

        val result = useCase.order(listOf(connectedA, b, connectedC), emptySet())

        assertEquals(listOf(connectedA, connectedC, b), result)
    }

    @Test
    fun `接続中はお気に入りより優先する`() {
        // お気に入りでも接続中でなければ後ろ。接続中を最初に見せたい。
        val connectedA = connected(a.id, "端末A")

        val result = useCase.order(listOf(connectedA, b, c), setOf(c.id))

        assertEquals(listOf(connectedA, c, b), result)
    }

    @Test
    fun `接続中が無ければお気に入りを先頭に出す`() {
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
    fun `接続中かつお気に入りでも先頭に出す`() {
        val connectedB = connected(b.id, "端末B")

        val result = useCase.order(listOf(a, connectedB, c), setOf(b.id))

        assertEquals(listOf(connectedB, a, c), result)
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
