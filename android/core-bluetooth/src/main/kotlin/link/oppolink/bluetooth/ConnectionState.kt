package link.oppolink.bluetooth

import uniffi.oppolink_protocol.EchoStats
import uniffi.oppolink_protocol.Role

/**
 * Lifecycle states reported by [PeerConnector] during the Sprint 1 D3
 * handshake and the D4 L2CAP echo test. Each state is terminal-or-progressive:
 * every non-`Failed` variant either advances to the next or back to [Idle]
 * on cancellation.
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
     * `1` = LE 1M, `2` = LE 2M, `3` = LE Coded; the spec target for the audio
     * path is 2. `0` means the read failed / fell back to default.
     */
    data class PsmExchanged(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val negotiatedPhy: Int,
    ) : ConnectionState

    /** Echo test running. `progress`/`total` drives the UI's progress bar. */
    data class EchoInProgress(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val progress: Int,
        val total: Int,
    ) : ConnectionState

    /** Echo test finished. Carries the RTT distribution + PHY snapshot. */
    data class EchoCompleted(
        val role: Role,
        val peer: Peer,
        val psm: Int,
        val negotiatedPhy: Int,
        val stats: EchoStats,
    ) : ConnectionState

    /** Terminal: something went wrong. Always carries a human-readable reason. */
    data class Failed(
        val peer: Peer,
        val reason: String,
        val role: Role? = null,
    ) : ConnectionState
}
