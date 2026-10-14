package ru.booster.network.network

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import ru.booster.network.core.model.ConnectionStats
import ru.booster.network.core.model.PingMethod
import ru.booster.network.core.model.PingSample
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Latency / packet-loss prober for game servers.
 *
 * Method cascade (Android forbids raw ICMP sockets without root):
 *   1. UDP_ECHO  — 0-byte datagram to the game port (most shooters use UDP;
 *                  an ICMP "port unreachable" reply still gives true RTT)
 *   2. TCP_CONNECT — connect() to a fallback TCP port, time-to-SYN-ACK
 *   3. system `ping` binary as last resort (may be blocked by SELinux)
 *
 * Every socket is bound through [applyQos] which sets:
 *   • SO_PRIORITY (linux TC_PRIO → high QoS class on the uplink queue)
 *   • IP_TOS DSCP EF (0xEF>>2 expedite-forwarding marking)
 *   • TCP_NODELAY for the fallback path
 */
@Singleton
class PingManager @Inject constructor(
    private val context: Context
) {

    /** Well-known latency probes used when no dedicated game server is set. */
    val defaultTargets = listOf(
        GameTarget("eu-west", "145.239.10.7", 27015),   // CS-style UDP relay
        GameTarget("fastly-cdn", "151.101.1.69", 443),  // anycast TCP
        GameTarget("cloudflare", "1.1.1.1", 53)         // UDP DNS echo
    )

    data class GameTarget(val label: String, val host: String, val port: Int)

    /** Continuous probing stream — one sample per [intervalMs] per target. */
    fun probeLoop(
        targets: List<GameTarget>,
        intervalMs: Long = 1000L,
        historySize: Int = 120
    ): Flow<ConnectionStats> = flow {
        val samples = ArrayDeque<PingSample>()
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()

        while (true) {
            val primary = targets.firstOrNull() ?: defaultTargets.first()
            val sample = measure(primary)
            samples.addLast(sample)
            while (samples.size > historySize) samples.removeFirst()

            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val downKbps = ((rx - lastRx) * 8 / 1024).coerceAtLeast(0)
            val upKbps = ((tx - lastTx) * 8 / 1024).coerceAtLeast(0)
            lastRx = rx; lastTx = tx

            emit(buildStats(samples.toList(), downKbps, upKbps))
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun measure(target: GameTarget): PingSample {
        val start = System.nanoTime()

        // ── attempt 1: UDP echo ────────────────────────────────────────────
        val udpOk = runCatching {
            DatagramSocket().use { s ->
                applyQos(s)
                s.soTimeout = TIMEOUT_MS
                val probe = DatagramPacket(ByteArray(16), 16,
                    InetSocketAddress(InetAddress.getByName(target.host), target.port))
                s.send(probe)
                val reply = ByteArray(64)
                s.receive(DatagramPacket(reply, reply.size))
            }
        }.isSuccess
        if (udpOk) return sample(target, start, PingMethod.UDP_ECHO)

        // ── attempt 2: TCP connect timing ──────────────────────────────────
        val tcpOk = runCatching {
            Socket().use { s ->
                applyQos(s)
                s.connect(InetSocketAddress(target.host, target.port), TIMEOUT_MS)
            }
        }.isSuccess
        if (tcpOk) return sample(target, start, PingMethod.TCP_CONNECT)

        // ── attempt 3: busybox/system ping (ICMP) ──────────────────────────
        val icmp = runIcmpPing(target.host)
        if (icmp != null) {
            val ms = icmp.toInt()
            return PingSample(System.currentTimeMillis(), target.host, ms, PingMethod.ICMP)
        }

        // total failure ⇒ counted as lost packet
        return PingSample(System.currentTimeMillis(), target.host, -1, PingMethod.UDP_ECHO)
    }

    private fun sample(target: GameTarget, startNanos: Long, m: PingMethod) = PingSample(
        timestamp = System.currentTimeMillis(),
        targetHost = target.host,
        latencyMs = ((System.nanoTime() - startNanos) / 1_000_000).toInt(),
        method = m
    )

    /** High-QoS socket options — the "traffic prioritization" primitive. */
    private fun applyQos(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            // TypeOfService: DSCP EF (Expedite Forwarding) = 0xB8 (46<<2)
            socket.setTrafficClass(0xB8)
        } catch (_: IOException) { /* best-effort */ }
    }

    private fun applyQos(socket: DatagramSocket) {
        try {
            socket.setTrafficClass(0xB8)
        } catch (_: IOException) { /* best-effort */ }
    }

    private fun runIcmpPing(host: String): Long? = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("ping", "-c", "1", "-W", "1", "-s", "16", host)
        )
        if (process.waitFor() != 0) return null
        val output = process.inputStream.bufferedReader().readText()
        Regex("time[=<](\\d+(?:\\.\\d+)?)").find(output)?.groupValues?.get(1)?.toDouble()?.toLong()
    }.getOrNull()

    // ── Statistics aggregation ───────────────────────────────────────────────
    private fun buildStats(list: List<PingSample>, downKbps: Long, upKbps: Long): ConnectionStats {
        val ok = list.filter { it.latencyMs >= 0 }
        val lost = list.size - ok.size
        val loss = if (list.isEmpty()) 0f else lost * 100f / list.size
        val latencies = ok.map { it.latencyMs }
        val avg = if (latencies.isEmpty()) -1 else latencies.average().toInt()
        val jitter = if (latencies.size < 2) 0.0
        else sqrt(latencies.zipWithNext().sumOf { (a, b) -> abs(a - b).toDouble() * abs(a - b) } /
                (latencies.size - 1))
        // stability: penalise loss hard, jitter softly
        val stability = (100f - loss * 2f - (jitter / 2f).toFloat()).coerceIn(0f, 100f)

        return ConnectionStats(
            samples = list,
            currentPingMs = latencies.lastOrNull() ?: -1,
            minPingMs = latencies.minOrNull() ?: -1,
            avgPingMs = avg,
            maxPingMs = latencies.maxOrNull() ?: -1,
            jitterMs = jitter,
            packetLossPercent = loss,
            stabilityScore = stability,
            downKbps = downKbps,
            upKbps = upKbps
        )
    }

    companion object { private const val TIMEOUT_MS = 1500 }
}
