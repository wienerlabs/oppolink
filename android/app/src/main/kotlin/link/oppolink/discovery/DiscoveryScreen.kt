package link.oppolink.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.oppolink.bluetooth.Peer
import link.oppolink.bluetooth.PeerAdvertiser
import link.oppolink.bluetooth.PeerScanner

@Composable
fun DiscoveryScreen(
    onPeerTap: (Peer) -> Unit,
    onSettings: () -> Unit = {},
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            DiscoveryHeader(state, onSettings = onSettings)
            Spacer(Modifier.height(16.dp))
            ErrorBanner(state)
            PeerList(state.peers, onPeerTap = onPeerTap, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DiscoveryHeader(state: DiscoveryUiState, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "OppoLink", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "${state.nickname} • ${if (state.isLive) "live" else "idle"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${state.peers.size} peer${if (state.peers.size == 1) "" else "s"} visible",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        androidx.compose.material3.TextButton(onClick = onSettings) {
            Text("Settings")
        }
    }
}

@Composable
private fun ErrorBanner(state: DiscoveryUiState) {
    val scanError = (state.scanState as? PeerScanner.State.Error)?.message
    val advertiseError = (state.advertiseState as? PeerAdvertiser.State.Error)?.message
    val message = listOfNotNull(scanError, advertiseError).joinToString(" / ")
    if (message.isBlank()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun PeerList(
    peers: List<Peer>,
    onPeerTap: (Peer) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (peers.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Looking for nearby peers…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(peers, key = { it.bdAddress }) { peer ->
            PeerRow(peer, onTap = { onPeerTap(peer) })
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, onTap: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RssiIndicator(peer.rssi)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.nickname.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = peer.bdAddress,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${peer.rssi} dBm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 4-step ASCII-art signal indicator, kept Composable-only. */
@Composable
private fun RssiIndicator(rssi: Int) {
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            val active = i <= bars
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = (4 + i * 3).dp)
                    .padding(end = 2.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}
