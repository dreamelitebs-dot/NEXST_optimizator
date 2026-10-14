package ru.booster.network.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Sends a UDP datagram of [payloadBytes] and waits for any reply/ICMP error.
 * Used by MtuOptimizer to walk down candidate MTU sizes. On Android we cannot
 * set IP_MTU_DISCOVER without NDK, so we rely on the endpoint's behaviour:
 * oversized fragments that get dropped simply time out → step down.
 */
internal object UdpDfProbe {

    suspend fun probe(host: String, port: Int, payloadBytes: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = PROBE_TIMEOUT_MS
                    val addr = InetAddress.getByName(host)
                    val payload = ByteArray(payloadBytes.coerceAtLeast(1)) { 'A'.code.toByte() }
                    socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(addr, port)))
                    val reply = ByteArray(2048)
                    val rp = DatagramPacket(reply, reply.size)
                    try {
                        socket.receive(rp)   // any response ⇒ path supports this size
                        true
                    } catch (_: java.net.SocketTimeoutException) {
                        false
                    }
                }
            }.getOrDefault(false)
        }

    private const val PROBE_TIMEOUT_MS = 800
}
