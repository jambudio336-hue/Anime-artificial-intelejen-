package id.maskiplay.vpn

import com.wireguard.android.backend.Tunnel

class MasTunnel(
    private val name: String,
    private val stateListener: (Tunnel.State) -> Unit = {}
) : Tunnel {
    @Volatile private var currentState: Tunnel.State = Tunnel.State.DOWN

    override fun getName(): String = name

    override fun onStateChange(newState: Tunnel.State) {
        currentState = newState
        stateListener(newState)
    }

    fun state(): Tunnel.State = currentState
}
