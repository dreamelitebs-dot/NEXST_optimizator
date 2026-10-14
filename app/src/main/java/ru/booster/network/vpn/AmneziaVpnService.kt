package ru.booster.network.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.booster.network.core.model.TunnelConfig
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject

/**
 * AmneziaWG / WireGuard VPN service.
 *
 * ── Architecture ────────────────────────────────────────────────────────────
 *  • [Builder] sets up the tun0 interface with:
 *      - negotiated MTU (see [MtuOptimizer]) — smallest non-fragmenting value;
 *      - per-game split tunneling via addAllowedApplication(): ONLY packages
 *        whitelisted for boost are captured by the tunnel, every other app
 *        keeps using the physical interface directly (bypass);
 *      - DNS servers from config; TCP/UDP buffer hints applied to sockets
 *        created while bound to this vpn profile.
 *
 *  • [AmneziaWgHandshake] performs the DPI-obfuscated handshake: it emits
 *    Jc junk datagrams of random size in [Jmin..Jmax], each prefixed with
 *    S1||S2 hex bytes, immediately followed by the real WG Initiation packet.
 *
 *  • The production data plane delegates to the vendored native wg-tunnel
 *    ("tunnel" AAR, see app/build.gradle.kts). When the native library is not
 *    linked into the build, [LocalRelayTunnel] keeps the tun pipe alive as a
 *    transparent UDP relay so the whole UI/statistics stack remains testable.
 */
class AmneziaVpnService : VpnService() {

    @Inject lateinit var configProvider: TunnelConfigHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // field injection without pulling full Hilt module into a system-managed
        // service class keeps the manifest registration simple & robust
        if (!::configProvider.isInitialized) configProvider = TunnelConfigHolder.INSTANCE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> establishTunnel()
            ACTION_DISCONNECT -> { stopTunnel(); stopSelf() }
            else -> if (intent == null) return START_STICKY
        }
        return START_STICKY
    }

    // ── Tunnel lifecycle ─────────────────────────────────────────────────────
    fun establishTunnel() {
        if (tunnelJob?.isActive == true) return
        if (prepare(this) != null) return                       // user consent missing

        val cfg = configProvider.config
        if (!cfg.isValid) return

        val mtuOptimizer = MtuOptimizer(applicationContext)
        val tunnelMtu = mtuOptimizer.negotiateTunnelMtu(cfg)

        try {
            val builder = Builder()
                .setSession(cfg.name)
                .setMtu(tunnelMtu)
                .addAddress(cfg.address.substringBefore('/'), 32)
                .addDnsServers(cfg.dnsServers)

            // ── Split tunneling ──────────────────────────────────────────
            // Whitelist mode: only tracked game packages traverse the tunnel.
            if (cfg.splitTunnelEnabled && cfg.includedPackages.isNotEmpty()) {
                cfg.includedPackages.forEach { builder.addAllowedApplication(it) }
            } else {
                cfg.allowedIps.forEach { route ->
                    runCatching {
                        val (ip, prefix) = route.split('/')
                        builder.addAllRoute("$ip/${prefix.toInt()}")
                    }
                }
            }
            // Never capture our own booster UI traffic or the tunnel endpoint
            builder.addDisallowedApplication(packageName)
            protectEndpoint(builder, cfg)

            val underlayMtu = mtuOptimizer.underlayMtu()
            val tunFd: ParcelFileDescriptor = builder.establish() ?: return

            notifyStarted("MTU $tunnelMtu · underlay $underlayMtu")

            tunnelJob = scope.launch {
                // 1. Obfuscated AmneziaWG handshake toward the endpoint
                AmneziaWgHandshake.perform(
                    host = cfg.endpointHost,
                    port = cfg.endpointPort,
                    params = cfg.wgParams
                )

                // 2. Data plane: native wg-tunnel when present, relay otherwise
                TunnelEngine.open(
                    service = this@AmneziaVpnService,
                    config = cfg,
                    tunFd = tunFd,
                    mtu = tunnelMtu
                ).collect { stats -> TunnelState.update(stats) }
            }
        } catch (t: Throwable) {
            TunnelState.markError(t.message ?: "tunnel failure")
        }
    }

    /** Exclude the tunnel endpoint itself from interception → no routing loop. */
    private fun protectEndpoint(builder: Builder, cfg: TunnelConfig) {
        runCatching {
            java.net.InetAddress.getByName(cfg.endpointHost).let { addr ->
                builder.addAllRoute("${addr.hostAddress}/32")
            }
        }
    }

    private fun stopTunnel() {
        tunnelJob?.cancel()
        tunnelJob = null
        TunnelState.markStopped()
    }

    private fun notifyStarted(detail: String) {
        TunnelState.markConnecting(detail)
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "ru.booster.network.vpn.CONNECT"
        const val ACTION_DISCONNECT = "ru.booster.network.vpn.DISCONNECT"
    }
}

/** Process-wide holder so both UI layer and the system-created service share one config. */
class TunnelConfigHolder @Inject constructor() {
    @Volatile var config: TunnelConfig = TunnelConfig()
        private set

    fun update(newConfig: TunnelConfig) { config = newConfig }

    companion object { val INSTANCE = TunnelConfigHolder() }
}

/** Live tunnel state observed by ViewModels. */
object TunnelState {
    sealed interface State {
        data object Stopped : State
        data class Connecting(val detail: String) : State
        data class Connected(val rxBytes: Long, val txBytes: Long) : State
        data class Error(val message: String) : State
    }

    private val listeners = mutableListOf<(State) -> Unit>()
    var current: State = State.Stopped
        private set(value) {
            field = value
            listeners.toList().forEach { it(value) }
        }

    fun observe(block: (State) -> Unit) { listeners += block; block(current) }
    fun unobserve(block: (State) -> Unit) { listeners -= block }
    fun markConnecting(detail: String) { current = State.Connecting(detail) }
    fun markConnected(rx: Long, tx: Long) { current = State.Connected(rx, tx) }
    fun markError(message: String) { current = State.Error(message) }
    fun markStopped() { current = State.Stopped }
}
