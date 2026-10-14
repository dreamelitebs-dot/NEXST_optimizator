package ru.booster.network.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Games
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.booster.network.ui.BoosterUiState
import ru.booster.network.ui.BoostPhase
import ru.booster.network.ui.components.BoostButton
import ru.booster.network.ui.components.PingGraph
import ru.booster.network.ui.components.StatChip
import ru.booster.network.ui.theme.NeonCyan
import ru.booster.network.ui.theme.NeonGreen
import ru.booster.network.ui.theme.NeonMagenta
import ru.booster.network.ui.theme.pingColor
import ru.booster.network.vpn.TunnelState

@Composable
fun HomeScreen(
    state: BoosterUiState,
    onBoostClick: () -> Unit,
    onOpenGames: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scanning = state.phase in setOf(
        BoostPhase.SCANNING, BoostPhase.OPTIMIZING_NETWORK,
        BoostPhase.OPTIMIZING_SYSTEM, BoostPhase.CONNECTING_TUNNEL
    )
    val active = state.phase == BoostPhase.ACTIVE

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Games, null, tint = NeonMagenta)
                Spacer(Modifier.width(8.dp))
                Text("NETWORK//BOOSTER", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                IconButtonCompat(Icons.Default.Settings, "Настройки", onOpenSettings)
            }

            // ── Boost orb ────────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            BoostButton(
                active = active,
                scanning = scanning,
                progress = state.scanProgress,
                pingMs = state.stats.currentPingMs,
                modifier = Modifier.clickable(enabled = !scanning) { onBoostClick() }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (scanning) {
                        CircularProgressIndicator(
                            progress = { state.scanProgress },
                            color = NeonMagenta, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 3.dp, modifier = Modifier.size(width = 56.dp, height = 56.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                    } else {
                        Text(
                            text = if (active) "ON" else "BOOST",
                            style = MaterialTheme.typography.displayLarge,
                            color = if (active) NeonGreen else NeonCyan
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = state.statusLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── Live stats card ──────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            ConnectionStatsCard(state)

            // ── Tunnel status strip ──────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            TunnelStatusRow(state.tunnel)

            // ── Games shortcut ───────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGames)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, null, tint = NeonCyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Игры для ускорения", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.selectedGamePackage ?: "Выберите игру · Split Tunneling",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionStatsCard(state: BoosterUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("СТАТИСТИКА СОЕДИНЕНИЯ", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatChip("PING", "${state.stats.currentPingMs.coerceAtLeast(0)} ms",
                    valueColor = pingColor(state.stats.currentPingMs))
                StatChip("JITTER", String.format("%.1f ms", state.stats.jitterMs))
                StatChip("LOSS", String.format("%.1f%%", state.stats.packetLossPercent),
                    valueColor = if (state.stats.packetLossPercent > 5) NeonMagenta else NeonGreen)
                StatChip("STAB", String.format("%.0f%%", state.stats.stabilityScore))
            }

            Spacer(Modifier.height(12.dp))
            PingGraph(
                samples = state.stats.samples.map { it.latencyMs }.takeLast(60),
                modifier = Modifier.fillMaxWidth().height(90.dp)
            )

            // До / После comparison
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ПИНГ ДО", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${maxOf(state.pingBeforeMs, 0)} ms",
                        style = MaterialTheme.typography.titleLarge, color = NeonMagenta)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▾", style = MaterialTheme.typography.titleLarge, color = NeonCyan)
                    val delta = if (state.pingBeforeMs >= 0 && state.pingAfterMs >= 0)
                        state.pingAfterMs - state.pingBeforeMs else 0
                    Text(if (delta < 0) "$delta ms" else "",
                        style = MaterialTheme.typography.bodyMedium, color = NeonGreen)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ПИНГ ПОСЛЕ", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (state.pingAfterMs >= 0) "${state.pingAfterMs} ms" else "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = pingColor(state.pingAfterMs)
                    )
                }
            }

            state.system?.let { sys ->
                Spacer(Modifier.height(10.dp))
                Text(
                    buildString {
                        append("RAM free ${sys.freeRamMb}/${sys.totalRamMb} MB")
                        if (sys.freedRamMb > 0) append(" · freed ${sys.freedRamMb} MB · killed ${sys.killedProcesses}")
                        append(" · ${sys.thermalStatus}")
                        if (sys.batteryPercent >= 0) append(" · batt ${sys.batteryPercent}%")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TunnelStatusRow(tunnel: TunnelState.State) {
    val (label, color) = when (tunnel) {
        TunnelState.State.Stopped -> "TUNNEL: OFFLINE" to MaterialTheme.colorScheme.onSurfaceVariant
        is TunnelState.State.Connecting -> "TUNNEL: ${tunnel.detail}" to NeonMagenta
        is TunnelState.State.Connected ->
            "TUNNEL: AMNEZIAWG UP ↑${tunnel.txBytes / 1024}KB ↓${tunnel.rxBytes / 1024}KB" to NeonGreen
        is TunnelState.State.Error -> "TUNNEL ERROR: ${tunnel.message}" to MaterialTheme.colorScheme.error
    }
    Card(shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun IconButtonCompat(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Icon(icon, contentDescription = desc, tint = NeonCyan,
        modifier = Modifier.padding(8.dp).clickable(onClick = onClick))
}
