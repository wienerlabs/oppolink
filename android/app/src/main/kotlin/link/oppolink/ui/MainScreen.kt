package link.oppolink.ui

import androidx.compose.runtime.Composable
import link.oppolink.discovery.DiscoveryScreen
import link.oppolink.discovery.PermissionGate

/**
 * Single-screen root. The discovery surface is the only thing v1 needs at
 * cold start — a future revision will route here only when no call is active.
 */
@Composable
fun MainScreen() {
    PermissionGate {
        DiscoveryScreen()
    }
}
