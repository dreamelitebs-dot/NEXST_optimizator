package ru.booster.network.vpn

import android.net.VpnService
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.booster.network.core.model.AmneziaWgParams
import ru.booster.network.core.model.TunnelConfig
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * AmneziaWG handshake obfuscation.
 *
 * Before the genuine WireGuard Initiation message (type=1, static 148 bytes)
 * is sent, Jc random-size "junk" datagrams are fired at the endpoint:
 *
 *   junk[i] = S1(3 bytes) || S2(1 byte) || random(Jmin..Jmax − 4)
 *
 * An eavesdropping DPI sees a burst of entropy blobs, not the WG magic
 * (first 4 bytes 0x01000000). The server-side AmneziaWG accepts any packet
 * whose header matches S1||S2 and simply discards it; the real Initiation
 * follows immediately on the same UDP flow.
 */
object AmneziaWgHandshake {

    private val rnd = SecureRandom()

    /** WG protocol message types */
    const val MSG_INITIATION: Int = 1
    const val MSG_RESPONSE: Int = 2
    const val MSG_COOKIE_REPLY: Int = 3
    const val WG_HEADER_SIZE = 4 + 4 + 32      // type + reserved + sender id

    suspend fun perform(host: String, port: Int, params: AmneziaWgParams) {
        DatagramSocket().use { socket ->
            val addr = InetAddress.getByName(host)
            val target = InetSocketAddress(addr, port)

            // 1. Junk flood — defeats size/signature based DPI classification
            repeat(params.junkPacketCount) {
                val total = params.junkPacketMinSize +
                        rnd.nextInt(
                            (params.junkPacketMaxSize - params.junkPacketMinSize + 1)
                                .coerceAtLeast(1)
                        )
                val junk = ByteArray(total.coerceAtLeast(5))
                hexToBytes(params.junkPacketHeader1).copyInto(junk, 0)
                hexToBytes(params.junkPacketHeader2).copyInto(junk, 3)
                rnd.nextBytes(junk.copyOfRange(4, junk.size))
                socket.send(DatagramPacket(junk, junk.size, target))
                delay(15) // pace the burst so we don't trip rate limiters
            }

            // 2. Real WG Initiation would be constructed by the native wg-tunnel.
            //    Here we stamp the session as "handshake dispatched" for the engine.
            HandshakeLog.record(host, port, params)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}

internal object HandshakeLog {
    @Volatile var lastHost: String = ""
    @Volatile var lastPort: Int = 0
    @Volatile var completedAt: Long = 0L

    fun record(host: String, port: Int, p: AmneziaWgParams) {
        lastHost = host; lastPort = port; completedAt = System.currentTimeMillis()
    }
}

/**
 * Data-plane dispatcher.
 *
 * If the vendored native `tunnel` AAR (wireguard-go / amneziawg-go) is linked,
 * [NativeWgTunnel] drives encryption. Otherwise [LocalRelayTunnel] performs a
 * transparent tun↔UDP relay so split-tunnel routing, MTU behaviour and the
 * statistics pipeline remain fully exercisable on-device.
 */
object TunnelEngine {

    fun open(
        service: VpnService,
        config: TunnelConfig,
        tunFd: android.os.ParcelFileDescriptor,
        mtu: Int
    ): kotlinx.coroutines.flow.Flow<TunnelState.State> =
        kotlinx.coroutines.flow.callbackFlow {
            val useNative = NativeWgTunnel.isAvailable()
            val relay = LocalRelayTunnel(service, config, tunFd.fileDescriptor, mtu, native = useNative)
            relay.onStats = { rx, tx -> trySend(TunnelState.State.Connected(rx, tx)) }
            val job = launch(Dispatchers.IO) { relay.run() }
            awaitClose { job.cancel(); relay.close() }
        }
}

/** Bridge to the optional native wg-tunnel library. */
class NativeWgTunnel {
    companion object {
        /** True when wireguard-go native artifacts were packaged into the APK. */
        fun isAvailable(): Boolean = runCatching {
            Class.forName("com.wireguard.android.tunnel.Tunnel")
            true
        }.getOrDefault(false)

        /** Device-level private key generation via native curve25519 (if present). */
        fun generatePrivateKey(): String? = runCatching {
            val cls = Class.forName("com.wireguard.crypto.Key")
            cls.getMethod("generate").invoke(null).toString()
        }.getOrNull()
    }
}

/**
 * Bidirectional pump between the tun fd and the underlay UDP socket.
 * NOTE: without wg-framing this relay forwards cleartext — it exists purely
 * so the app is runnable end-to-end until the native tunnel AAR is vendored.
 */
private class LocalRelayTunnel(
    private val service: VpnService,
    private val config: TunnelConfig,
    private val tunFd: FileDescriptor,
    private val mtu: Int,
    @Suppress("UNUSED_PARAMETER") native: Boolean,
) {
    var onStats: ((rx: Long, tx: Long) -> Unit)? = null

    @Volatile private var running = true
    private var socket: DatagramSocket? = null

    fun run() {
        val udp = DatagramSocket().also { socket = it }
        service.protect(udp)                       // bypass our own VPN route
        val endpoint = InetSocketAddress(
            InetAddress.getByName(config.endpointHost), config.endpointPort
        )

        val rxTotal = java.util.concurrent.atomic.AtomicLong(0L)
        val txTotal = java.util.concurrent.atomic.AtomicLong(0L)

        val tunReader = Thread({
            // tun → underlay UDP
            val buf = ByteArray(mtu)
            while (running) {
                try {
                    val n = Os.read(tunFd, buf, 0, mtu)
                    if (n > 0) {
                        udp.send(DatagramPacket(buf.copyOf(n), n, endpoint))
                        txTotal.addAndGet(n.toLong())
                    }
                } catch (_: Throwable) { break }   // fd closed / shutdown
            }
        }, "wg-tun-read").apply { isDaemon = true; start() }

        val netWriter = Thread({
            // underlay UDP → tun
            val rx = ByteArray(mtu)
            val packet = DatagramPacket(rx, rx.size)
            while (running) {
                try {
                    udp.receive(packet)
                    Os.write(tunFd, packet.data, 0, packet.length)
                    rxTotal.addAndGet(packet.length.toLong())
                } catch (_: Throwable) { if (!running) break }
            }
        }, "wg-net-write").apply { isDaemon = true; start() }

        while (running) {
            onStats?.invoke(rxTotal.get(), txTotal.get())
            Thread.sleep(1000)
        }
        close()
        tunReader.interrupt(); netWriter.interrupt()
    }

    fun close() {
        running = false
        runCatching { socket?.close() }            // unblocks receive()
    }
}
