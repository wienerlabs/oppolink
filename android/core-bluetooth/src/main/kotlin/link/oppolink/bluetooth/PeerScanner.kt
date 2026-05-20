package link.oppolink.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.oppolink_protocol.ManufacturerData
import uniffi.oppolink_protocol.parseManufacturerData

/**
 * Discovers nearby OppoLink peers via BLE scan. Emits the live peer list as a
 * [StateFlow] keyed by Bluetooth address; peers age out after
 * [PEER_TIMEOUT_MS] without a fresh advertisement.
 */
interface PeerScanner {
    val state: StateFlow<State>
    val peers: StateFlow<List<Peer>>

    /** Begin scanning. Safe to call when already scanning (idempotent). */
    fun start()

    /** Stop scanning and clear the visible peer list. */
    fun stop()

    sealed interface State {
        data object Idle : State
        data object Scanning : State
        data class Error(val message: String, val nativeErrorCode: Int?) : State
    }
}

internal class RealPeerScanner(
    private val context: Context,
    private val scanner: BluetoothLeScanner?,
) : PeerScanner {

    private val _state = MutableStateFlow<PeerScanner.State>(PeerScanner.State.Idle)
    override val state: StateFlow<PeerScanner.State> = _state.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<List<Peer>> = MutableStateFlow<List<Peer>>(emptyList()).also {
        // Mirror the keyed map into a sorted list, strongest RSSI first.
        scope.launch {
            _peers.collect { map ->
                it.value = map.values.sortedByDescending { p -> p.rssi }
            }
        }
    }.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _state.value = PeerScanner.State.Error(
                message = describeError(errorCode),
                nativeErrorCode = errorCode,
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweeperJob: Job? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun start() {
        if (scanner == null) {
            _state.value = PeerScanner.State.Error(
                message = "Bluetooth LE scanning is not available on this device.",
                nativeErrorCode = null,
            )
            return
        }
        if (!BluetoothPermissions.hasBle(context)) {
            _state.value = PeerScanner.State.Error(
                message = "Missing Bluetooth runtime permissions.",
                nativeErrorCode = null,
            )
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(OppoLinkUuid.SERVICE_PARCEL)
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        scanner.startScan(filters, settings, callback)
        _state.value = PeerScanner.State.Scanning
        startSweeper()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun stop() {
        scanner?.let { runCatching { it.stopScan(callback) } }
        sweeperJob?.cancel()
        sweeperJob = null
        _peers.value = emptyMap()
        _state.value = PeerScanner.State.Idle
    }

    private fun handleResult(result: ScanResult) {
        val bdAddress = result.device.address ?: return
        val rawManuf = result.scanRecord?.getManufacturerSpecificData(OppoLinkUuid.MANUFACTURER_ID)
        val decoded: ManufacturerData? = rawManuf?.let { parseManufacturerData(it) }
        val peer = Peer(
            bdAddress = bdAddress,
            nickname = decoded?.nickname ?: UNKNOWN_NICK,
            rssi = result.rssi,
            capabilities = decoded?.capabilities ?: defaultCapabilities(),
            lastSeenAtMs = SystemClock.elapsedRealtime(),
        )
        _peers.update { current -> current + (peer.bdAddress to peer) }
    }

    private fun startSweeper() {
        sweeperJob?.cancel()
        sweeperJob = scope.launch {
            while (true) {
                delay(SWEEP_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                _peers.update { current ->
                    current.filterValues { now - it.lastSeenAtMs <= PEER_TIMEOUT_MS }
                }
            }
        }
    }

    private fun defaultCapabilities() = uniffi.oppolink_protocol.Capabilities(
        pcm16kMono = true,
        opus = true,
        aead = false,
    )

    private fun describeError(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal Bluetooth stack error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Scan feature not supported"
        else -> "Unknown scan error ($code)"
    }

    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        const val TAG = "PeerScanner"
        const val PEER_TIMEOUT_MS = 20_000L
        const val SWEEP_INTERVAL_MS = 2_000L
        const val UNKNOWN_NICK = "(unknown)"
    }
}
