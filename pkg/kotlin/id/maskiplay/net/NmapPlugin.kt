package id.maskiplay.net

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

/** Original, unprivileged TCP connect scanner. Does not bundle or invoke Nmap. */
@CapacitorPlugin(name = "NetworkScanner")
class NetworkScannerPlugin : Plugin() {
    private val worker = Executors.newSingleThreadExecutor()

    @PluginMethod
    fun scan(call: PluginCall) {
        val host = call.getString("host")?.trim().orEmpty()
        val validIpv4 = host.matches(Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")) &&
            host.split(".").all { it.toIntOrNull() in 0..255 }
        val validHostname = HOST_PATTERN.matches(host) && !host.matches(Regex("""^[\d.]+$"""))
        if (!validIpv4 && !validHostname) {
            call.reject("Host tidak valid. Masukkan satu alamat IPv4 atau hostname.")
            return
        }

        worker.execute {
            try {
                val address = InetAddress.getByName(host)
                val openPorts = mutableListOf<Pair<Int, String>>()
                val elapsed = measureTimeMillis {
                    for (port in COMMON_PORTS) {
                        val socket = Socket()
                        try {
                            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                            openPorts += port to (SERVICES[port] ?: "unknown")
                        } catch (_: Exception) {
                            // Closed, filtered, or unreachable ports are not reported as open.
                        } finally {
                            try { socket.close() } catch (_: Exception) { }
                        }
                    }
                }
                val portArray = JSArray()
                openPorts.forEach { (port, service) ->
                    portArray.put(JSObject().apply {
                        put("port", port)
                        put("protocol", "tcp")
                        put("state", "open")
                        put("service", service)
                    })
                }
                call.resolve(JSObject().apply {
                    put("host", host)
                    put("address", address.hostAddress ?: host)
                    put("ports", portArray)
                    put("openCount", openPorts.size)
                    put("scannedPorts", COMMON_PORTS.size)
                    put("durationMs", elapsed)
                    put("method", "TCP connect")
                })
            } catch (e: Exception) {
                call.reject("Pemindaian gagal: " + (e.message ?: "host tidak dapat dijangkau"), e)
            }
        }
    }

    override fun handleOnDestroy() {
        worker.shutdownNow()
        super.handleOnDestroy()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 250
        private val COMMON_PORTS = intArrayOf(
            21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445, 465, 587,
            993, 995, 1433, 1521, 1723, 1883, 2049, 2181, 2375, 3000, 3306, 3389,
            3690, 4444, 5000, 5432, 5601, 5900, 5985, 6379, 6443, 7001, 8000,
            8008, 8080, 8081, 8088, 8443, 8888, 9000, 9090, 9200, 9300, 10000,
            11211, 27017
        )
        private val SERVICES = mapOf(
            21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
            80 to "http", 110 to "pop3", 111 to "rpcbind", 135 to "msrpc",
            139 to "netbios", 143 to "imap", 443 to "https", 445 to "smb",
            465 to "smtps", 587 to "submission", 993 to "imaps", 995 to "pop3s",
            1433 to "mssql", 1521 to "oracle", 1723 to "pptp", 1883 to "mqtt",
            2049 to "nfs", 2181 to "zookeeper", 2375 to "docker", 3000 to "http-alt",
            3306 to "mysql", 3389 to "rdp", 3690 to "svn", 4444 to "http-alt",
            5000 to "http-alt", 5432 to "postgresql", 5601 to "kibana",
            5900 to "vnc", 5985 to "winrm", 6379 to "redis", 6443 to "kubernetes",
            7001 to "weblogic", 8000 to "http-alt", 8008 to "http-alt",
            8080 to "http-proxy", 8081 to "http-alt", 8088 to "http-alt",
            8443 to "https-alt", 8888 to "http-alt", 9000 to "http-alt",
            9090 to "http-alt", 9200 to "elasticsearch", 9300 to "elasticsearch",
            10000 to "webmin", 11211 to "memcached", 27017 to "mongodb"
        )
        private val HOST_PATTERN = Regex(
            """(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"""
        )
    }
}
