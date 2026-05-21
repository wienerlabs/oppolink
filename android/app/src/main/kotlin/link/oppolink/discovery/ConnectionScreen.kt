package link.oppolink.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.oppolink.bluetooth.ConnectionState
import link.oppolink.bluetooth.Peer

@Composable
fun ConnectionScreen(
    peer: Peer,
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(peer.bdAddress) {
        viewModel.start(peer)
        onDispose { viewModel.cancel() }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(peer)
            StateCard(state, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Footer(
                isTerminal = state is ConnectionState.PsmExchanged ||
                    state is ConnectionState.Failed ||
                    state is ConnectionState.Idle,
                onCancel = {
                    viewModel.cancel()
                    onBack()
                },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun Header(peer: Peer) {
    Column {
        Text(text = peer.nickname, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = peer.bdAddress,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StateCard(state: ConnectionState, modifier: Modifier = Modifier) {
    val (label, detail, isError) = describe(state)
    Card(
        modifier = modifier,
        colors = if (isError) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun Footer(
    isTerminal: Boolean,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (isTerminal) {
            Button(onClick = onBack) { Text("Back to peer list") }
        } else {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun describe(state: ConnectionState): Triple<String, String, Boolean> = when (state) {
    ConnectionState.Idle -> Triple("Waiting", "Preparing to connect…", false)
    is ConnectionState.RoleDecided -> Triple(
        "Role: client",
        "We'll be the L2CAP client; the peer is hosting.",
        false,
    )
    is ConnectionState.Connecting -> Triple(
        "Connecting",
        "Opening GATT to ${state.peer.nickname}…",
        false,
    )
    is ConnectionState.Handshaking -> Triple(
        "Handshaking",
        "Reading handshake characteristic…",
        false,
    )
    is ConnectionState.PsmExchanged -> Triple(
        "PSM received: ${state.psm}",
        "GATT torn down; L2CAP socket open lands in Sprint 1 D4.",
        false,
    )
    is ConnectionState.Failed -> Triple(
        "Connection failed",
        state.reason,
        true,
    )
}
