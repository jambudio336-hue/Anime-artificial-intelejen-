package ai.maskiplay.app

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.max

/**
 * VPN Gate's public CSV directory reader.
 *
 * This class discovers and ranks OpenVPN-capable volunteer relays only.
 * It deliberately does not claim to establish an OpenVPN tunnel.
 */
object VpnGateRepository {
    private const val CSV_URL = "https://www.vpngate.net/api/iphone/"
    private const val MAX_CSV_BYTES = 5 * 1024 * 1024
    private const val MAX_PROFILE_BYTES = 1024 * 1024
    private const val MAX_CANDIDATES = 24

    data class Relay(
        val hostName: String,
        val ip: String,
        val country: String,
        val countryCode: String,
        val score: Long,
        val reportedPingMs: Long,
        val probeMs: Long?,
        val speedMbps: Double,
        val sessions: Int,
        val uptimeSeconds: Long,
        val logType: String,
        val operator: String,
        val port: Int,
        val transport: String,
        val profileAvailable: Boolean
    ) {
        fun rankingLatency(): Long = probeMs ?: reportedPingMs.takeIf { it >= 0 } ?: 9999L
        fun rankingScore(): Double {
            val latency = rankingLatency().coerceIn(1L, 5000L).toDouble()
            val throughputPenalty = 900.0 / max(1.0, speedMbps)
            val loadPenalty = sessions.coerceAtLeast(0) * 0.35
            val stabilityBonus = (uptimeSeconds.coerceAtLeast(0L) / 86400.0).coerceAtMost(14.0)
            return latency + throughputPenalty + loadPenalty - stabilityBonus
        }
    }

    fun fetchAndRank(): List<Relay> {
        val connection = (URL(CSV_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/csv,*/*")
            setRequestProperty("User-Agent", "MasKiplay-Android/9")
        }
        val csv = try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("VPN Gate HTTP $status")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CSV_BYTES) throw IllegalStateException("Daftar VPN Gate terlalu besar")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }

        val rows = csv.lineSequence().filter { it.isNotBlank() }.map { parseCsvLine(it) }.toList()
        val headerIndex = rows.indexOfFirst { row ->
            val names = row.map { it.trim().removePrefix("#").lowercase() }
            names.contains("hostname") && names.contains("ip") &&
                names.contains("openvpn_configdata_base64")
        }
        if (headerIndex < 0) throw IllegalStateException("Header CSV VPN Gate tidak ditemukan")

        val headers = rows[headerIndex].mapIndexed { index, value ->
            val normalized = value.trim().removePrefix("#").trim().lowercase()
            normalized to index
        }.toMap()

        fun value(row: List<String>, name: String): String =
            row.getOrNull(headers[name] ?: -1)?.trim().orEmpty()

        val parsed = rows.drop(headerIndex + 1).mapNotNull { row ->
            try {
                val encoded = value(row, "openvpn_configdata_base64")
                if (encoded.isBlank()) return@mapNotNull null
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isEmpty() || bytes.size > MAX_PROFILE_BYTES) return@mapNotNull null
                val profile = String(bytes, StandardCharsets.UTF_8)
                val remote = profile.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("remote ", ignoreCase = true) }
                    ?: return@mapNotNull null
                val parts = remote.split(Regex("\\s+"))
                val endpointHost = parts.getOrNull(1)?.trim() ?: return@mapNotNull null
                val endpointPort = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: return@mapNotNull null
                val transport = parts.getOrNull(3)?.lowercase()?.let {
                    if (it.startsWith("tcp")) "TCP" else "UDP"
                } ?: "UDP"
                val ip = value(row, "ip")
                if (!isIpv4(ip)) return@mapNotNull null
                Relay(
                    hostName = value(row, "hostname").take(120),
                    ip = ip,
                    country = value(row, "countrylong").take(80),
                    countryCode = value(row, "countryshort").take(8),
                    score = value(row, "score").toLongOrNull() ?: 0L,
                    reportedPingMs = value(row, "ping").toLongOrNull() ?: -1L,
                    probeMs = if (transport == "TCP") probeTcp(endpointHost, endpointPort) else null,
                    speedMbps = ((value(row, "speed").toDoubleOrNull() ?: 0.0) / 1_000_000.0).coerceAtLeast(0.0),
                    sessions = value(row, "numvpnsessions").toIntOrNull() ?: 0,
                    uptimeSeconds = value(row, "uptime").toLongOrNull() ?: 0L,
                    logType = value(row, "logtype").take(40),
                    operator = value(row, "operator").take(100),
                    port = endpointPort,
                    transport = transport,
                    profileAvailable = profile.contains("client", ignoreCase = true) &&
                        profile.contains("remote ", ignoreCase = true)
                )
            } catch (_: Exception) {
                null
            }
        }.filter { it.profileAvailable && it.port in 1..65535 }
            .sortedBy { it.rankingScore() }
            .take(MAX_CANDIDATES)

        return parsed
    }

    private fun probeTcp(host: String, port: Int): Long? {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 1200)
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: Exception) {
            null
        }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                (part.toIntOrNull() ?: 256) in 0..255
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result.add(field.toString()); field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        result.add(field.toString())
        return result
    }
}
