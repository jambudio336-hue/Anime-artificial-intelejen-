package ai.maskiplay.app

/**
 * Thin loader/status surface for the source-built OpenVPN 3 Core JNI library.
 * A loaded Core is not yet a connected VPN: the Android TunBuilder and
 * VpnService socket-protection session bridge must also be active.
 */
object OpenVpn3Native {
    @Volatile private var loadFailure: String? = null
    @Volatile private var loaded = false

    init {
        try {
            System.loadLibrary("maskiplay_openvpn3")
            loaded = true
        } catch (error: UnsatisfiedLinkError) {
            loadFailure = error.message ?: "native library unavailable"
        } catch (error: SecurityException) {
            loadFailure = error.message ?: "native library load blocked"
        }
    }

    external fun nativeCoreReady(): Boolean
    external fun nativeCoreInfo(): String

    fun status(): String {
        if (!loaded) return "OpenVPN 3 Core belum dimuat: ${loadFailure ?: "library tidak tersedia"}"
        return try {
            if (nativeCoreReady()) nativeCoreInfo()
            else "OpenVPN 3 Core dimuat, tetapi kapabilitas Android TUN belum tersedia"
        } catch (error: Throwable) {
            "OpenVPN 3 Core gagal diperiksa: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun isLoaded(): Boolean = loaded
}
