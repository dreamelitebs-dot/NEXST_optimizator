package ru.booster.network.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.booster.network.core.model.AmneziaWgParams
import ru.booster.network.core.model.TunnelConfig
import ru.booster.network.core.util.ConfigParser
import ru.booster.network.data.repository.GameRepository
import ru.booster.network.data.repository.TunnelRepository
import javax.inject.Inject

data class SettingsUiState(
    val config: TunnelConfig = TunnelConfig(),
    val trackedPackages: List<String> = emptyList(),
    val statusMessage: String? = null,
    val validationError: String? = null
)

@HiltViewModel
class VpnSettingsViewModel @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val gameRepository: GameRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            tunnelRepository.configFlow.collect { cfg ->
                _state.update { it.copy(config = cfg) }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(trackedPackages = gameRepository.trackedPackages()) }
        }
    }

    // ── Field updates ────────────────────────────────────────────────────────
    fun onConfigChange(transform: (TunnelConfig) -> TunnelConfig) =
        _state.update { it.copy(config = transform(it.config)) }

    fun onWgParamChange(transform: (AmneziaWgParams) -> AmneziaWgParams) =
        _state.update { st ->
            st.copy(config = st.config.copy(wgParams = transform(st.config.wgParams)))
        }

    fun toggleSplitTunnel(enabled: Boolean) =
        onConfigChange { it.copy(splitTunnelEnabled = enabled) }

    // ── Import .conf / JSON / URI ────────────────────────────────────────────
    fun importRaw(raw: String) = viewModelScope.launch {
        runCatching { ConfigParser.parse(raw) }
            .onSuccess { cfg ->
                _state.update { it.copy(config = cfg, statusMessage = "Configuration imported ✓") }
            }
            .onFailure { e ->
                _state.update { it.copy(validationError = "Import failed: ${e.message}") }
            }
    }

    // ── Save with validation ─────────────────────────────────────────────────
    fun save() = viewModelScope.launch {
        val cfg = _state.value.config
        val keyErrs = buildList {
            if (cfg.privateKey.isNotBlank() && !ConfigParser.isValidWgKey(cfg.privateKey, isPrivate = true))
                add("PrivateKey is not valid base64 curve25519 material")
            if (cfg.publicKey.isNotBlank() && !ConfigParser.isValidWgKey(cfg.publicKey, isPrivate = false))
                add("PublicKey is not valid base64 curve25519 material")
        }
        val wgErr = cfg.wgParams.validate().exceptionOrNull()?.message

        val errors = keyErrs + listOfNotNull(wgErr)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(validationError = errors.joinToString("\n")) }
            return@launch
        }
        // Attach split-tunnel whitelist from tracked games
        val finalCfg = cfg.copy(includedPackages = gameRepository.trackedPackages())
        tunnelRepository.save(finalCfg)
        _state.update {
            it.copy(statusMessage = "Saved · MTU auto=${if (cfg.mtu == 0) "ON" else "off"} · split=${cfg.splitTunnelEnabled}",
                    validationError = null)
        }
    }

    fun exportConf(): String = ConfigParser.toConf(_state.value.config)

    fun consumeMessages() = _state.update { it.copy(statusMessage = null, validationError = null) }
}
