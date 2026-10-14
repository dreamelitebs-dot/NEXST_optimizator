package ru.booster.network.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AmneziaWG = WireGuard + DPI-circumvention obfuscation.
 * Extra handshake parameters (mirror of Amnezia VPN control panel):
 *
 *  Jc   – number of junk packets inserted before the real WG Initiation
 *  Jmin – minimum junk packet size (bytes)
 *  Jmax – maximum junk packet size (bytes)
 *  S1   – first 24 bits of every junk packet (hex, e.g. "deadff")
 *  S2   – remaining 8 bits of every junk packet  (hex, e.g. "ee")
 *
 * A random-size junk prefix makes the WG handshake look like entropy noise
 * to deep-packet-inspection middleboxes (RSDPI / TSPU style).
 */
@Serializable
data class AmneziaWgParams(
    @SerialName("Jc") val junkPacketCount: Int = 3,
    @SerialName("Jmin") val junkPacketMinSize: Int = 57,
    @SerialName("Jmax") val junkPacketMaxSize: Int = 1270,
    @SerialName("S1") val junkPacketHeader1: String = "deadff",
    @SerialName("S2") val junkPacketHeader2: String = "ee"
) {
    fun validate(): Result<Unit> = runCatching {
        require(junkPacketCount in 0..9) { "Jc must be in 0..9" }
        require(junkPacketMinSize in 57..1270) { "Jmin must be in 57..1270" }
        require(junkPacketMaxSize in junkPacketMinSize..1460) { "Jmax must be >= Jmin and <= 1460" }
        require(junkPacketHeader1.matches(Regex("^[0-9a-fA-F]{6}$"))) { "S1 must be 3 hex bytes" }
        require(junkPacketHeader2.matches(Regex("^[0-9a-fA-F]{2}$"))) { "S2 must be 1 hex byte" }
    }

    companion object {
        /** Amnezia-recommended defaults for hostile networks. */
        val DEFAULT = AmneziaWgParams()
    }
}

/** Full tunnel configuration parsed from .conf / JSON / wireguard:// URI. */
@Serializable
data class TunnelConfig(
    val name: String = "AmneziaWG Tunnel",
    // [Interface]
    val privateKey: String = "",
    val address: String = "10.8.0.2/32",
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val mtu: Int = 0,                       // 0 → auto-negotiate smallest working MTU
    // [Peer]
    val publicKey: String = "",
    val presharedKey: String = "",          // optional PSK
    val endpointHost: String = "",
    val endpointPort: Int = 443,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val persistentKeepalive: Int = 25,
    // AmneziaWG obfuscation block
    val wgParams: AmneziaWgParams = AmneziaWgParams.DEFAULT,
    // Split tunneling: when true — ONLY listed game packages go through tunnel
    val splitTunnelEnabled: Boolean = true,
    val includedPackages: List<String> = emptyList(),
    val isAmneziaWg: Boolean = true
) {
    val isValid: Boolean
        get() = privateKey.isNotBlank() && publicKey.isNotBlank() &&
                endpointHost.isNotBlank() && endpointPort in 1..65535
}

/** Boost tuning knobs applied to sockets & buffers while a session runs. */
@Serializable
data class NetworkBoostProfile(
    val autoMtu: Boolean = true,
    val tcpSendBufferBytes: Int = 262_144,     // 256 KiB
    val tcpReceiveBufferBytes: Int = 524_288,  // 512 KiB
    val udpSendBufferBytes: Int = 416_000,
    val udpReceiveBufferBytes: Int = 416_000,
    val lowLatencyFlag: Boolean = true,        // TCP_NODELAY on every socket
    val trafficClassDscp: Int = 0x2E           // DSCP EF (expedite forwarding) << 2
)
