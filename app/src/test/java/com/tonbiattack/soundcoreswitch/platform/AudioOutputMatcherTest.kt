package com.tonbiattack.soundcoreswitch.platform

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsModel
import com.tonbiattack.soundcoreswitch.domain.model.SupportLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputMatcherTest {
    private fun device(address: String, name: String) = EarbudsDevice(
        address = address,
        displayName = name,
        model = EarbudsModel("test", name),
        supportLevel = SupportLevel.SUPPORTED,
        connection = ConnectionStatus.UNKNOWN,
    )

    private val first = device("AA:AA:AA:AA:AA:01", "同名イヤホン")
    private val second = device("AA:AA:AA:AA:AA:02", "同名イヤホン")

    @Test
    fun `同名でもアドレスが一致する一台だけを接続中にする`() {
        val result = markConnectedAudioOutputs(
            listOf(first, second), listOf(AudioOutputIdentity(second.address.lowercase(), second.displayName)),
        )
        assertEquals(ConnectionStatus.UNKNOWN, result[0].connection)
        assertEquals(ConnectionStatus.CONNECTED, result[1].connection)
    }

    @Test
    fun `アドレスが使えず同名が複数なら不明のままにする`() {
        val result = markConnectedAudioOutputs(
            listOf(first, second), listOf(AudioOutputIdentity(null, first.displayName)),
        )
        assertEquals(listOf(ConnectionStatus.UNKNOWN, ConnectionStatus.UNKNOWN), result.map { it.connection })
    }

    @Test
    fun `アドレスが使えず名前が一意なら接続中にする`() {
        val result = markConnectedAudioOutputs(
            listOf(first), listOf(AudioOutputIdentity("02:00:00:00:00:00", " 同名イヤホン ")),
        )
        assertEquals(ConnectionStatus.CONNECTED, result.single().connection)
    }

    @Test
    fun `異なる有効アドレスなら同名でも接続中にしない`() {
        val result = markConnectedAudioOutputs(
            listOf(first), listOf(AudioOutputIdentity(second.address, first.displayName)),
        )
        assertEquals(ConnectionStatus.UNKNOWN, result.single().connection)
    }

    @Test
    fun `音声出力に見つからなくても切断とは断定しない`() {
        assertEquals(listOf(first), markConnectedAudioOutputs(listOf(first), emptyList()))
    }
}
