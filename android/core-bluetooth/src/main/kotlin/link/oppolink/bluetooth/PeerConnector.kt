package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.oppolink_protocol.Role

/**
 * Orchestrates the Sprint 1 D3 peer-connection lifecycle.
 *
 * **Role assignment** is UI-driven: whichever side taps a peer in the
 * discovery list takes the **client** role; the tapped side stays passive
 * with its [GattServerHost] already advertising the OppoLink service. We
 * deliberately don't lean on local BD_ADDR comparison — Android 8+ refuses
 * to surface the local address to apps, and the deterministic tie-break in
 * `oppolink-protocol::decide_role` exists for the Sprint 4 D13 handshake
 * where both peers exchange ephemeral pubkeys over an already-open channel.
 *
 * State is exposed as a [StateFlow] for the UI; [connect] suspends until the
 * handshake either completes or fails. The pending connection is cancellable
 * via [cancel] — it aborts the GATT session and resets state to [ConnectionState.Idle].
 */
interface PeerConnector {
    val state: StateFlow<ConnectionState>

    /**
     * Start the passive GATT-server side so a remote client can reach us at
     * any time. Idempotent; safe to call from the discovery screen lifecycle.
     */
    fun startPassiveServer(nickname: String)

    /** Tear down the passive GATT server (e.g. when discovery exits). */
    fun stopPassiveServer()

    /**
     * Start the client-side handshake against [peer]. Suspends until the
     * handshake yields a PSM, the connection fails, or the call is cancelled.
     */
    suspend fun connect(peer: Peer)

    /** Abort an in-flight [connect] and tear the GATT session down. */
    fun cancel()
}

internal class RealPeerConnector(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val manager: BluetoothManager,
) : PeerConnector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val client = GattClient(context, adapter)
    private var serverHost: GattServerHost? = null
    private var connectJob: Job? = null

    /**
     * Idempotent passive listener: ensures the local peer keeps a GATT server
     * + L2CAP listening socket up while the app is in the foreground, ready
     * for any inbound client. Called by the discovery screen.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun startPassiveServer(nickname: String) {
        if (serverHost != null) return
        val host = GattServerHost(context, adapter, manager)
        runCatching { host.start(nickname) }
            .onSuccess { serverHost = host }
            .onFailure { Log.w(TAG, "passive GATT server failed to start: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun stopPassiveServer() {
        serverHost?.stop()
        serverHost = null
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun connect(peer: Peer) {
        // Cancel any in-flight attempt before starting a new one.
        connectJob?.cancel()

        val job = scope.launch {
            _state.value = ConnectionState.RoleDecided(Role.CLIENT, peer)
            _state.value = ConnectionState.Connecting(Role.CLIENT, peer)
            try {
                _state.value = ConnectionState.Handshaking(Role.CLIENT, peer)
                val handshake = client.fetchHandshake(peer)
                _state.value = ConnectionState.PsmExchanged(
                    role = Role.CLIENT,
                    peer = peer,
                    psm = handshake.psm.toInt() and 0xFFFF,
                )
            } catch (t: Throwable) {
                _state.value = ConnectionState.Failed(
                    peer = peer,
                    reason = t.message ?: "Unknown GATT failure",
                    role = Role.CLIENT,
                )
            }
        }
        connectJob = job
        job.join()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun cancel() {
        connectJob?.cancel()
        connectJob = null
        _state.value = ConnectionState.Idle
    }

    /** Drop coroutine scope; should be called from the DI graph on tear-down. */
    fun shutdown() {
        scope.cancel()
        runCatching { serverHost?.stop() }
        serverHost = null
    }

    private companion object {
        const val TAG = "PeerConnector"
    }
}
