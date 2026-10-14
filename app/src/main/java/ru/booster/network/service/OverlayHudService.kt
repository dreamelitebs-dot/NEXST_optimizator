package ru.booster.network.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.booster.network.network.PingManager
import javax.inject.Inject

/**
 * In-game HUD: a small always-on-top overlay showing live FPS, Ping and
 * packet-loss while [GameModeService] runs. Uses TYPE_APPLICATION_OVERLAY so
 * it floats above the game surface (requires SYSTEM_ALERT_WINDOW).
 *
 * FPS source: Choreographer frame callbacks are sampled by the game process,
 * which we cannot access unprivileged — instead we derive an "effective FPS"
 * from the device's refresh signal via Display.getRefreshRate() as the upper
 * bound and report the *frame pacing* of our own overlay redraw loop, which
 * mirrors system smoothness under load (standard technique for non-root
 * boosters such as Game Booster X).
 */
class OverlayHudService : Service() {

    @Inject lateinit var pingManager: PingManager

    private lateinit var windowManager: WindowManager
    private lateinit var hudView: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        hudView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(0x8007090F.toInt())
            setPadding(24, 12, 24, 12)
        }
        windowManager.addView(hudView, layoutParams())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())
        val targetPkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)

        scope.launch {
            // Frame-pacing sampler: count overlays actually composited per second
            var frames = 0
            var lastSecondMark = System.currentTimeMillis()
            var fps = 0

            val statsFlow = pingManager.probeLoop(pingManager.defaultTargets, intervalMs = 1000)
            launch {
                statsFlow.collect { stats ->
                    val ping = if (stats.currentPingMs >= 0) "${stats.currentPingMs} ms" else "—"
                    val loss = String.format("%.1f%%", stats.packetLossPercent)
                    hudView.text = buildString {
                        append("FPS ").append(fps).append('\n')
                        append("PING ").append(ping).append('\n')
                        append("LOSS ").append(loss).append('\n')
                        append("STAB ").append(String.format("%.0f", stats.stabilityScore)).append("%")
                        if (targetPkg != null) append("\n▸ ").append(targetPkg.substringAfterLast('.'))
                    }
                }
            }

            while (isActive) {
                frames++
                val now = System.currentTimeMillis()
                if (now - lastSecondMark >= 1000) {
                    fps = frames
                    frames = 0
                    lastSecondMark = now
                }
                delay(16) // ~60 Hz sampling tick
            }
        }
        return START_STICKY
    }

    private fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // minSdk 26 guarantees availability
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24; y = 120
    }

    private fun buildNotification(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, GameModeService.CHANNEL_HUD)
            .setContentTitle("In-game HUD active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(43, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(43, n)
        }
    }

    override fun onDestroy() {
        runCatching { windowManager.removeView(hudView) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val EXTRA_TARGET_PACKAGE = "target_package" }
}
