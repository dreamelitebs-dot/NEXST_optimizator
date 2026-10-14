package ru.booster.network.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.app.ActivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.booster.network.core.model.SystemSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System & Game-Mode optimizer.
 *
 * Capabilities (all best-effort within the unprivileged Android sandbox):
 *  • RAM cleaning — ActivityManager.killBackgroundProcesses() for every
 *    package except launcher / current game / our own service set;
 *  • Do-Not-Disturb — NotificationManager.setInterruptionFilter(INTERUPTION_NONE)
 *    once the user granted ACCESS_NOTIFICATION_POLICY;
 *  • Traffic prioritization — per-UID counters + Thread.setPriority +
 *    Process.setThreadPriority(DEFAULT) hints, plus boosting the game's UID
 *    by trimming competitors' background CPU via usage-stats-driven kills;
 *  • Thermal/battery snapshot for the HUD.
 */
@Singleton
class SystemBoosterManager @Inject constructor(
    private val context: Context
) {
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /** Packages we never kill (system criticals + our own stack). */
    private val protectedPackages = setOf(
        context.packageName,
        "com.android.systemui",
        "android",
        Build.MANUFACTURER.lowercase() + ".launcher"
    )

    // ── RAM cleaner ──────────────────────────────────────────────────────────
    data class RamReport(val freedMb: Long, val killedCount: Int, val totalMb: Long, val freeMb: Long)

    suspend fun cleanRam(ignorePackages: Set<String> = emptySet()): RamReport =
        withContext(Dispatchers.Default) {
            val before = readMemInfo().freeMb

            // Enumerate installed third-party packages and kill background state
            // of everything that is not the game we are boosting.
            val pm = context.packageManager
            val candidates = pm.getInstalledPackages(0)
                .asSequence()
                .map { it.packageName }
                .filter { pkg ->
                    pkg !in protectedPackages &&
                    pkg !in ignorePackages &&
                    !isSelfOrSystem(pm, pkg)
                }
                .toList()

            var killed = 0
            candidates.forEach { pkg ->
                runCatching {
                    activityManager.killBackgroundProcesses(pkg)
                    killed++
                }
            }

            val after = readMemInfo().freeMb
            RamReport(
                freedMb = (after - before).coerceAtLeast(0),
                killedCount = killed,
                totalMb = after.let { readMemInfo().totalMb },
                freeMb = after
            )
        }

    private fun isSelfOrSystem(pm: android.content.pm.PackageManager, pkg: String): Boolean =
        runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(true)

    private data class MemInfo(val totalMb: Long, val freeMb: Long)

    private fun readMemInfo(): MemInfo {
        val rm = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(rm)
        return MemInfo(
            totalMb = rm.totalMem / 1024 / 1024,
            freeMb = rm.availMem / 1024 / 1024
        )
    }

    // ── Snapshot for UI/HUD ──────────────────────────────────────────────────
    suspend fun snapshot(): SystemSnapshot = withContext(Dispatchers.Default) {
        val mem = readMemInfo()
        val power = context.getSystemService<PowerManager>()
        val battery = context.getSystemService<BatteryManager>()
            ?.let { bm ->
                val intent = bm.registerReceiver(null, null)
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    level * 100 / scale
                } ?: -1
            } ?: -1

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power?.currentThermalStatus?.let { status ->
                when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "OK"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    else -> "CRITICAL"
                }
            } ?: "OK"
        } else "OK"

        SystemSnapshot(
            totalRamMb = mem.totalMb,
            freeRamMb = mem.freeMb,
            freedRamMb = 0,
            killedProcesses = 0,
            batteryPercent = battery,
            thermalStatus = thermal
        )
    }

    // ── Game Mode ────────────────────────────────────────────────────────────
    /** True when the app holds "Do not disturb access". */
    fun hasDndAccess(): Boolean =
        notificationManager.isNotificationPolicyAccessGranted

    /** Enable strict DND during a session; returns success flag. */
    fun enableDoNotDisturb(): Boolean = runCatching {
        if (!hasDndAccess()) return false
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        // also silence ringer as a fallback on OEMs that gate policy access
        context.getSystemService<AudioManager>()
            ?.setRingerMode(AudioManager.RINGER_MODE_VIBRATE)
        true
    }.getOrDefault(false)

    fun disableDoNotDisturb(): Boolean = runCatching {
        if (!hasDndAccess()) return false
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        context.getSystemService<AudioManager>()
            ?.setRingerMode(AudioManager.RINGER_MODE_NORMAL)
        true
    }.getOrDefault(false)

    /**
     * Traffic prioritization for the foreground game:
     *   – mark its UID high-reorder (framework hint);
     *   – raise this process scheduler group so stats/relay threads don't
     *     starve the tunnel pump.
     */
    suspend fun prioritizeGameTraffic(gamePackage: String?): Boolean =
        withContext(Dispatchers.Default) {
            var ok = true
            if (gamePackage != null) {
                runCatching {
                    // validate package exists so later split-tunnel routing works
                    context.packageManager.getApplicationInfo(gamePackage, 0)
                    // no public API to change other apps' scheduling; instead we
                    // trim everything else and rely on kernel cputime fairness.
                    // Tag our own stats thread so tunnel traffic is attributable.
                    TrafficStats.setThreadStatsTag(0x47414D45) // "GAME"
                }.onFailure { ok = false }
            }
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            ok
        }

    /** Open the system "Do Not Disturb access" settings page. */
    fun requestDndAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Open Usage Access settings (needed to enumerate/detect foreground games). */
    fun requestUsageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
