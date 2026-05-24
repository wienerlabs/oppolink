package link.oppolink.bluetooth

import uniffi.oppolink_protocol.Role

/**
 * Lifecycle states reported by [PeerConnector] from the moment a peer is
 * tapped through the end of the call. Each state is terminal-or-progressive:
 * every non-`Failed` variant either advances to the next or back to [Idle]
 * on cancellation.
 *
 * The Sprint 1 D4 echo path retired in Sprint 2 D5 — the passive accept
 * thread now drives the audio playback loop instead of mirroring bytes
 * back. Echo helpers still exist in Rust for ad-hoc latency probing.
 */
sealed interface ConnectionState {
    /** No active connection. Initial state and the destination after [cancel]. */
    data object Idle : ConnectionState

    /** Local peer has decided its role; about to start GATT activity. */
    data class RoleDecided(val role: Role, val peer: Peer) : ConnectionState

    /** GATT TCP / ACL connection coming up. */
    data class Connecting(val role: Role, val peer: Peer) : ConnectionState

    /** GATT is connected; exchanging the handshake characteristic. */
    data class Handshaking(val role: Role, val peer: Peer) : ConnectionState

    /**
     * Handshake completed. Client side: PSM read from the server's
     * characteristic. Server side: own PSM is now published and a
     * `BluetoothServerSocket` is listening for the matching client.
     *
     * `negotiatedPhy` is the value returned by `BluetoothGatt.readPhy()`:
     * `1` = LE 1M, `2` = LE 2M, `3` = LE Coded; the spec target for the
     * audio path is 2. `0` means the read failed / fell back to default.
     */
    data class PsmExchanged(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val negotiatedPhy: Int,
    ) : ConnectionState

    /**
     * Call is live. The client thread is capturing → encoding → sending;
     * the server thread is receiving → decoding → playing. `framesSent`
     * and `framesReceived` tick on every 20 ms boundary so the UI can
     * surface a heartbeat.
     *
     * `muted` (Sprint 4 D12) is `true` when the local mic is off — Tx
     * keeps the L2CAP socket open but skips encode/send while
     * `AudioRecord` is stopped. Used by push-to-talk and the future
     * "mute me" UI button.
     */
    data class InCall(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val negotiatedPhy: Int,
        val framesSent: Int,
        val framesReceived: Int,
        val muted: Boolean = false,
    ) : ConnectionState

    /**
     * Sprint 3 D11 — the L2CAP socket broke (peer briefly out of range,
     * radio glitch); we're trying to bring it back with the cached PSM.
     * `attempt` increments each retry; the window caps at 5 seconds
     * before flipping to [Failed].
     */
    data class Reconnecting(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val attempt: Int,
    ) : ConnectionState

    /** Call ended normally (peer hung up, user cancelled, end of test loop). */
    data class CallEnded(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val framesSent: Int,
        val framesReceived: Int,
    ) : ConnectionState

    /** Terminal: something went wrong. Always carries a human-readable reason. */
    data class Failed(
        val peer: Peer,
        val reason: String,
        val role: Role? = null,
    ) : ConnectionState
}
