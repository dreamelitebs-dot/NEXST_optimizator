package ru.booster.network.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A game tracked by the booster (installed app or user-added package). */
@Entity(tableName = "games")
data class GameInfo(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isTracked: Boolean = false,           // added to split-tunnel whitelist
    val pingBeforeMs: Int = -1,               // measured without boost
    val pingAfterMs: Int = -1,                // measured through tunnel
    val lastLaunchedAt: Long = 0L,
    val installOrder: Int = 0
)

/** Latency probe result against a game server / well-known endpoint. */
data class PingSample(
    val timestamp: Long,
    val targetHost: String,
    val latencyMs: Int,          // -1 == timeout / lost
    val method: PingMethod
)

enum class PingMethod { ICMP, UDP_ECHO, TCP_CONNECT }

/** Aggregated live connection statistics shown on HomeScreen cards. */
data class ConnectionStats(
    val samples: List<PingSample> = emptyList(),
    val currentPingMs: Int = -1,
    val minPingMs: Int = -1,
    val avgPingMs: Int = -1,
    val maxPingMs: Int = -1,
    val jitterMs: Double = 0.0,
    val packetLossPercent: Float = 0f,
    val stabilityScore: Float = 0f,      // 0..100 — derived from jitter+loss
    val fps: Int = 0,
    val downKbps: Long = 0,
    val upKbps: Long = 0
) {
    companion object { val EMPTY = ConnectionStats() }
}

/** Snapshot of system resources produced by SystemBoosterManager. */
data class SystemSnapshot(
    val totalRamMb: Long,
    val freeRamMb: Long,
    val freedRamMb: Long,
    val killedProcesses: Int,
    val cpuTempMilliC: Int = 0,
    val batteryPercent: Int = 100,
    val thermalStatus: String = "OK"
)
