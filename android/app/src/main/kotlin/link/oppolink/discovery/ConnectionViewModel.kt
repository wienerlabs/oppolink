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
 * Drives the Sprint 1 D3 connection screen. The shared [PeerConnector]
 * singleton owns the GATT session and exposes the live state; this VM only
 * coordinates [start] / [cancel] calls and the [Peer] argument bridged from
 * navigation.
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
