package id.maskiplay.net

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CapacitorPlugin(name = "Nmap")
class NmapPlugin : Plugin() {
    private val worker = Executors.newSingleThreadExecutor()

    @PluginMethod
    fun scan(call: PluginCall) {
        val host = call.getString("host")?.trim().orEmpty()
        val validIpv4 = host.matches(Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")) &&
            host.split(".").all { it.toIntOrNull() in 0..255 }
        val validHostname = HOST_PATTERN.matches(host) && !host.matches(Regex("""^[\d.]+$"""))
        if (!validIpv4 && !validHostname) {
            call.reject("Host tidak valid. Masukkan IPv4 atau hostname tanpa opsi tambahan.")
            return
        }
        worker.execute {
            var process: Process? = null
            try {
                val binary = extractBinary()
                val running = ProcessBuilder(
                    binary.absolutePath,
                    "-sT", "-Pn", "-T4", "-F",
                    "--host-timeout", "30s",
                    "-oX", "-", host
                ).redirectErrorStream(true).start()
                process = running
                if (!running.waitFor(35, TimeUnit.SECONDS)) {
                    running.destroyForcibly()
                    throw IllegalStateException("Scan melewati batas waktu 35 detik")
                }
                val output = running.inputStream.bufferedReader().use { it.readText() }
                if (running.exitValue() != 0 && !output.contains("<nmaprun")) {
                    throw IllegalStateException(output.ifBlank { "Nmap gagal dengan kode ${running.exitValue()}" })
                }
                if (!output.trimStart().startsWith("<?xml") && !output.contains("<nmaprun")) {
                    throw IllegalStateException(output.ifBlank { "Nmap tidak mengembalikan XML yang valid" })
                }
                call.resolve(JSObject().apply {
                    put("host", host)
                    put("xml", output)
                    put("exitCode", running.exitValue())
                })
            } catch (e: Exception) {
                call.reject(e.message ?: "Pemindaian Nmap gagal", e)
            } finally {
                process?.destroy()
            }
        }
    }

    private fun extractBinary(): File {
        val destination = File(context.filesDir, "nmap/nmap")
        if (destination.isFile && destination.canExecute()) return destination
        destination.parentFile?.mkdirs()
        try {
            context.assets.open("nmap/nmap").use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Binary Nmap arm64 belum dibundel di assets/nmap/nmap", e)
        }
        if (!destination.setExecutable(true, true) || !destination.canExecute()) {
            throw IllegalStateException("Binary Nmap tidak dapat dibuat executable")
        }
        return destination
    }

    override fun handleOnDestroy() {
        worker.shutdownNow()
        super.handleOnDestroy()
    }

    companion object {
        private val HOST_PATTERN = Regex("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$|^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    }
}
