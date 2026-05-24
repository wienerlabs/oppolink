package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import link.oppolink.audio.AudioCapture
import link.oppolink.audio.AudioPlayback
import uniffi.oppolink_protocol.AudioFrameHeader
import uniffi.oppolink_protocol.Capabilities
import uniffi.oppolink_protocol.JitterBuffer
import uniffi.oppolink_protocol.JitterPopResult
import uniffi.oppolink_protocol.Role
import uniffi.oppolink_protocol.VoipDecoder
import uniffi.oppolink_protocol.VoipEncoder
import uniffi.oppolink_protocol.buildAudioFrame
import uniffi.oppolink_protocol.parseAudioFrame
import uniffi.oppolink_protocol.pcmSamplesPerFrame

/**
 * Orchestrates handshake + full-duplex call lifecycle.
 *
 * **Role assignment** is UI-driven: whichever side taps a peer in the
 * discovery list takes the **client** role; the tapped side stays passive
 * with its [GattServerHost] already advertising the OppoLink service.
 *
 * **Sprint 2 D6** moves both sides to a symmetric duplex pipeline. Each
 * device runs a `Tx` thread (`AudioRecord` → libopus → L2CAP write) and
 * an `Rx` thread (L2CAP read → libopus → `AudioTrack`) in parallel. Both
 * threads run at `Process.THREAD_PRIORITY_URGENT_AUDIO`. The L2CAP socket
 * is shared but `BluetoothSocket.inputStream` and `outputStream` are
 * independent at the OS level — see [L2capChannel] for the contract.
 */
interface PeerConnector {
    val state: StateFlow<ConnectionState>

    /** Start the passive GATT-server + accept thread. Idempotent. */
    fun startPassiveServer(nickname: String)

    /** Tear down the passive GATT server (e.g. when discovery exits). */
    fun stopPassiveServer()

    /** Client-side handshake against [peer]; advances state through to PsmExchanged. */
    suspend fun connect(peer: Peer)

    /**
     * Drive a full-duplex call.
     *
     * `durationMs = null` runs indefinitely until [cancel] or the peer
     * closes the socket — that's what the Sprint 3 D9 foreground service
     * passes. A non-null value caps the client-side Tx loop and is useful
     * for the in-app "10 second test call" affordance still on the
     * "Start full-duplex call" button.
     */
    suspend fun runCall(peer: Peer, durationMs: Long? = null)

    /** Abort an in-flight call. */
    fun cancel()

    companion object {
        /** Test-call duration the in-app button uses (10 seconds). */
        const val SHORT_TEST_CALL_DURATION_MS: Long = 10_000L
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
    override suspend fun runCall(peer: Peer, durationMs: Long?) {
        val (psm, phy) = when (val s = _state.value) {
            is ConnectionState.PsmExchanged -> s.psm to s.negotiatedPhy
            is ConnectionState.CallEnded -> s.psm to 0
            else -> error("Call can only start after handshake; current=${s::class.simpleName}")
        }
        val socketRef = AtomicReference<BluetoothSocket?>(null)

        suspendCancellableCoroutine<Unit> { cont ->
            val thread = Thread({
                try {
                    val socket = client.openL2capSocket(peer, psm)
                    socketRef.set(socket)
                    runDuplexCall(
                        socket = socket,
                        role = Role.CLIENT,
                        peer = peer,
                        psm = psm,
                        phy = phy,
                        durationMs = durationMs,
                    )
                    if (cont.isActive) cont.resume(Unit)
                } catch (t: Throwable) {
                    if (cont.isCancelled) return@Thread
                    Log.w(TAG, "client duplex call failed: ${t.message}")
                    _state.value = ConnectionState.Failed(
                        peer = peer,
                        reason = t.message ?: "call failed",
                        role = Role.CLIENT,
                    )
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }, "OppoLinkCallOrchestrator")
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
            // Accept loop (Sprint 3 D11). After a SocketLost we drop back
            // here and wait for the client to reconnect — `runDuplexCall`
            // on the server side does not reconnect itself; the listening
            // socket on `GattServerHost` is what the client re-opens
            // against.
            while (!Thread.currentThread().isInterrupted) {
                val socket = host.acceptL2cap() ?: break
                val remotePeer = synthesizeRemotePeer(socket)
                try {
                    runDuplexCall(
                        socket = socket,
                        role = Role.SERVER,
                        peer = remotePeer,
                        psm = host.psm(),
                        phy = 0, // PHY readback only runs on the client during fetchHandshake
                        durationMs = null,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "server duplex call failed: ${t.message}")
                }
            }
        }, "OppoLinkAccept").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Build a stand-in [Peer] for the server side that does not have a
     * discovery-time advertisement to draw from. RSSI 0 + default capability
     * bitmap is good enough for state display; real values arrive once
     * Sprint 4 D13 lands and both peers exchange identifiers over the
     * already-open L2CAP channel.
     */
    @SuppressLint("MissingPermission")
    private fun synthesizeRemotePeer(socket: BluetoothSocket): Peer {
        val device = socket.remoteDevice
        val nickname = runCatching { device?.name }.getOrNull() ?: "(remote)"
        val address = device?.address ?: "00:00:00:00:00:00"
        return Peer(
            bdAddress = address,
            nickname = nickname,
            rssi = 0,
            capabilities = Capabilities(pcm16kMono = true, opus = true, aead = false),
            lastSeenAtMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Reason a single duplex session exited. Surfaced via an
     * `AtomicReference` from the Tx / Rx threads so the outer reconnect
     * loop in [runDuplexCall] can decide whether to retry.
     */
    private enum class SessionEndReason {
        /** Tx finished cleanly — duration elapsed or [running] flipped. */
        Normal,

        /** Tx or Rx hit an `IOException` — the L2CAP socket is gone. */
        SocketLost,
    }

    /**
     * Symmetric duplex pipeline shared by client and server.
     *
     * Three dedicated [Thread]s run at `URGENT_AUDIO`:
     *   - **Tx** captures PCM, encodes Opus, length-prefixes and writes the
     *     frame to L2CAP.
     *   - **Rx** reads framed bytes off L2CAP, parses, and pushes the Opus
     *     packet into a [JitterBuffer] keyed by `seq`.
     *   - **Playback** ticks against the jitter buffer: `pop_next()` yields
     *     a packet to decode, a `Plc` request, or `Empty` during prewarm.
     *
     * **Reconnect** (Sprint 3 D11): the outer loop owns audio +
     * encoder/decoder + jitter buffer lifecycles, the inner
     * [runDuplexSession] owns the socket-bound threads. On
     * [SessionEndReason.SocketLost] (client role only), the outer loop
     * tries [attemptReconnect] for up to [RECONNECT_DEADLINE_MS]. Server
     * side relies on the passive accept loop to pick the reconnect up.
     */
    @SuppressLint("MissingPermission")
    private fun runDuplexCall(
        socket: BluetoothSocket,
        role: Role,
        peer: Peer,
        psm: Int,
        phy: Int,
        durationMs: Long?,
    ) {
        val capture = AudioCapture()
        val playback = AudioPlayback()
        val framesSent = AtomicInteger(0)
        val framesReceived = AtomicInteger(0)
        val callDeadlineNs: Long? = durationMs?.let { System.nanoTime() + it * 1_000_000L }
        var attempt = 0
        var currentSocket: BluetoothSocket = socket

        try {
            capture.prepare(); capture.start()
            playback.prepare(); playback.start()

            outer@ while (true) {
                val reason = runDuplexSession(
                    socket = currentSocket,
                    capture = capture,
                    playback = playback,
                    role = role,
                    peer = peer,
                    psm = psm,
                    phy = phy,
                    framesSent = framesSent,
                    framesReceived = framesReceived,
                    callDeadlineNs = callDeadlineNs,
                )

                when (reason) {
                    SessionEndReason.Normal -> break@outer

                    SessionEndReason.SocketLost -> {
                        // Server side: bail out and let the passive accept
                        // loop re-accept; the client owns reconnect.
                        if (role != Role.CLIENT) break@outer
                        if (callDeadlineNs != null && System.nanoTime() >= callDeadlineNs) break@outer

                        attempt++
                        _state.value = ConnectionState.Reconnecting(role, peer, psm, attempt)
                        val newSocket = attemptReconnect(peer, psm)
                        if (newSocket == null) {
                            _state.value = ConnectionState.Failed(
                                peer = peer,
                                reason = "L2CAP reconnect timed out after ${RECONNECT_DEADLINE_MS}ms",
                                role = role,
                            )
                            return
                        }
                        currentSocket = newSocket
                    }
                }
            }
        } finally {
            runCatching { capture.stop() }; runCatching { capture.close() }
            runCatching { playback.stop() }; runCatching { playback.close() }
        }

        _state.value = ConnectionState.CallEnded(
            role = role,
            peer = peer,
            psm = psm,
            framesSent = framesSent.get(),
            framesReceived = framesReceived.get(),
        )
    }

    /**
     * Inner half of [runDuplexCall]: one socket, one set of codec / jitter
     * state, three threads. Returns the reason this session ended. Encoder
     * + decoder + jitter buffer are reset every session so re-encoding
     * after a reconnect doesn't carry over Opus internal state that the
     * peer has lost.
     */
    @SuppressLint("MissingPermission")
    private fun runDuplexSession(
        socket: BluetoothSocket,
        capture: AudioCapture,
        playback: AudioPlayback,
        role: Role,
        peer: Peer,
        psm: Int,
        phy: Int,
        framesSent: AtomicInteger,
        framesReceived: AtomicInteger,
        callDeadlineNs: Long?,
    ): SessionEndReason {
        val encoder = VoipEncoder()
        val decoder = VoipDecoder()
        val jitterBuffer = JitterBuffer()
        val running = AtomicBoolean(true)
        val sessionReason = AtomicReference(SessionEndReason.Normal)
        var txThread: Thread? = null
        var rxThread: Thread? = null
        var playbackThread: Thread? = null

        try {
            L2capChannel(socket).use { channel ->
                txThread = startTxThread(
                    channel = channel,
                    capture = capture,
                    encoder = encoder,
                    role = role,
                    peer = peer,
                    psm = psm,
                    phy = phy,
                    framesSent = framesSent,
                    framesReceived = framesReceived,
                    running = running,
                    callDeadlineNs = callDeadlineNs,
                    sessionReason = sessionReason,
                )
                rxThread = startRxThread(
                    channel = channel,
                    jitterBuffer = jitterBuffer,
                    running = running,
                    sessionReason = sessionReason,
                )
                playbackThread = startPlaybackThread(
                    decoder = decoder,
                    playback = playback,
                    jitterBuffer = jitterBuffer,
                    role = role,
                    peer = peer,
                    psm = psm,
                    phy = phy,
                    framesSent = framesSent,
                    framesReceived = framesReceived,
                    running = running,
                )

                txThread?.join()
                running.set(false)
                runCatching { socket.close() }
                rxThread?.join(SHUTDOWN_GRACE_MS)
                playbackThread?.join(SHUTDOWN_GRACE_MS)
            }
        } finally {
            running.set(false)
            txThread?.interrupt()
            rxThread?.interrupt()
            playbackThread?.interrupt()
        }

        return sessionReason.get()
    }

    /**
     * Try to reopen the L2CAP socket against the same PSM, polling every
     * 500 ms until [RECONNECT_DEADLINE_MS] elapses. Returns the freshly
     * connected socket or `null` on timeout.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun attemptReconnect(peer: Peer, psm: Int): BluetoothSocket? {
        val deadlineNs = System.nanoTime() + RECONNECT_DEADLINE_MS * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            try {
                return client.openL2capSocket(peer, psm)
            } catch (t: Throwable) {
                Log.d(TAG, "reconnect attempt failed: ${t.message}; will retry")
                try {
                    Thread.sleep(RECONNECT_BACKOFF_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun startTxThread(
        channel: L2capChannel,
        capture: AudioCapture,
        encoder: VoipEncoder,
        role: Role,
        peer: Peer,
        psm: Int,
        phy: Int,
        framesSent: AtomicInteger,
        framesReceived: AtomicInteger,
        running: AtomicBoolean,
        callDeadlineNs: Long?,
        sessionReason: AtomicReference<SessionEndReason>,
    ): Thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ShortArray(capture.frameSamples)
        // Seq + ts continue from the per-call counters so a reconnect
        // doesn't reset the wire stream — the peer's jitter buffer
        // still uses signed-delta arithmetic to order frames.
        var seq: Int = framesSent.get() and 0xFFFF
        var tsTicks: Int = framesSent.get() and 0xFFFF
        val deadlineNs: Long = callDeadlineNs ?: Long.MAX_VALUE

        while (running.get() && System.nanoTime() < deadlineNs) {
            val read = capture.readFrame(pcm)
            if (read < pcm.size) {
                Log.w(TAG, "AudioRecord underrun: $read/${pcm.size}; stopping Tx")
                break
            }
            val opus = try {
                encoder.encode(pcm.toList())
            } catch (t: Throwable) {
                Log.w(TAG, "encode failed: ${t.message}; stopping Tx")
                break
            }
            val header = AudioFrameHeader(
                seq = (seq and 0xFFFF).toUShort(),
                ts = (tsTicks and 0xFFFF).toUShort(),
            )
            val framed = buildAudioFrame(header, opus)
            val lenPrefix = byteArrayOf(
                ((framed.size shr 8) and 0xFF).toByte(),
                (framed.size and 0xFF).toByte(),
            )
            try {
                channel.send(lenPrefix)
                channel.send(framed)
            } catch (e: IOException) {
                Log.i(TAG, "Tx closing — L2CAP socket lost: ${e.message}")
                sessionReason.compareAndSet(SessionEndReason.Normal, SessionEndReason.SocketLost)
                break
            }
            seq = (seq + 1) and 0xFFFF
            tsTicks = (tsTicks + 1) and 0xFFFF
            val s = framesSent.incrementAndGet()
            _state.value = ConnectionState.InCall(
                role = role,
                peer = peer,
                psm = psm,
                negotiatedPhy = phy,
                framesSent = s,
                framesReceived = framesReceived.get(),
            )
        }
    }, "OppoLinkCallTx").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    private fun startRxThread(
        channel: L2capChannel,
        jitterBuffer: JitterBuffer,
        running: AtomicBoolean,
        sessionReason: AtomicReference<SessionEndReason>,
    ): Thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val lenBuf = ByteArray(2)
        while (running.get()) {
            try {
                channel.receiveExact(lenBuf)
                val frameLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (frameLen <= 0 || frameLen > MAX_WIRE_FRAME) {
                    Log.w(TAG, "invalid frame length on the wire: $frameLen; closing Rx")
                    break
                }
                val frameBuf = ByteArray(frameLen)
                channel.receiveExact(frameBuf)
                val parsed = parseAudioFrame(frameBuf)
                jitterBuffer.push(parsed.header.seq, parsed.opusPacket)
            } catch (e: IOException) {
                Log.i(TAG, "Rx closing — L2CAP socket lost: ${e.message}")
                sessionReason.compareAndSet(SessionEndReason.Normal, SessionEndReason.SocketLost)
                break
            }
        }
    }, "OppoLinkCallRx").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    /**
     * Drives the playback cadence. Pulls from the jitter buffer, decodes
     * (or PLCs) into PCM, and writes to AudioTrack. AudioTrack's own buffer
     * drain rate paces the loop at ~20 ms per frame; we never sleep on the
     * happy path. The 1 ms sleep below only fires during prewarm before
     * the jitter buffer has collected `target_depth` frames.
     */
    private fun startPlaybackThread(
        decoder: VoipDecoder,
        playback: AudioPlayback,
        jitterBuffer: JitterBuffer,
        role: Role,
        peer: Peer,
        psm: Int,
        phy: Int,
        framesSent: AtomicInteger,
        framesReceived: AtomicInteger,
        running: AtomicBoolean,
    ): Thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcmBuf = ShortArray(pcmSamplesPerFrame().toInt())

        while (running.get()) {
            val popped = jitterBuffer.popNext()
            val pcmList = when (popped) {
                is JitterPopResult.Packet -> {
                    try {
                        decoder.decode(popped.opusPacket)
                    } catch (t: Throwable) {
                        Log.w(TAG, "decode failed: ${t.message}; PLC instead")
                        runCatching { decoder.decodePlc() }.getOrNull()
                    }
                }
                JitterPopResult.Plc -> runCatching { decoder.decodePlc() }.getOrNull()
                JitterPopResult.Empty -> {
                    // Prewarm window — no data yet; idle briefly and try
                    // again. Production traffic should leave Empty within
                    // a few ticks of the first arrival.
                    Thread.sleep(1)
                    continue
                }
            } ?: continue

            val copyLen = minOf(pcmList.size, pcmBuf.size)
            // UniFFI 0.28 hands us a Kotlin List even for a Vec<i16>; this
            // copy is unavoidable until we move to a custom UniFFI type.
            for (i in 0 until copyLen) pcmBuf[i] = pcmList[i]
            playback.writeFrame(pcmBuf)

            val r = framesReceived.incrementAndGet()
            _state.value = ConnectionState.InCall(
                role = role,
                peer = peer,
                psm = psm,
                negotiatedPhy = phy,
                framesSent = framesSent.get(),
                framesReceived = r,
            )
        }
    }, "OppoLinkCallPlay").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    private companion object {
        const val TAG = "PeerConnector"
        /** Hard cap on a single audio frame as seen on the wire (header + opus). */
        const val MAX_WIRE_FRAME = 1504
        /** Time we wait for Rx to drain before forcibly tearing down. */
        const val SHUTDOWN_GRACE_MS = 1_500L

        /** Sprint 3 D11 — reconnect window before giving up on the call. */
        const val RECONNECT_DEADLINE_MS = 5_000L

        /** Polling interval inside [attemptReconnect]. */
        const val RECONNECT_BACKOFF_MS = 500L
    }
}
