package link.oppolink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import link.oppolink.bluetooth.Peer
import link.oppolink.discovery.ConnectionScreen
import link.oppolink.discovery.DiscoveryScreen
import link.oppolink.discovery.PermissionGate

/**
 * Single-activity navigation: a permission gate, then either the discovery
 * surface or the per-peer connection screen. Kept as a hand-rolled toggle so
 * the v1 app stays free of the Navigation library — three screens is too
 * little to justify one.
 */
@Composable
fun MainScreen() {
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }

    PermissionGate {
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
