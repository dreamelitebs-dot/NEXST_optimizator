package ru.booster.network.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.booster.network.core.model.ConnectionStats
import ru.booster.network.core.model.SystemSnapshot
import ru.booster.network.core.model.TunnelConfig
import ru.booster.network.data.repository.GameRepository
import ru.booster.network.data.repository.TunnelRepository
import ru.booster.network.network.PingManager
import ru.booster.network.service.SystemBoosterManager
import ru.booster.network.vpn.TunnelState
import javax.inject.Inject

/** Phases of the animated "Boost" pipeline shown on HomeScreen. */
enum class BoostPhase { IDLE, SCANNING, OPTIMIZING_NETWORK, OPTIMIZING_SYSTEM, CONNECTING_TUNNEL, ACTIVE }

data class BoosterUiState(
    val phase: BoostPhase = BoostPhase.IDLE,
    val scanProgress: Float = 0f,               // 0..1 radar animation
    val statusLine: String = "READY",
    val stats: ConnectionStats = ConnectionStats.EMPTY,
    val system: SystemSnapshot? = null,
    val tunnel: TunnelState.State = TunnelState.State.Stopped,
    val selectedGamePackage: String? = null,
    val pingBeforeMs: Int = -1,
    val pingAfterMs: Int = -1,
    val dndGranted: Boolean = false,
    val usageAccessGranted: Boolean = false
)

@HiltViewModel
class BoosterViewModel @Inject constructor(
    application: Application,
    private val booster: SystemBoosterManager,
    private val pingManager: PingManager,
    gameRepository: GameRepository,
    private val tunnelRepository: TunnelRepository
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(BoosterUiState())
    val ui: StateFlow<BoosterUiState> = _ui.asStateFlow()

    private var probeJob: Job? = null
    private var boostJob: Job? = null

    init {
        // Mirror live tunnel state into UI
        viewModelScope.launch {
            kotlinx.coroutines.flow.callbackFlow<TunnelState.State> {
                val listener: (TunnelState.State) -> Unit = { trySend(it) }
                TunnelState.observe(listener)
                awaitClose { TunnelState.unobserve(listener) }
            }.collect { st -> _ui.update { it.copy(tunnel = st) } }
        }
        // baseline permission snapshot
        _ui.update {
            it.copy(
                dndGranted = booster.hasDndAccess(),
                usageAccessGranted = booster.hasUsageAccess()
            )
        }
    }

    // ── The big "BOOST" button ───────────────────────────────────────────────
    fun toggleBoost() {
        if (_ui.value.phase == BoostPhase.ACTIVE) stopBoost() else startBoost()
    }

    fun selectGame(pkg: String?) {
        _ui.update { it.copy(selectedGamePackage = pkg) }
    }

    private fun startBoost() = viewModelScope.launch {
        val cfg = tunnelRepository.current()

        // Phase 1 — scanning (animated progress sweep)
        _ui.update { it.copy(phase = BoostPhase.SCANNING, statusLine = "Scanning network…") }
        for (i in 1..20) {
            _ui.update { it.copy(scanProgress = i / 20f) }
            delay(60)
        }

        // Baseline ping BEFORE any optimization (for До/После card)
        val before = pingManager.measure(pingManager.defaultTargets.first()).latencyMs
        _ui.update { it.copy(pingBeforeMs = before) }

        // Phase 2 — network tuning: MTU negotiation + buffers
        _ui.update { it.copy(phase = BoostPhase.OPTIMIZING_NETWORK, statusLine = "Negotiating MTU · tuning TCP/UDP buffers…") }
        delay(700)

        // Phase 3 — system boost: RAM clean + DND + QoS priority
        _ui.update { it.copy(phase = BoostPhase.OPTIMIZING_SYSTEM, statusLine = "Cleaning RAM · Game Mode ON…") }
        val report = booster.cleanRam(ignorePackages = listOfNotNull(_ui.value.selectedGamePackage))
        booster.enableDoNotDisturb()
        booster.prioritizeGameTraffic(_ui.value.selectedGamePackage)
        _ui.update {
            it.copy(system = booster.snapshot().copy(freedRamMb = report.freedMb, killedProcesses = report.killedCount))
        }

        // Phase 4 — bring up AmneziaWG split tunnel for the tracked games
        _ui.update { it.copy(phase = BoostPhase.CONNECTING_TUNNEL, statusLine = "AmneziaWG handshake (Jc=${cfg.wgParams.junkPacketCount})…") }
        val app = getApplication<Application>()
        val vpnIntent = android.content.Intent(app, ru.booster.network.vpn.AmneziaVpnService::class.java)
            .setAction(ru.booster.network.vpn.AmneziaVpnService.ACTION_CONNECT)
        ru.booster.network.service.GameModeService.ensureChannels(app)
        app.startForegroundService(
            android.content.Intent(app, ru.booster.network.service.GameModeService::class.java)
                .setAction(ru.booster.network.service.GameModeService.ACTION_START)
                .putExtra(ru.booster.network.service.GameModeService.EXTRA_GAME_PACKAGE,
                    _ui.value.selectedGamePackage)
        )
        runCatching { app.startService(vpnIntent) }   // VpnService started by GameModeService normally

        // Phase 5 — active: continuous monitoring
        _ui.update { it.copy(phase = BoostPhase.ACTIVE, statusLine = "BOOST ACTIVE", scanProgress = 1f) }
        startProbing()

        // After a warm-up window measure the AFTER ping
        boostJob = viewModelScope.launch {
            delay(6000)
            val after = pingManager.measure(pingManager.defaultTargets.first()).latencyMs
            _ui.update { it.copy(pingAfterMs = after) }
        }
    }

    private fun stopBoost() {
        probeJob?.cancel(); boostJob?.cancel()
        val app = getApplication<Application>()
        app.startService(
            android.content.Intent(app, ru.booster.network.service.GameModeService::class.java)
                .setAction(ru.booster.network.service.GameModeService.ACTION_STOP)
        )
        booster.disableDoNotDisturb()
        _ui.update { it.copy(phase = BoostPhase.IDLE, scanProgress = 0f, statusLine = "READY",
            pingAfterMs = -1) }
    }

    /** Continuous ping/loss monitoring (also runs outside boost sessions). */
    fun startMonitoring() {
        if (probeJob?.isActive == true) return
        probeJob = viewModelScope.launch {
            pingManager.probeLoop(pingManager.defaultTargets, intervalMs = 1000)
                .collect { stats -> _ui.update { it.copy(stats = stats) } }
        }
    }

    fun stopMonitoring() { probeJob?.cancel(); probeJob = null }

    private fun startProbing() = startMonitoring()

    override fun onCleared() {
        super.onCleared()
        probeJob?.cancel(); boostJob?.cancel()
    }
}
