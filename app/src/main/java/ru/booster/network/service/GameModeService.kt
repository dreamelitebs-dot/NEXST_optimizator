package ru.booster.network.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.booster.network.R
import ru.booster.network.network.PingManager
import ru.booster.network.vpn.AmneziaVpnService
import javax.inject.Inject

/**
 * Game-mode session controller.
 *
 * Started when the user taps "Boost":
 *   1. cleans RAM (keeping the target game alive),
 *   2. switches on Do-Not-Disturb,
 *   3. raises network priority for the game's traffic,
 *   4. starts the AmneziaWG split tunnel for the whitelisted package,
 *   5. launches [OverlayHudService] if the user granted overlay permission.
 */
class GameModeService : Service() {

    @Inject lateinit var booster: SystemBoosterManager
    @Inject lateinit var pingManager: PingManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val game = intent.getStringExtra(EXTRA_GAME_PACKAGE)
                startForegroundCompat(buildNotification(game ?: "all games"))
                beginSession(game)
            }
            ACTION_STOP -> stopSession()
        }
        return START_STICKY
    }

    /** API 34+ requires an explicit FGS type; specialUse is declared in the manifest. */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun beginSession(gamePackage: String?) = scope.launch {
        // 1. RAM cleaning — never kill the game we are boosting
        val ignore = listOfNotNull(gamePackage).toSet()
        booster.cleanRam(ignorePackages = ignore)

        // 2. DND during play
        booster.enableDoNotDisturb()

        // 3. Traffic prioritization (high QoS sockets / thread scheduling)
        booster.prioritizeGameTraffic(gamePackage)

        // 4. VPN split-tunnel: only this game goes through AmneziaWG
        val vpn = Intent(applicationContext, AmneziaVpnService::class.java)
            .setAction(AmneziaVpnService.ACTION_CONNECT)
        startService(vpn)

        // 5. HUD overlay
        if (canDrawOverlays()) {
            startService(
                Intent(applicationContext, OverlayHudService::class.java)
                    .putExtra(OverlayHudService.EXTRA_TARGET_PACKAGE, gamePackage)
            )
        }
    }

    private fun stopSession() {
        booster.disableDoNotDisturb()
        stopService(Intent(applicationContext, OverlayHudService::class.java))
        startService(
            Intent(applicationContext, AmneziaVpnService::class.java)
                .setAction(AmneziaVpnService.ACTION_DISCONNECT)
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(this)

    private fun buildNotification(gameLabel: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_GAME)
            .setContentTitle(getString(R.string.notif_game_title))
            .setContentText("Boosting: $gameLabel")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.notif_action_stop),
                    PendingIntent.getBroadcast(
                        this, 1,
                        Intent(this, BootReceiver::class.java).setAction(ACTION_STOP),
                        flagImmutable()
                    )
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_GAME = "game_mode"
        const val CHANNEL_VPN = "vpn_tunnel"
        const val CHANNEL_HUD = "hud_overlay"
        const val ACTION_START = "ru.booster.network.game.START"
        const val ACTION_STOP = "ru.booster.network.game.STOP"
        const val EXTRA_GAME_PACKAGE = "game_package"
        const val NOTIF_ID = 42

        fun flagImmutable(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE else 0

        /** Create all app notification channels (call once at startup). */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_VPN,
                    context.getString(R.string.notif_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_GAME,
                    context.getString(R.string.notif_channel_game),
                    NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_HUD,
                    context.getString(R.string.notif_channel_hud),
                    NotificationManager.IMPORTANCE_MIN)
            )
        }
    }
}

/** Handles the "Stop Boost" notification action. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GameModeService.ACTION_STOP) {
            context.startService(
                Intent(context, GameModeService::class.java)
                    .setAction(GameModeService.ACTION_STOP)
            )
        }
    }
}
