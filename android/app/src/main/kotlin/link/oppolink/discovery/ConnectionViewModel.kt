package link.oppolink.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import link.oppolink.bluetooth.ConnectionState
import link.oppolink.bluetooth.Peer
import link.oppolink.bluetooth.PeerConnector

/**
 * Drives the Sprint 1 D3 connection screen and the D4 L2CAP echo test.
 *
 * The shared [PeerConnector] singleton owns the GATT session + L2CAP server
 * socket and publishes the live state; this VM only coordinates [start] /
 * [runEcho] / [cancel] calls and threads the [Peer] argument through.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connector: PeerConnector,
) : ViewModel() {

    val state: StateFlow<ConnectionState> = connector.state

    private var inflight: Job? = null

    fun start(peer: Peer) {
        inflight?.cancel()
        inflight = viewModelScope.launch {
            connector.connect(peer)
        }
    }

    /** Kick off the L2CAP echo test once handshake (PsmExchanged) is done. */
    fun runEcho(peer: Peer) {
        inflight?.cancel()
        inflight = viewModelScope.launch {
            runCatching { connector.runEchoTest(peer) }
                // PeerConnector pushes its own Failed state on error — the
                // result of this launch is informational only.
                .onFailure { /* state flow already reflects failure */ }
        }
    }

    fun cancel() {
        inflight?.cancel()
        inflight = null
        connector.cancel()
    }

    override fun onCleared() {
        inflight?.cancel()
        connector.cancel()
        super.onCleared()
    }
}
