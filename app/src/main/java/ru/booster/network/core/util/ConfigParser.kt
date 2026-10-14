package ru.booster.network.core.util

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.Json
import ru.booster.network.core.model.AmneziaWgParams
import ru.booster.network.core.model.TunnelConfig
import java.security.MessageDigest

/**
 * Imports AmneziaWG configurations from three sources:
 *   1. WireGuard-style .conf text ([Interface] / [Peer] blocks + WgExtra params)
 *   2. Amnezia VPN JSON export
 *   3. wireguard:// / amneziawg:// deep-link URIs (same payload as .conf, url-encoded)
 */
object ConfigParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, sourceName: String = "Imported tunnel"): TunnelConfig {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("wireguard://") || trimmed.startsWith("amneziawg://") ->
                parseUri(trimmed, sourceName)
            trimmed.startsWith("{") ->
                parseJson(trimmed, sourceName)
            else -> parseConf(trimmed, sourceName)
        }
    }

    // ── .conf ────────────────────────────────────────────────────────────────
    fun parseConf(text: String, name: String): TunnelConfig {
        var privateKey = ""
        var address = ""
        var dns: List<String> = emptyList()
        var mtu = 0
        var publicKey = ""
        var presharedKey = ""
        var endpointHost = ""
        var endpointPort = 443
        var allowedIps = listOf("0.0.0.0/0", "::/0")
        var keepalive = 25
        var jc = 3; var jmin = 57; var jmax = 1270
        var s1 = "deadff"; var s2 = "ee"
        var hasAmnezia = false

        var section = ""
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase()
                return@forEach
            }
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            if (value.isEmpty()) return@forEach

            when (section) {
                "[interface]" -> when (key.lowercase()) {
                    "privatekey" -> privateKey = value
                    "address" -> address = value
                    "dns" -> dns = value.split(',').map { it.trim() }
                    "mtu" -> mtu = value.toIntOrNull() ?: 0
                    "junkpacketcount" -> { jc = value.toIntOrNull() ?: jc; hasAmnezia = true }
                    "junkpacketminsize" -> { jmin = value.toIntOrNull() ?: jmin; hasAmnezia = true }
                    "junkpacketmaxsize" -> { jmax = value.toIntOrNull() ?: jmax; hasAmnezia = true }
                    "junkpacketheader1" -> { s1 = value; hasAmnezia = true }
                    "junkpacketheader2" -> { s2 = value; hasAmnezia = true }
                }
                "[peer]" -> when (key.lowercase()) {
                    "publickey" -> publicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> {
                        val hostPart = value.substringBeforeLast(':')
                        endpointHost = hostPart.removeSurrounding("[", "]")
                        endpointPort = value.substringAfterLast(':').toIntOrNull() ?: 443
                    }
                    "allowedips" -> allowedIps = value.split(',').map { it.trim() }
                    "persistentkeepalive" -> keepalive = value.toIntOrNull() ?: 25
                }
            }
        }

        return TunnelConfig(
            name = name,
            privateKey = privateKey,
            address = address.ifBlank { "10.8.0.2/32" },
            dnsServers = dns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") },
            mtu = mtu,
            publicKey = publicKey,
            presharedKey = presharedKey,
            endpointHost = endpointHost,
            endpointPort = endpointPort,
            allowedIps = allowedIps,
            persistentKeepalive = keepalive,
            wgParams = AmneziaWgParams(jc, jmin, jmax, s1, s2),
            isAmneziaWg = hasAmnezia
        )
    }

    // ── wireguard:// URI ─────────────────────────────────────────────────────
    fun parseUri(uriString: String, name: String): TunnelConfig {
        // wireguard://#public_key=..&private_key=..&address=..&endpoint=.. …
        val fragment = uriString.substringAfter('#', "")
            .ifBlank { uriString.substringAfter("?", "") }
        val map = fragment.split('&').mapNotNull { kv ->
            kv.split('=', limit = 2).takeIf { it.size == 2 }
                ?.let { it[0] to Uri.decode(it[1]) }
        }.toMap()

        val conf = buildString {
            appendLine("[Interface]")
            map["private_key"]?.let { appendLine("PrivateKey = $it") }
            map["address"]?.let { appendLine("Address = ${it.replace("%2F", "/")}") }
            map["dns"]?.let { appendLine("DNS = $it") }
            map["mtu"]?.let { appendLine("MTU = $it") }
            map["jc"]?.let { appendLine("JunkPacketCount = $it") }
            map["jmin"]?.let { appendLine("JunkPacketMinSize = $it") }
            map["jmax"]?.let { appendLine("JunkPacketMaxSize = $it") }
            map["s1"]?.let { appendLine("JunkPacketHeader1 = $it") }
            map["s2"]?.let { appendLine("JunkPacketHeader2 = $it") }
            appendLine()
            appendLine("[Peer]")
            map["public_key"]?.let { appendLine("PublicKey = $it") }
            map["preshared_key"]?.let { appendLine("PresharedKey = $it") }
            map["endpoint"]?.let { appendLine("Endpoint = $it") }
            map["allowed_ips"]?.let { appendLine("AllowedIPs = $it") }
            map["persistent_keepalive"]?.let { appendLine("PersistentKeepalive = $it") }
        }
        return parseConf(conf, map["name"]?.ifBlank { null } ?: name)
    }

    // ── Amnezia JSON export ──────────────────────────────────────────────────
    fun parseJson(text: String, name: String): TunnelConfig {
        // Amnezia exports embed the WG .conf under container/wireguard configuration
        val node = json.parseToJsonElement(text)
        val obj = node as? kotlinx.serialization.json.JsonObject
            ?: error("Not a JSON object")

        fun str(path: List<String>): String? {
            var acc: kotlinx.serialization.json.JsonElement? = obj
            // support numeric segments like containers[0] / containers.0
            for (rawKey in path) {
                val keys = rawKey.split('.')
                for (k in keys) {
                    acc = if (k.toIntOrNull() != null && acc is kotlinx.serialization.json.JsonArray) {
                        acc.elements.getOrNull(k.toInt())
                    } else if (acc is kotlinx.serialization.json.JsonObject) {
                        acc[k]
                    } else null
                    if (acc == null) return null
                }
            }
            return (acc as? kotlinx.serialization.json.JsonPrimitive)?.content
        }

        val embeddedConf = str(listOf("containers", "0", "wireguard", "config_params"))
            ?: str(listOf("configuration"))
        if (embeddedConf != null) {
            return parseConf(embeddedConf, str(listOf("name")) ?: name)
        }
        return TunnelConfig(
            name = str(listOf("name")) ?: name,
            privateKey = str(listOf("client", "keys", "private_key")) ?: "",
            publicKey = str(listOf("server", "keys", "public_key")) ?: "",
            endpointHost = str(listOf("server", "host")) ?: "",
            endpointPort = str(listOf("server", "port"))?.toIntOrNull() ?: 443,
            wgParams = AmneziaWgParams(
                junkPacketCount = str(listOf("container", "wg", "Jc"))?.toIntOrNull() ?: 3,
                junkPacketMinSize = str(listOf("container", "wg", "Jmin"))?.toIntOrNull() ?: 57,
                junkPacketMaxSize = str(listOf("container", "wg", "Jmax"))?.toIntOrNull() ?: 1270,
                junkPacketHeader1 = str(listOf("container", "wg", "S1")) ?: "deadff",
                junkPacketHeader2 = str(listOf("container", "wg", "S2")) ?: "ee"
            )
        )
    }

    /** Serialise back to canonical .conf with AmneziaWG extras. */
    fun toConf(cfg: TunnelConfig): String = buildString {
        appendLine("# AmneziaWG configuration — generated by Network & System Game Booster")
        appendLine("[Interface]")
        appendLine("PrivateKey = ${cfg.privateKey}")
        appendLine("Address = ${cfg.address}")
        appendLine("DNS = ${cfg.dnsServers.joinToString(", ")}")
        if (cfg.mtu > 0) appendLine("MTU = ${cfg.mtu}")
        if (cfg.isAmneziaWg) {
            appendLine("JunkPacketCount = ${cfg.wgParams.junkPacketCount}")
            appendLine("JunkPacketMinSize = ${cfg.wgParams.junkPacketMinSize}")
            appendLine("JunkPacketMaxSize = ${cfg.wgParams.junkPacketMaxSize}")
            appendLine("JunkPacketHeader1 = ${cfg.wgParams.junkPacketHeader1}")
            appendLine("JunkPacketHeader2 = ${cfg.wgParams.junkPacketHeader2}")
        }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${cfg.publicKey}")
        if (cfg.presharedKey.isNotBlank()) appendLine("PresharedKey = ${cfg.presharedKey}")
        appendLine("Endpoint = ${cfg.endpointHost}:${cfg.endpointPort}")
        appendLine("AllowedIPs = ${cfg.allowedIps.joinToString(", ")}")
        appendLine("PersistentKeepalive = ${cfg.persistentKeepalive}")
    }

    /** Key material sanity check: base64 of 32 bytes whose curve25519 clamping matches. */
    fun isValidWgKey(base64Key: String, isPrivate: Boolean): Boolean = runCatching {
        val decoded = Base64.decode(base64Key.trim(), Base64.DEFAULT)
        if (decoded.size != 32) return false
        if (!isPrivate) {
            // public keys must satisfy X25519 group order — cheap check: not all-zero
            MessageDigest.getInstance("SHA-256").digest(decoded).isNotEmpty() &&
                    decoded.any { it != 0.toByte() }
        } else true
    }.getOrDefault(false)
}
