package link.oppolink.bluetooth

/**
 * A discovered OppoLink peer, surfaced by the BLE scanner.
 *
 * Stubbed in Sprint 1 D1. Replaced by a real implementation backed by
 * [android.bluetooth.le.ScanResult] in D2.
 */
data class Peer(
    val nickname: String,
    val bdAddress: String,
    val rssi: Int,
)

/** Discovery API. Real implementation arrives in Sprint 1 D2. */
interface PeerScanner {
    fun start()
    fun stop()
    fun observePeers(): kotlinx.coroutines.flow.Flow<List<Peer>>
}
