package ru.booster.network.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import ru.booster.network.core.model.TunnelConfig
import ru.booster.network.vpn.TunnelConfigHolder
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tunnelDataStore: DataStore<Preferences> by preferencesDataStore("amnezia_wg")

/**
 * Persists the AmneziaWG tunnel configuration (keys, Jc/Jmin/Jmax/S1/S2,
 * split-tunnel package list) in DataStore and mirrors it into the process-wide
 * [TunnelConfigHolder] consumed by the VpnService.
 */
@Singleton
class TunnelRepository @Inject constructor(
    private val context: Context,
    private val holder: TunnelConfigHolder
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val keyConfig = stringPreferencesKey("tunnel_config_v1")

    val configFlow: Flow<TunnelConfig> = tunnelConfigFlow()

    private fun tunnelConfigFlow(): Flow<TunnelConfig> =
        context.tunnelDataStore.data.map { prefs ->
            val raw = prefs[keyConfig]
            if (raw.isNullOrBlank()) holder.config
            else runCatching { json.decodeFromString<TunnelConfig>(raw) }.getOrDefault(holder.config)
        }

    suspend fun save(config: TunnelConfig) {
        context.tunnelDataStore.edit { it[keyConfig] = json.encodeToString(config) }
        holder.update(config)
    }

    suspend fun current(): TunnelConfig = holder.config
}
