package ru.booster.network.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import ru.booster.network.core.model.GameInfo
import ru.booster.network.data.local.GameDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers installed games (launcher apps whose category/feature flags look
 * game-like, plus anything the user adds manually) and persists tracking state.
 */
@Singleton
class GameRepository @Inject constructor(
    private val context: Context,
    private val dao: GameDao
) {
    fun observeGames(): Flow<List<GameInfo>> = dao.observeAll()

    suspend fun syncInstalledApps(): Int = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcherIntent, 0)

        val games = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            val appInfo = ri.activityInfo.applicationInfo
            GameInfo(
                packageName = pkg,
                appName = ri.loadLabel(pm).toString(),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installOrder = appInfo.firstInstallTime.toInt()
            )
        }
        // Only store likely-games + everything user explicitly tracks later;
        // filter heuristic: GPU feature or "game" in name/metadata
        val likelyGames = games.filter { g ->
            !g.isSystemApp && (isLikelyGame(g.packageName) || g.appName.contains("game", true))
        }
        dao.insertAll(likelyGames)
        likelyGames.size
    }

    private fun isLikelyGame(pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        val features = pm.getPackageInfo(pkg, PackageManager.GET_CONFIG_LIMITS)
            .reqFeatures.orEmpty()
        features.any { it.name?.contains("android.hardware.game") == true } ||
                pkg.contains(".game", true) || GAME_HINTS.any { pkg.contains(it) }
    }.getOrDefault(false)

    /** User-added arbitrary app package (custom "game"). */
    suspend fun addCustomApp(pkg: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            dao.upsert(
                GameInfo(
                    packageName = pkg,
                    appName = pm.getApplicationLabel(info).toString(),
                    isTracked = true
                )
            )
        }
    }

    suspend fun setTracked(pkg: String, tracked: Boolean) = dao.setTracked(pkg, tracked)

    suspend fun recordPing(pkg: String, before: Int, after: Int) = dao.recordPing(pkg, before, after)

    suspend fun trackedPackages(): List<String> =
        dao.trackedGames().map { it.packageName }

    companion object {
        private val GAME_HINTS = listOf(
            "pubg", "mobilelegends", "mlbb", "freefire", "cod", "callofduty",
            "genshin", "honkai", "dota", "arena", "clash", "roblox", "fortnite",
            "brawlstars", "standoff", "pointblank", "warzone", "naraka"
        )
    }
}
