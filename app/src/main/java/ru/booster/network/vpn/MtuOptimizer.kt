package ru.booster.network.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import ru.booster.network.core.model.TunnelConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * MTU auto-negotiation & TCP/UDP buffer tuning.
 *
 * Strategy for the "smallest working MTU":
 *   1. Read the underlay interface MTU from NetworkCapabilities.
 *   2. Subtract the encapsulation overhead:
 *        IPv4 + UDP + WireGuard header ≈ 28 + 8 + 32 = 68 bytes
 *        IPv6 variant                 ≈ 48 + 8 + 32 = 88 bytes
 *   3. For AmneziaWG, junk packets are only prepended to the handshake,
 *      they never enlarge data-plane frames — but Jmax must stay ≤ path MTU,
 *      so we clamp it as well.
 *   4. Probe with DF-bit sized UDP datagrams; on failure step down by 4
 *      (57 → WG minimum) until a probe round-trips.
 */
@Singleton
class MtuOptimizer @Inject constructor(
    private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun underlayMtu(): Int {
        val network = connectivityManager.activeNetwork ?: return DEFAULT_MTU
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return DEFAULT_MTU
        // LinkProperties carries the concrete interface MTU when available.
        val linkProps: LinkProperties? = connectivityManager.getLinkProperties(network)
        return linkProps?.mtu?.takeIf { it in MIN_WG_MTU..JUMBO_MTU }
            ?: caps.mtu.takeIf { it in MIN_WG_MTU..JUMBO_MTU }
            ?: DEFAULT_MTU
    }

    /** Compute tunnel MTU: smallest value guaranteed not to fragment. */
    fun negotiateTunnelMtu(config: TunnelConfig): Int {
        if (config.mtu > 0) return config.mtu.coerceIn(MIN_WG_MTU, MAX_TUNNEL_MTU)

        val underlay = underlayMtu()
        val isIpv6Endpoint = config.endpointHost.contains(':') // literal v6 address
        val overhead = if (isIpv6Endpoint) IPV6_OVERHEAD else IPV4_OVERHEAD
        var mtu = min(underlay - overhead, MAX_TUNNEL_MTU)

        // Clamp AmneziaWG junk bounds below negotiated tunnel MTU
        val clampedJmax = min(config.wgParams.junkPacketMaxSize, mtu)
        if (clampedJmax < config.wgParams.junkPacketMinSize) {
            // pathological config — shrink min too
            mtu = max(mtu, MIN_WG_MTU)
        }
        return max(mtu, MIN_WG_MTU)
    }

    /**
     * Probes candidate MTUs with a DF-sized UDP packet against the endpoint.
     * Returns the first size that round-trips, stepping down from [start].
     */
    suspend fun probeMtu(endpointHost: String, port: Int, start: Int): Int {
        var candidate = start.coerceIn(MIN_WG_MTU, MAX_TUNNEL_MTU)
        while (candidate >= MIN_WG_MTU) {
            val ok = UdpDfProbe.probe(host = endpointHost, port = port, payloadBytes = candidate - UDP_HEADER)
            if (ok) return candidate
            candidate -= MTU_PROBE_STEP
        }
        return MIN_WG_MTU // safe floor: WG spec minimum
    }

    companion object {
        const val DEFAULT_MTU = 1500
        const val JUMBO_MTU = 9000
        const val MAX_TUNNEL_MTU = 1420
        const val MIN_WG_MTU = 57          // per WireGuard protocol spec
        const val MTU_PROBE_STEP = 4
        private const val IPV4_OVERHEAD = 68   // IP28 + UDP8 + WG32
        private const val IPV6_OVERHEAD = 88   // IP48 + UDP8 + WG32
        private const val UDP_HEADER = 8 + 20  // UDP + IPv4
    }
}

/** Recommended socket buffer sizes scaled to measured RTT (BDP heuristic). */
object BufferTuner {

    /**
     * Bandwidth-delay product sizing:
     *   buffer ≥ BDP = bandwidth(bps) × RTT(s), floored for lossy mobile links.
     * Game traffic is latency-bound, so we cap buffers at ~2×BDP to avoid
     * bufferbloat while keeping the pipe full for bursty UDP streams.
     */
    fun suggestBuffers(bandwidthMbps: Double, rttMs: Int): SocketBuffers {
        val bdpBytes = ((bandwidthMbps * 1_000_000.0 / 8.0) * (rttMs.coerceAtLeast(1) / 1000.0))
        val target = bdpBytes.toLong().coerceIn(MIN_BUFFER.toLong(), MAX_BUFFER.toLong())
        val gameStream = (target / 2).coerceAtLeast(GAME_MIN.toLong()).toInt()
        return SocketBuffers(
            tcpSend = target.toInt(),
            tcpReceive = (target * 2).coerceAtMost(MAX_BUFFER.toLong()).toInt(),
            udpSend = gameStream,
            udpReceive = gameStream * 2
        )
    }

    data class SocketBuffers(
        val tcpSend: Int,
        val tcpReceive: Int,
        val udpSend: Int,
        val udpReceive: Int
    )

    private const val MIN_BUFFER = 64 * 1024
    private const val MAX_BUFFER = 4 * 1024 * 1024
    private const val GAME_MIN = 128 * 1024
}
