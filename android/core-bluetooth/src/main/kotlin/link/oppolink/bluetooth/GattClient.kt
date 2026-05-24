package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.oppolink_protocol.EphemeralKeyPair
import uniffi.oppolink_protocol.HandshakeMessage
import uniffi.oppolink_protocol.SessionKey
import uniffi.oppolink_protocol.handshakeCharUuid
import uniffi.oppolink_protocol.parseHandshake

/**
 * Result of a successful GATT handshake fetch: the parsed [HandshakeMessage]
 * plus the PHY the underlying ACL link is currently using.
 *
 * `negotiatedPhy`:
 *  - `1` = LE 1M (default fallback)
 *  - `2` = LE 2M (the Sprint 1 D4 target for the audio path)
 *  - `3` = LE Coded (long-range, slower)
 *  - `0` = readPhy() failed; caller treats this as "unknown, assume 1M".
 */
data class GattHandshakeResult(
    val handshake: HandshakeMessage,
    val negotiatedPhy: Int,
    /**
     * Sprint 4 D13 — derived AEAD key for this call. The Tx / Rx loops
     * call `sessionKey.encryptFrame` / `decryptFrame` on every 20 ms
     * frame. `null` only on the legacy code path during v1→v2 migration;
     * the new code always produces one.
     */
    val sessionKey: SessionKey,
    /** Held by [GattClient] until the client writes its pubkey into L2CAP. */
    internal val clientPubkey: ByteArray,
)

/**
 * Connects to a discovered [Peer] over GATT, reads the handshake
 * characteristic, snapshots the negotiated PHY, and returns both.
 *
 * The GATT session is torn down before returning, so callers that need to
 * hand off to L2CAP must wire it on top via [openL2capSocket] — this class
 * does not keep a long-lived GATT handle around. The Android L2CAP CoC API
 * works at the BD_ADDR / PSM level and does not require GATT to stay open.
 */
internal class GattClient(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {

    /**
     * Open an L2CAP CoC socket against [peer] at [psm] and write the
     * client's 32-byte ephemeral pubkey as the first payload bytes. The
     * server side reads those 32 bytes before any audio frame arrives,
     * derives its own [SessionKey], and matches what the client
     * computed in [fetchHandshake].
     *
     * `clientPubkey` is the bytes the [fetchHandshake] caller saved in
     * [GattHandshakeResult.clientPubkey]; passing it explicitly avoids
     * having `GattClient` hold mutable session state across method calls.
     *
     * Blocking: callers MUST invoke from a dedicated worker [Thread].
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun openL2capSocket(peer: Peer, psm: Int, clientPubkey: ByteArray): BluetoothSocket {
        require(clientPubkey.size == 32) { "Curve25519 pubkey must be exactly 32 bytes" }
        val adapter = adapter ?: error("Bluetooth adapter unavailable")
        val device = adapter.getRemoteDevice(peer.bdAddress)
        val socket = device.createInsecureL2capChannel(psm)
        socket.connect() // blocks until peer accepts or fails
        try {
            socket.outputStream.write(clientPubkey)
            socket.outputStream.flush()
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
        return socket
    }

    /**
     * Connect, read the handshake characteristic, read PHY, disconnect.
     *
     * Throws on any GATT failure or timeout; cancelling the coroutine closes
     * the GATT handle cleanly.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun fetchHandshake(peer: Peer): GattHandshakeResult {
        val adapter = adapter ?: error("Bluetooth adapter unavailable")
        val device = adapter.getRemoteDevice(peer.bdAddress)

        return suspendCancellableCoroutine { cont ->
            var gatt: BluetoothGatt? = null
            var parsedHandshake: HandshakeMessage? = null

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                        finishWithError(g, "GATT connection failed (status=$status)")
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!g.discoverServices()) {
                            finishWithError(g, "discoverServices() returned false")
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // If we haven't resumed yet, this is a real failure.
                        if (cont.isActive) {
                            finishWithError(g, "GATT disconnected before handshake was read")
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finishWithError(g, "service discovery failed (status=$status)")
                        return
                    }
                    val service = g.getService(OppoLinkUuid.SERVICE)
                    if (service == null) {
                        finishWithError(g, "OppoLink service not advertised by peer")
                        return
                    }
                    val char = service.getCharacteristic(UUID.fromString(handshakeCharUuid()))
                    if (char == null) {
                        finishWithError(g, "handshake characteristic missing on peer service")
                        return
                    }
                    if (!g.readCharacteristic(char)) {
                        finishWithError(g, "readCharacteristic() returned false")
                    }
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finishWithError(g, "characteristic read failed (status=$status)")
                        return
                    }
                    val value = characteristic.value
                    if (value == null || value.isEmpty()) {
                        finishWithError(g, "handshake characteristic returned empty payload")
                        return
                    }
                    val parsed = parseHandshake(value)
                    if (parsed == null) {
                        finishWithError(g, "handshake payload failed to parse")
                        return
                    }
                    parsedHandshake = parsed
                    Log.i(TAG, "handshake from ${peer.bdAddress}: psm=${parsed.psm.toInt()}, sas pending ECDH")
                    // Trigger PHY snapshot before tearing the GATT session down.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        g.readPhy()
                    } else {
                        finishWithPhy(g, fallbackPhy = 1)
                    }
                }

                override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                    val phy = if (status == BluetoothGatt.GATT_SUCCESS) {
                        // We treat the slower of tx/rx as the bottleneck — the
                        // 20 ms audio tick is bidirectional.
                        minOf(txPhy, rxPhy)
                    } else {
                        Log.w(TAG, "readPhy failed (status=$status); falling back to LE 1M")
                        1
                    }
                    finishWithPhy(g, phy)
                }

                private fun finishWithPhy(g: BluetoothGatt, fallbackPhy: Int) {
                    val handshake = parsedHandshake
                    runCatching { g.disconnect() }
                    runCatching { g.close() }
                    if (!cont.isActive) return
                    if (handshake == null) {
                        cont.resumeWithException(
                            IllegalStateException("PHY read fired before handshake parsed"),
                        )
                        return
                    }
                    // Sprint 4 D13 — ECDH. The server published its
                    // ephemeral pubkey in the handshake payload. We build
                    // ours, derive the session key, and let the caller
                    // write our pubkey across L2CAP in openL2capSocket.
                    val ourKp = EphemeralKeyPair()
                    val ourPub = ourKp.publicKey()
                    val sessionKey = try {
                        ourKp.deriveSession(handshake.pubkey)
                    } catch (t: Throwable) {
                        cont.resumeWithException(
                            IllegalStateException("ECDH derive failed: ${t.message}", t),
                        )
                        return
                    }
                    cont.resume(
                        GattHandshakeResult(
                            handshake = handshake,
                            negotiatedPhy = fallbackPhy,
                            sessionKey = sessionKey,
                            clientPubkey = ourPub,
                        ),
                    )
                }

                private fun finishWithError(g: BluetoothGatt, message: String) {
                    Log.w(TAG, "GATT error: $message")
                    runCatching { g.disconnect() }
                    runCatching { g.close() }
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException(message))
                    }
                }
            }

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    context,
                    /* autoConnect = */ false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } else {
                device.connectGatt(context, /* autoConnect = */ false, callback)
            }

            cont.invokeOnCancellation {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }
    }

    private companion object {
        const val TAG = "GattClient"
    }
}
