package link.oppolink.discovery

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import link.oppolink.bluetooth.BluetoothPermissions
import link.oppolink.bluetooth.Peer
import link.oppolink.bluetooth.PeerAdvertiser
import link.oppolink.bluetooth.PeerConnector
import link.oppolink.bluetooth.PeerScanner

/**
 * Drives the Sprint 1 D2 discovery screen.
 *
 * - On [start] we begin advertising our own nickname and scanning for peers.
 * - On [stop] we tear both sides down. The advertiser/scanner are Singletons
 *   so this ViewModel can come and go without leaking radio state.
 */
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: PeerScanner,
    private val advertiser: PeerAdvertiser,
    private val connector: PeerConnector,
) : ViewModel() {

    /** Best-effort display nickname; user-editable in Sprint 4. */
    val deviceNickname: String = sanitizeNickname("${Build.MANUFACTURER} ${Build.MODEL}")

    val uiState: StateFlow<DiscoveryUiState> = combine(
        scanner.state,
        advertiser.state,
        scanner.peers,
    ) { scanState, advertiseState, peers ->
        DiscoveryUiState(
            nickname = deviceNickname,
            scanState = scanState,
            advertiseState = advertiseState,
            peers = peers,
            permissionsGranted = BluetoothPermissions.hasBle(context),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DiscoveryUiState.initial(deviceNickname, BluetoothPermissions.hasBle(context)),
    )

    fun start() {
        scanner.start()
        advertiser.start(nickname = deviceNickname)
        connector.startPassiveServer(nickname = deviceNickname)
    }

    fun stop() {
        scanner.stop()
        advertiser.stop()
        connector.stopPassiveServer()
    }

    private fun sanitizeNickname(raw: String): String =
        raw.trim().take(MAX_NICKNAME_DISPLAY_CHARS).ifBlank { "OppoLink peer" }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_NICKNAME_DISPLAY_CHARS = 16
    }
}

data class DiscoveryUiState(
    val nickname: String,
    val scanState: PeerScanner.State,
    val advertiseState: PeerAdvertiser.State,
    val peers: List<Peer>,
    val permissionsGranted: Boolean,
) {
    val isLive: Boolean
        get() = scanState is PeerScanner.State.Scanning ||
            advertiseState is PeerAdvertiser.State.Advertising

    companion object {
        fun initial(nickname: String, permissionsGranted: Boolean) = DiscoveryUiState(
            nickname = nickname,
            scanState = PeerScanner.State.Idle,
            advertiseState = PeerAdvertiser.State.Idle,
            peers = emptyList(),
            permissionsGranted = permissionsGranted,
        )
    }
}
