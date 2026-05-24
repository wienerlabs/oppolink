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
import link.oppolink.settings.SettingsScreen
import link.oppolink.setup.ColorOsSetupScreen
import link.oppolink.setup.SetupViewModel

/**
 * Single-activity navigation:
 *   1. [PermissionGate] — runtime BLE / RECORD_AUDIO grants
 *   2. [ColorOsSetupScreen] — Sprint 3 D10 first-run wizard; auto-skipped
 *      on stock Android or once the user has dismissed it
 *   3. [DiscoveryScreen] / [ConnectionScreen] — the actual app
 *   4. [SettingsScreen] — reachable from the Discovery header; lets the
 *      user re-open the wizard, deep-link into ColorOS settings, and
 *      read app/build info
 *
 * Hand-rolled toggle on purpose; a Navigation library isn't worth its
 * weight for four screens.
 */
@Composable
fun MainScreen() {
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    PermissionGate {
        val setupViewModel: SetupViewModel = hiltViewModel()
        val showSetup by setupViewModel.shouldShowWizard.collectAsStateWithLifecycle()

        when {
            settingsOpen -> {
                BackHandler { settingsOpen = false }
                SettingsScreen(onBack = {
                    settingsOpen = false
                    // Sprint 4 polish — if the user reset the wizard
                    // from inside Settings, surface that immediately
                    // when we return to the Discovery / setup gate.
                    setupViewModel.refresh()
                })
            }
            showSetup -> {
                ColorOsSetupScreen(viewModel = setupViewModel)
            }
            else -> {
                val peer = selectedPeer
                if (peer == null) {
                    DiscoveryScreen(
                        onPeerTap = { selectedPeer = it },
                        onSettings = { settingsOpen = true },
                    )
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
}
