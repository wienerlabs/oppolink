package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID
import uniffi.oppolink_protocol.HandshakeMessage
import uniffi.oppolink_protocol.Role
import uniffi.oppolink_protocol.encodeHandshake
import uniffi.oppolink_protocol.handshakeCharUuid

/**
 * Hosts the OppoLink GATT service so a peer can read our handshake payload.
 *
 * Lifecycle:
 *  - [start] registers the service, allocates an insecure L2CAP server socket
 *    via `BluetoothAdapter.listenUsingInsecureL2capChannel()`, and bakes the
 *    resulting PSM into the handshake characteristic's stored value.
 *  - The GATT layer auto-replies to every read on the characteristic with
 *    the static payload — no per-connection logic is required for D3.
 *  - [stop] closes the L2CAP server socket and tears down the GATT service.
 *
 * D4 will reuse the [serverSocket] to `accept()` an incoming L2CAP connection.
 */
internal class GattServerHost(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val manager: BluetoothManager,
) {
    private var gattServer: BluetoothGattServer? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var handshakeChar: BluetoothGattCharacteristic? = null
    private var allocatedPsm: Int = 0

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.d(TAG, "server conn state device=${device?.address} status=$status newState=$newState")
        }
    }

    /** PSM allocated by Android for our L2CAP server socket, or 0 before [start]. */
    fun psm(): Int = allocatedPsm

    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    fun start(nickname: String) {
        val adapter = adapter ?: error("Bluetooth adapter unavailable")
        // Allocate L2CAP server socket first — we need the PSM before we can
        // build the handshake payload.
        val socket = adapter.listenUsingInsecureL2capChannel()
        serverSocket = socket
        allocatedPsm = socket.psm

        val payload = encodeHandshake(
            HandshakeMessage(
                role = Role.SERVER,
                pubkey = ByteArray(32), // zeros until Sprint 4 D13
                psm = allocatedPsm.toUShort(),
                nickname = nickname,
            ),
        )

        val char = BluetoothGattCharacteristic(
            UUID.fromString(handshakeCharUuid()),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also { it.value = payload }
        handshakeChar = char

        val service = BluetoothGattService(
            OppoLinkUuid.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).also { it.addCharacteristic(char) }

        val server = manager.openGattServer(context, callback)
            ?: error("openGattServer returned null — Bluetooth off?")
        gattServer = server
        if (!server.addService(service)) {
            error("Failed to register handshake GATT service")
        }

        Log.i(TAG, "GATT server running, PSM=$allocatedPsm")
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    fun stop() {
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        allocatedPsm = 0
        handshakeChar = null
    }

    private companion object {
        const val TAG = "GattServerHost"
    }
}
