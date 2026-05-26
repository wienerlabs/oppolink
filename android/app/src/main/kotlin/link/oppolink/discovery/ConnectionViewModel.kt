package link.oppolink.discovery

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import link.oppolink.bluetooth.AudioLevels
import link.oppolink.bluetooth.ConnectionState
import link.oppolink.bluetooth.Peer
import link.oppolink.bluetooth.PeerConnector
import link.oppolink.service.CallForegroundService

/**
 * Drives the connection screen + the Sprint 3 D9 foreground-service-backed
 * call session.
 *
 * The shared [PeerConnector] singleton owns the GATT session, the L2CAP
 * server socket, and (now) the audio pipeline. This ViewModel only
 * coordinates [start], [startCall] (which fires up
 * [CallForegroundService]), and [cancel].
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connector: PeerConnector,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val state: StateFlow<ConnectionState> = connector.state
    val audioLevels: StateFlow<AudioLevels> = connector.audioLevels

    private var handshakeJob: Job? = null

    fun start(peer: Peer) {
        handshakeJob?.cancel()
        handshakeJob = viewModelScope.launch {
            connector.connect(peer)
        }
    }

    /**
     * Kick off the foreground call session once the handshake has put us
     * in `PsmExchanged`. The service reads the current peer from
     * `connector.state.value` so we don't need to parcel `Peer` through
     * an Intent.
     */
    fun startCall() {
        ContextCompat.startForegroundService(
            appContext,
            CallForegroundService.startIntent(appContext),
        )
    }

    /**
     * Sprint 4 D12 - toggle the local mic. Plumbed through PeerConnector
     * which owns the AudioCapture lifecycle. Idempotent; safe to call on
     * every press / release of the push-to-talk button.
     */
    fun setMuted(muted: Boolean) {
        connector.setMuted(muted)
    }

    fun cancel() {
        handshakeJob?.cancel()
        handshakeJob = null
        // Stop the foreground service first so its own onDestroy doesn't
        // race with the connector teardown.
        appContext.startService(CallForegroundService.stopIntent(appContext))
        connector.cancel()
    }

    override fun onCleared() {
        handshakeJob?.cancel()
        // We do NOT cancel the connector or stop the service here; the
        // call must survive the activity being recreated (rotation,
        // screen-off + screen-on). The user explicitly hangs up via the
        // notification or the "End call" UI button.
        super.onCleared()
    }
}
