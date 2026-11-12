package id.maskiplay.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Vpn")
class VpnPlugin : Plugin() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var backend: GoBackend
    private lateinit var store: VpnStore
    @Volatile private var tunnel = MasTunnel("maskiplay")

    override fun load() {
        super.load()
        backend = GoBackend(context)
        store = VpnStore(context)
    }

    @PluginMethod
    fun prepare(call: PluginCall) {
        val intent = VpnService.prepare(activity)
        if (intent == null) {
            call.resolve(JSObject().putValue("granted", true))
        } else {
            startActivityForResult(call, intent, "vpnPermissionResult")
        }
    }

    @ActivityCallback
    private fun vpnPermissionResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        call.resolve(JSObject().putValue("granted", result.resultCode == Activity.RESULT_OK))
    }

    @PluginMethod
    fun up(call: PluginCall) = runWorker(call) {
        val configText = call.getString("config") ?: store.load()
            ?: throw IllegalArgumentException("Tempel konfigurasi WireGuard terlebih dahulu")
        val name = call.getString("name", "maskiplay") ?: "maskiplay"
        require(!Tunnel.isNameInvalid(name)) { "Nama tunnel tidak valid (1–15 karakter)" }
        if (VpnService.prepare(context) != null) throw IllegalStateException("Izin VPN belum diberikan; panggil prepare()")
        if (backend.getState(tunnel) == Tunnel.State.UP && tunnel.getName() != name) {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        }
        if (tunnel.getName() != name) tunnel = MasTunnel(name)
        store.save(configText)
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
        val state = backend.setState(tunnel, Tunnel.State.UP, config)
        if (state != Tunnel.State.UP) throw IllegalStateException("WireGuard tidak berhasil mengaktifkan tunnel")
        JSObject().putValue("status", "AKTIF").putValue("name", name)
    }

    @PluginMethod
    fun down(call: PluginCall) = runWorker(call) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        JSObject().putValue("status", "MATI")
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val up = try { backend.getState(tunnel) == Tunnel.State.UP } catch (_: Exception) { false }
        call.resolve(JSObject().putValue("active", up).putValue("status", if (up) "AKTIF" else "MATI"))
    }

    @PluginMethod
    fun genKey(call: PluginCall) {
        try {
            val pair = KeyPair()
            call.resolve(JSObject().putValue("privateKey", pair.privateKey.toBase64())
                .putValue("publicKey", pair.publicKey.toBase64()))
        } catch (e: Exception) { call.reject(e.message ?: "Gagal membuat keypair", e) }
    }

    private fun runWorker(call: PluginCall, action: () -> JSObject) {
        worker.execute {
            try { call.resolve(action()) }
            catch (e: Exception) { call.reject(e.message ?: "Kesalahan WireGuard", e) }
        }
    }

    override fun handleOnDestroy() {
        worker.shutdownNow()
        super.handleOnDestroy()
    }

    private fun JSObject.putValue(key: String, value: Any?): JSObject {
        put(key, value)
        return this
    }
}
