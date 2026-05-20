package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.oppolink_protocol.Capabilities
import uniffi.oppolink_protocol.ManufacturerData
import uniffi.oppolink_protocol.encodeManufacturerData

/**
 * Starts and stops BLE advertising for this peer. Primary packet carries the
 * service UUID; the scan response carries OppoLink's manufacturer-data payload
 * (magic + version + capability bitmap + nickname).
 *
 * The two-packet split is forced by BLE budget: a 128-bit service UUID alone
 * eats most of the 31-byte primary advertise budget.
 */
interface PeerAdvertiser {
    val state: StateFlow<State>

    /** Begin advertising with the given identity. Idempotent. */
    fun start(nickname: String, capabilities: Capabilities = Capabilities(true, true, false))

    /** Stop advertising. Idempotent. */
    fun stop()

    sealed interface State {
        data object Idle : State
        data object Advertising : State
        data class Error(val message: String, val nativeErrorCode: Int?) : State
    }
}

internal class RealPeerAdvertiser(
    private val context: Context,
    private val advertiser: BluetoothLeAdvertiser?,
) : PeerAdvertiser {

    private val _state = MutableStateFlow<PeerAdvertiser.State>(PeerAdvertiser.State.Idle)
    override val state: StateFlow<PeerAdvertiser.State> = _state.asStateFlow()

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "advertising started (mode=${settingsInEffect?.mode})")
            _state.value = PeerAdvertiser.State.Advertising
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            _state.value = PeerAdvertiser.State.Error(
                message = describeError(errorCode),
                nativeErrorCode = errorCode,
            )
        }
    }

    @SuppressLint("MissingPermission") // checked at call sites via BluetoothPermissions
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun start(nickname: String, capabilities: Capabilities) {
        val activeAdvertiser = advertiser
        if (activeAdvertiser == null) {
            _state.value = PeerAdvertiser.State.Error(
                message = "Bluetooth LE advertising is not available on this device.",
                nativeErrorCode = null,
            )
            return
        }
        if (!BluetoothPermissions.hasBle(context)) {
            _state.value = PeerAdvertiser.State.Error(
                message = "Missing Bluetooth runtime permissions.",
                nativeErrorCode = null,
            )
            return
        }

        // Stop any in-flight advertisement before reconfiguring.
        runCatching { activeAdvertiser.stopAdvertising(callback) }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // run until stop() is called
            .build()

        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(OppoLinkUuid.SERVICE_PARCEL)
            .build()

        val payload = encodeManufacturerData(
            ManufacturerData(nickname = nickname, capabilities = capabilities),
        )
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(OppoLinkUuid.MANUFACTURER_ID, payload)
            .build()

        activeAdvertiser.startAdvertising(settings, primary, scanResponse, callback)
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun stop() {
        advertiser?.let { runCatching { it.stopAdvertising(callback) } }
        _state.value = PeerAdvertiser.State.Idle
    }

    private fun describeError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertising data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Bluetooth stack error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertising not supported"
        else -> "Unknown advertise error ($code)"
    }

    private companion object {
        const val TAG = "PeerAdvertiser"
    }
}
