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
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID
import uniffi.oppolink_protocol.EphemeralKeyPair
import uniffi.oppolink_protocol.HandshakeMessage
import uniffi.oppolink_protocol.Role
import uniffi.oppolink_protocol.SessionKey
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
 *    the static payload - no per-connection logic is required for D3.
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
    /** Sprint 4 D13 - server's ephemeral Curve25519 keypair, generated in [start]. */
    private var keyPair: EphemeralKeyPair? = null

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
        // Allocate L2CAP server socket first - we need the PSM before we can
        // build the handshake payload.
        val socket = adapter.listenUsingInsecureL2capChannel()
        serverSocket = socket
        allocatedPsm = socket.psm

        // Sprint 4 D13 - server's ephemeral keypair. Pubkey goes into the
        // handshake; the secret half is kept on this object and consumed
        // when an inbound L2CAP socket completes its first 32-byte
        // pubkey exchange (see [deriveServerSession]).
        val kp = EphemeralKeyPair()
        val ourPub = kp.publicKey()
        keyPair = kp

        val payload = encodeHandshake(
            HandshakeMessage(
                role = Role.SERVER,
                pubkey = ourPub,
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
            ?: error("openGattServer returned null - Bluetooth off?")
        gattServer = server
        if (!server.addService(service)) {
            error("Failed to register handshake GATT service")
        }

        Log.i(TAG, "GATT server running, PSM=$allocatedPsm")
    }

    /**
     * Blocking accept on the listening L2CAP server socket.
     *
     * MUST be called from a dedicated worker [Thread] - the call parks the
     * caller until a client connects or the socket is closed underneath us.
     * Returns `null` if the server is no longer running (e.g. [stop] fired
     * concurrently). The caller owns the returned socket and is responsible
     * for closing it.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    fun acceptL2cap(): BluetoothSocket? {
        val socket = serverSocket ?: return null
        return try {
            socket.accept()
        } catch (t: Throwable) {
            Log.w(TAG, "L2CAP accept failed: ${t.message}")
            null
        }
    }

    /**
     * Sprint 4 D13 - read the client's 32-byte Curve25519 pubkey off the
     * fresh L2CAP socket and ECDH it against our own keypair. Throws on
     * EOF / IO failure; the caller treats that as "the socket died
     * before handshake completed" and closes everything.
     *
     * Blocking; MUST be called from the same worker thread that did
     * [acceptL2cap]. Returns the derived [SessionKey] which the audio
     * pipeline uses for `encryptFrame` / `decryptFrame`.
     */
    fun deriveServerSession(socket: BluetoothSocket): SessionKey {
        val kp = keyPair ?: error("GattServerHost not started")
        val buf = ByteArray(32)
        var read = 0
        val input = socket.inputStream
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw java.io.IOException(
                "L2CAP closed before client pubkey arrived ($read/${buf.size} bytes)",
            )
            read += n
        }
        return kp.deriveSession(buf)
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
        keyPair = null
    }

    private companion object {
        const val TAG = "GattServerHost"
    }
}
