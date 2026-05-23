package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.oppolink_protocol.EchoStats
import uniffi.oppolink_protocol.Role
import uniffi.oppolink_protocol.buildEchoPacket
import uniffi.oppolink_protocol.echoPacketSize
import uniffi.oppolink_protocol.summarizeEchoSamples

/**
 * Orchestrates the Sprint 1 D3 handshake + D4 L2CAP echo test.
 *
 * **Role assignment** is UI-driven: whichever side taps a peer in the
 * discovery list takes the **client** role; the tapped side stays passive
 * with its [GattServerHost] already advertising the OppoLink service. The
 * deterministic tie-break in `oppolink-protocol::decide_role` exists for the
 * Sprint 4 D13 handshake where both peers exchange ephemeral pubkeys over an
 * already-open channel.
 *
 * State is exposed as a [StateFlow] for the UI; [connect] suspends until the
 * handshake either completes or fails. The pending connection is cancellable
 * via [cancel] — it aborts the GATT session and resets state to
 * [ConnectionState.Idle].
 */
interface PeerConnector {
    val state: StateFlow<ConnectionState>

    /**
     * Start the passive GATT-server side so a remote client can reach us at
     * any time. Idempotent; safe to call from the discovery screen lifecycle.
     * Also starts a background [Thread] that `accept()`s the inbound L2CAP
     * connection and runs the echo loop the client drives in Sprint 1 D4.
     */
    fun startPassiveServer(nickname: String)

    /** Tear down the passive GATT server (e.g. when discovery exits). */
    fun stopPassiveServer()

    /**
     * Start the client-side handshake against [peer]. Suspends until the
     * handshake yields a PSM, the connection fails, or the call is cancelled.
     */
    suspend fun connect(peer: Peer)

    /**
     * Run the L2CAP echo test against [peer]. Must be called after [connect]
     * has driven the state to [ConnectionState.PsmExchanged]. Returns the
     * RTT distribution and pushes [ConnectionState.EchoInProgress] /
     * [ConnectionState.EchoCompleted] onto the state flow.
     */
    suspend fun runEchoTest(peer: Peer, samples: Int = ECHO_DEFAULT_SAMPLES): EchoStats

    /** Abort an in-flight [connect] or [runEchoTest]. */
    fun cancel()

    companion object {
        /** Number of round-trips taken per echo test by default. */
        const val ECHO_DEFAULT_SAMPLES: Int = 10
    }
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
    private var passiveAcceptThread: Thread? = null

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
            .onSuccess {
                serverHost = host
                startPassiveAcceptThread(host)
            }
            .onFailure { Log.w(TAG, "passive GATT server failed to start: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun stopPassiveServer() {
        serverHost?.stop()
        serverHost = null
        passiveAcceptThread?.interrupt()
        passiveAcceptThread = null
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
                val result = client.fetchHandshake(peer)
                _state.value = ConnectionState.PsmExchanged(
                    role = Role.CLIENT,
                    peer = peer,
                    psm = result.handshake.psm.toInt() and 0xFFFF,
                    negotiatedPhy = result.negotiatedPhy,
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
    override suspend fun runEchoTest(peer: Peer, samples: Int): EchoStats {
        val (psm, phy) = when (val s = _state.value) {
            is ConnectionState.PsmExchanged -> s.psm to s.negotiatedPhy
            is ConnectionState.EchoCompleted -> s.psm to s.negotiatedPhy
            else -> error("Echo can only run after handshake; current=${s::class.simpleName}")
        }

        val socketRef = AtomicReference<BluetoothSocket?>(null)

        return suspendCancellableCoroutine { cont ->
            val thread = Thread({
                try {
                    val socket = client.openL2capSocket(peer, psm)
                    socketRef.set(socket)
                    val samplesUs = L2capChannel(socket).use { channel ->
                        runClientEchoLoop(channel, peer, psm, samples)
                    }
                    val stats = summarizeEchoSamples(samplesUs)
                    _state.value = ConnectionState.EchoCompleted(
                        role = Role.CLIENT,
                        peer = peer,
                        psm = psm,
                        negotiatedPhy = phy,
                        stats = stats,
                    )
                    if (cont.isActive) cont.resume(stats)
                } catch (t: Throwable) {
                    if (cont.isCancelled) return@Thread
                    Log.w(TAG, "client echo loop failed: ${t.message}")
                    _state.value = ConnectionState.Failed(
                        peer = peer,
                        reason = t.message ?: "echo failed",
                        role = Role.CLIENT,
                    )
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }, "OppoLinkEchoClient")
            // URGENT_AUDIO is a Process-level priority; here we approximate
            // with the highest JVM Thread priority. The Sprint 2 audio path
            // will switch to android.os.Process.setThreadPriority instead.
            thread.priority = Thread.MAX_PRIORITY
            thread.start()
            cont.invokeOnCancellation {
                runCatching { socketRef.get()?.close() }
                thread.interrupt()
            }
        }
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
        passiveAcceptThread?.interrupt()
        passiveAcceptThread = null
        runCatching { serverHost?.stop() }
        serverHost = null
    }

    private fun startPassiveAcceptThread(host: GattServerHost) {
        passiveAcceptThread?.interrupt()
        passiveAcceptThread = Thread({
            // accept() blocks until a client connects or stop() closes us.
            val socket = host.acceptL2cap() ?: return@Thread
            try {
                L2capChannel(socket).use { channel ->
                    runServerEchoLoop(channel)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server echo loop failed: ${t.message}")
            }
        }, "OppoLinkAccept").apply {
            // Approximate URGENT_AUDIO; Sprint 2 will move to Process.setThreadPriority.
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Run the echo loop on the server side: reads a 1024-byte packet, writes
     * it back, repeat until the socket closes. Returns on the first read
     * failure — the client owns the lifecycle.
     */
    private fun runServerEchoLoop(channel: L2capChannel) {
        val buffer = ByteArray(echoPacketSize().toInt())
        while (true) {
            try {
                channel.receiveExact(buffer)
                channel.send(buffer)
            } catch (e: IOException) {
                Log.i(TAG, "server echo loop closing: ${e.message}")
                return
            }
        }
    }

    /**
     * Drive [samples] round-trips on the client side, surfacing progress
     * onto the state flow as we go. Returns the per-sample RTT in
     * microseconds (as `List<UInt>` so we can hand it straight to
     * [summarizeEchoSamples]).
     */
    private fun runClientEchoLoop(
        channel: L2capChannel,
        peer: Peer,
        psm: Int,
        samples: Int,
    ): List<UInt> {
        val samplesUs = ArrayList<UInt>(samples)
        val rxBuffer = ByteArray(echoPacketSize().toInt())
        for (i in 0 until samples) {
            _state.value = ConnectionState.EchoInProgress(
                role = Role.CLIENT,
                peer = peer,
                psm = psm,
                progress = i,
                total = samples,
            )
            val packet = buildEchoPacket(i.toUInt())
            val startNs = System.nanoTime()
            channel.send(packet)
            channel.receiveExact(rxBuffer)
            val elapsedUs = (System.nanoTime() - startNs) / 1_000L
            samplesUs.add(elapsedUs.toUInt())
        }
        return samplesUs
    }

    private companion object {
        const val TAG = "PeerConnector"
    }
}
