package ru.booster.network.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AmneziaWgParams(
    @SerialName("Jc") val junkPacketCount: Int = 4,
    @SerialName("Jmin") val junkPacketMinSize: Int = 10,
    @SerialName("Jmax") val junkPacketMaxSize: Int = 50,
    @SerialName("S1") val junkPacketHeader1: String = "12",
    @SerialName("S2") val junkPacketHeader2: String = "12",
    @SerialName("S3") val junkPacketHeader3: String = "12",
    @SerialName("S4") val junkPacketHeader4: String = "12",
    @SerialName("H1") val h1: Int = 1,
    @SerialName("H2") val h2: Int = 2,
    @SerialName("H3") val h3: Int = 3,
    @SerialName("H4") val h4: Int = 4
) {
    fun validate(): Result<Unit> = runCatching {
        require(junkPacketCount in 0..20) { "Jc range error" }
    }

    companion object {
        val DEFAULT = AmneziaWgParams()
    }
}

@Serializable
data class TunnelConfig(
    val name: String = "AmneziaWG Custom Tunnel",
    // [Interface]
    val privateKey: String = "hCBDYAypC7CVUqBxF4hiDPxAtHMDhdzIXVZE4DOxHLc=",
    val address: String = "10.8.1.7/32",
    val dnsServers: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val mtu: Int = 0,
    
    // [Peer]
    val publicKey: String = "6Uot9tRDh+U1di8EQD3/yi15hvGR1EvXsD+Ah98mzmk=",
    val presharedKey: String = "Vlvr39KP8kC2kOzhaCUTwj9+l87LUGzU93VmbGXWaiY=",
    val endpointHost: String = "176.124.204.40",
    val endpointPort: Int = 39597,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val persistentKeepalive: Int = 25,
    
    // AmneziaWG obfuscation block
    val wgParams: AmneziaWgParams = AmneziaWgParams(),
    
    // Split tunneling
    val splitTunnelEnabled: Boolean = true,
    val includedPackages: List<String> = emptyList(),
    val isAmneziaWg: Boolean = true
) {
    val isValid: Boolean
        get() = privateKey.isNotBlank() && publicKey.isNotBlank() &&
                endpointHost.isNotBlank() && endpointPort in 1..65535
}

@Serializable
data class NetworkBoostProfile(
    val autoMtu: Boolean = true,
    val tcpSendBufferBytes: Int = 262_144,
    val tcpReceiveBufferBytes: Int = 524_288,
    val udpSendBufferBytes: Int = 416_000,
    val udpReceiveBufferBytes: Int = 416_000,
    val lowLatencyFlag: Boolean = true,
    val trafficClassDscp: Int = 0x2E
)
