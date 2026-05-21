package link.oppolink.bluetooth

import uniffi.oppolink_protocol.Role

/**
 * Lifecycle states reported by [PeerConnector] during the Sprint 1 D3
 * handshake. Each state is terminal-or-progressive: every non-`Failed`
 * variant either advances to the next or back to [Idle] on cancellation.
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
     * `BluetoothServerSocket` is listening — D3 stops here; D4 will accept
     * and open the L2CAP socket on top.
     */
    data class PsmExchanged(val role: Role, val peer: Peer, val psm: Int) : ConnectionState

    /** Terminal: something went wrong. Always carries a human-readable reason. */
    data class Failed(
        val peer: Peer,
        val reason: String,
        val role: Role? = null,
    ) : ConnectionState
}
