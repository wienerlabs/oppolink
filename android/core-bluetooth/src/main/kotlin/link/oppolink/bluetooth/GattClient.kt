package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.oppolink_protocol.HandshakeMessage
import uniffi.oppolink_protocol.handshakeCharUuid
import uniffi.oppolink_protocol.parseHandshake

/**
 * Connects to a discovered [Peer] over GATT, reads the handshake
 * characteristic, and returns the decoded [HandshakeMessage].
 *
 * The GATT session is torn down before returning, so callers that need to
 * hand off to L2CAP must wire it on top — this class is single-purpose for
 * Sprint 1 D3.
 */
internal class GattClient(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {

    /**
     * Connect, read, disconnect. Throws on any GATT failure or timeout; the
     * coroutine cancellation path closes the GATT handle cleanly.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun fetchHandshake(peer: Peer): HandshakeMessage {
        val adapter = adapter ?: error("Bluetooth adapter unavailable")
        val device = adapter.getRemoteDevice(peer.bdAddress)

        return suspendCancellableCoroutine { cont ->
            var gatt: BluetoothGatt? = null

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
                    Log.i(TAG, "handshake from ${peer.bdAddress}: psm=${parsed.psm.toInt()}")
                    runCatching { g.disconnect() }
                    runCatching { g.close() }
                    if (cont.isActive) cont.resume(parsed)
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
                    BluetoothDeviceTransport.LE,
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

/** Compile-time alias to make the `connectGatt(..., transport=…)` call read clearly. */
private object BluetoothDeviceTransport {
    const val LE: Int = android.bluetooth.BluetoothDevice.TRANSPORT_LE
}
