package com.tonbiattack.soundcoreswitch.platform

import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice

internal data class AudioOutputIdentity(val address: String?, val name: String?)

/** 音声出力の確かな一致だけを接続中にする。複数の同名端末は推測しない。 */
internal fun markConnectedAudioOutputs(
    devices: List<EarbudsDevice>,
    outputs: List<AudioOutputIdentity>,
): List<EarbudsDevice> {
    val connectedAddresses = outputs.mapNotNull { output ->
        val address = output.address?.trim().orEmpty()
        val validAddress = address.matches(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) &&
            address != "02:00:00:00:00:00" && address != "00:00:00:00:00:00"
        if (validAddress) {
            // アドレスがある場合、名前が同じ別の機器に誤って一致させない。
            devices.singleOrNull { it.address.equals(address, ignoreCase = true) }?.address
        } else {
            val name = output.name?.trim().orEmpty()
            if (name.isEmpty()) null else devices.filter {
                it.displayName.trim().equals(name, ignoreCase = true)
            }.singleOrNull()?.address
        }
    }.toSet()
    return devices.map { device ->
        if (device.address in connectedAddresses) device.copy(connection = ConnectionStatus.CONNECTED)
        else device
    }
}
