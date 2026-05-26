package link.oppolink.bluetooth

import uniffi.oppolink_protocol.Capabilities

/**
 * A nearby OppoLink peer surfaced by [PeerScanner]. Identity is the BLE
 * address - the nickname is advisory and may be the device model.
 *
 * `lastSeenAtMs` is wall-clock time of the most recent advertisement; the
 * scanner uses it to time out stale peers from the visible list.
 */
data class Peer(
    val bdAddress: String,
    val nickname: String,
    val rssi: Int,
    val capabilities: Capabilities,
    val lastSeenAtMs: Long,
)
