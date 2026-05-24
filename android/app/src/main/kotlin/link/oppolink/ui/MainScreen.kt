package link.oppolink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.oppolink.bluetooth.Peer
import link.oppolink.discovery.ConnectionScreen
import link.oppolink.discovery.DiscoveryScreen
import link.oppolink.discovery.PermissionGate
import link.oppolink.setup.ColorOsSetupScreen
import link.oppolink.setup.SetupViewModel

/**
 * Single-activity navigation:
 *   1. [PermissionGate] — runtime BLE / RECORD_AUDIO grants
 *   2. [ColorOsSetupScreen] — Sprint 3 D10 first-run wizard; auto-skipped
 *      on stock Android or once the user has dismissed it
 *   3. [DiscoveryScreen] / [ConnectionScreen] — the actual app
 *
 * Hand-rolled toggle on purpose; a Navigation library isn't worth its
 * weight for four screens.
 */
@Composable
fun MainScreen() {
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }

    PermissionGate {
        val setupViewModel: SetupViewModel = hiltViewModel()
        val showSetup by setupViewModel.shouldShowWizard.collectAsStateWithLifecycle()
        if (showSetup) {
            ColorOsSetupScreen(viewModel = setupViewModel)
        } else {
            val peer = selectedPeer
            if (peer == null) {
                DiscoveryScreen(onPeerTap = { selectedPeer = it })
            } else {
                BackHandler { selectedPeer = null }
                ConnectionScreen(
                    peer = peer,
                    onBack = { selectedPeer = null },
                )
            }
        }
    }
}
