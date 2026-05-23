package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Process
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
import link.oppolink.audio.AudioCapture
import link.oppolink.audio.AudioPlayback
import uniffi.oppolink_protocol.AudioFrameHeader
import uniffi.oppolink_protocol.Role
import uniffi.oppolink_protocol.VoipDecoder
import uniffi.oppolink_protocol.VoipEncoder
import uniffi.oppolink_protocol.buildAudioFrame
import uniffi.oppolink_protocol.parseAudioFrame
import uniffi.oppolink_protocol.pcmSamplesPerFrame

/**
 * Orchestrates the Sprint 1 D3 handshake + Sprint 2 D5 one-way call.
 *
 * **Role assignment** is UI-driven: whichever side taps a peer in the
 * discovery list takes the **client** role and drives the capture →
 * encode → send leg; the tapped side stays passive with its
 * [GattServerHost] already advertising the OppoLink service and runs
 * receive → decode → play on the accept thread.
 *
 * Sprint 2 D5 retires the D4 echo loop — server side no longer mirrors
 * bytes; it decodes Opus and writes PCM to [AudioPlayback]. The Rust
 * `summarize_echo_samples` helper still exists for ad-hoc latency probes
 * but is no longer wired from the UI.
 */
interface PeerConnector {
    val state: StateFlow<ConnectionState>

    /**
     * Start the passive GATT-server side so a remote client can reach us at
     * any time. Idempotent; safe to call from the discovery screen lifecycle.
     * Also starts the `OppoLinkAccept` thread which `accept()`s the inbound
     * L2CAP socket and drives the playback loop.
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
     * Drive the one-way call client side: open the L2CAP socket on the PSM
     * the handshake exchanged, capture → encode → send for [durationMs].
     * Pushes [ConnectionState.InCall] heartbeats and finishes on
     * [ConnectionState.CallEnded].
     */
    suspend fun runCall(peer: Peer, durationMs: Long = DEFAULT_CALL_DURATION_MS)

    /** Abort an in-flight [connect] or [runCall]. */
    fun cancel()

    companion object {
        /** Default test-call duration the UI button uses (10 seconds). */
        const val DEFAULT_CALL_DURATION_MS: Long = 10_000L
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
    override suspend fun runCall(peer: Peer, durationMs: Long) {
        val (psm, phy) = when (val s = _state.value) {
            is ConnectionState.PsmExchanged -> s.psm to s.negotiatedPhy
            is ConnectionState.CallEnded -> s.psm to 0
            else -> error("Call can only start after handshake; current=${s::class.simpleName}")
        }
        val socketRef = AtomicReference<BluetoothSocket?>(null)

        suspendCancellableCoroutine<Unit> { cont ->
            val thread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val capture = AudioCapture()
                val encoder: VoipEncoder
                try {
                    encoder = VoipEncoder()
                    capture.prepare()
                    capture.start()
                    val socket = client.openL2capSocket(peer, psm)
                    socketRef.set(socket)
                    L2capChannel(socket).use { channel ->
                        runClientCallLoop(capture, encoder, channel, peer, psm, phy, durationMs)
                    }
                    if (cont.isActive) cont.resume(Unit)
                } catch (t: Throwable) {
                    if (cont.isCancelled) return@Thread
                    Log.w(TAG, "client call loop failed: ${t.message}")
                    _state.value = ConnectionState.Failed(
                        peer = peer,
                        reason = t.message ?: "call failed",
                        role = Role.CLIENT,
                    )
                    if (cont.isActive) cont.resumeWithException(t)
                } finally {
                    runCatching { capture.stop() }
                    runCatching { capture.close() }
                }
            }, "OppoLinkCallClient")
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val playback = AudioPlayback()
            try {
                val decoder = VoipDecoder()
                playback.prepare()
                playback.start()
                val socket = host.acceptL2cap() ?: return@Thread
                L2capChannel(socket).use { channel ->
                    runServerPlaybackLoop(decoder, playback, channel)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server playback loop failed: ${t.message}")
            } finally {
                runCatching { playback.stop() }
                runCatching { playback.close() }
            }
        }, "OppoLinkAccept").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Server playback loop: read length-prefixed audio frames off the
     * channel, decode Opus, write PCM to AudioTrack. The wire delimiter is
     * a 2-byte big-endian length prefix so the receive side knows how many
     * bytes to pull before parsing.
     */
    private fun runServerPlaybackLoop(
        decoder: VoipDecoder,
        playback: AudioPlayback,
        channel: L2capChannel,
    ) {
        val lenBuf = ByteArray(2)
        var maxFrameLen = 1500
        var frameBuf = ByteArray(maxFrameLen)
        val pcmBuf = ShortArray(pcmSamplesPerFrame().toInt())

        while (true) {
            try {
                channel.receiveExact(lenBuf)
                val frameLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (frameLen <= 0 || frameLen > MAX_WIRE_FRAME) {
                    Log.w(TAG, "invalid frame length on the wire: $frameLen; closing")
                    return
                }
                if (frameLen > maxFrameLen) {
                    maxFrameLen = frameLen
                    frameBuf = ByteArray(maxFrameLen)
                }
                val readBuf = ByteArray(frameLen)
                channel.receiveExact(readBuf)
                val parsed = parseAudioFrame(readBuf)
                val pcmList = decoder.decode(parsed.opusPacket)
                // List<Short> → pre-allocated ShortArray. UniFFI 0.28 hands us
                // a Kotlin List even when the Rust side is a Vec<i16>; this
                // copy is the unavoidable cost until we move to a custom type.
                val copyLen = minOf(pcmList.size, pcmBuf.size)
                for (i in 0 until copyLen) pcmBuf[i] = pcmList[i]
                playback.writeFrame(pcmBuf)
            } catch (e: IOException) {
                Log.i(TAG, "server playback loop closing: ${e.message}")
                return
            }
        }
    }

    /**
     * Client capture loop: capture → encode → length-prefix → send.
     * Maintains the wall-clock target by pacing on the AudioRecord stream
     * itself (blocking read consumes ~20 ms whenever a frame is ready).
     */
    private fun runClientCallLoop(
        capture: AudioCapture,
        encoder: VoipEncoder,
        channel: L2capChannel,
        peer: Peer,
        psm: Int,
        phy: Int,
        durationMs: Long,
    ) {
        val pcm = ShortArray(capture.frameSamples)
        var seq: Int = 0
        var tsTicks: Int = 0
        val deadline = System.nanoTime() + durationMs * 1_000_000L

        while (System.nanoTime() < deadline) {
            val read = capture.readFrame(pcm)
            if (read < pcm.size) {
                Log.w(TAG, "AudioRecord underrun: $read/${pcm.size}; stopping")
                break
            }
            val opus = encoder.encode(pcm.toList())
            val header = AudioFrameHeader(
                seq = (seq and 0xFFFF).toUShort(),
                ts = (tsTicks and 0xFFFF).toUShort(),
            )
            val framed = buildAudioFrame(header, opus)
            val lenPrefix = byteArrayOf(
                ((framed.size shr 8) and 0xFF).toByte(),
                (framed.size and 0xFF).toByte(),
            )
            channel.send(lenPrefix)
            channel.send(framed)
            seq = (seq + 1) and 0xFFFF
            tsTicks = (tsTicks + 1) and 0xFFFF
            _state.value = ConnectionState.InCall(
                role = Role.CLIENT,
                peer = peer,
                psm = psm,
                negotiatedPhy = phy,
                framesSent = seq,
                framesReceived = 0,
            )
        }
        _state.value = ConnectionState.CallEnded(
            role = Role.CLIENT,
            peer = peer,
            psm = psm,
            framesSent = seq,
            framesReceived = 0,
        )
    }

    private companion object {
        const val TAG = "PeerConnector"
        /** Hard cap on a single audio frame as seen on the wire (header + opus). */
        const val MAX_WIRE_FRAME = 1504
    }
}
