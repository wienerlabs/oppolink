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
 * Drives the Sprint 1 D3 connection screen and the Sprint 2 D5 one-way
 * call. The shared [PeerConnector] singleton owns the GATT session +
 * L2CAP server socket and publishes the live state; this VM only
 * coordinates [start] / [startCall] / [cancel] calls.
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

    /** Kick off the one-way call once handshake (PsmExchanged) is done. */
    fun startCall(peer: Peer) {
        inflight?.cancel()
        inflight = viewModelScope.launch {
            runCatching { connector.runCall(peer) }
                // PeerConnector pushes its own Failed state on error.
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
