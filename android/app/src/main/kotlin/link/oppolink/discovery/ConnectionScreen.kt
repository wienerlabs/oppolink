package link.oppolink.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
            when (state) {
                is ConnectionState.InCall -> InCallCard(
                    inCall = state as ConnectionState.InCall,
                    modifier = Modifier.fillMaxWidth(),
                )
                is ConnectionState.CallEnded -> CallEndedCard(
                    ended = state as ConnectionState.CallEnded,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
            Spacer(Modifier.height(8.dp))
            Footer(
                state = state,
                onStartCall = { viewModel.startCall() },
                onEndCall = { viewModel.cancel() },
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
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
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
private fun InCallCard(inCall: ConnectionState.InCall, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Call in progress", style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = {},
                    label = { Text(phyLabel(inCall.negotiatedPhy)) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Stat("frames sent", inCall.framesSent)
            Stat("frames received", inCall.framesReceived)
            Spacer(Modifier.height(4.dp))
            Text(
                "PSM ${inCall.psm} · 20 ms Opus VoIP frames over L2CAP (duplex)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CallEndedCard(ended: ConnectionState.CallEnded, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Call ended", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Stat("frames sent", ended.framesSent)
            Stat("frames received", ended.framesReceived)
            Spacer(Modifier.height(4.dp))
            Text(
                "PSM ${ended.psm} closed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(name: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Footer(
    state: ConnectionState,
    onStartCall: () -> Unit,
    onEndCall: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val canStartCall =
        state is ConnectionState.PsmExchanged || state is ConnectionState.CallEnded
    val isInCall = state is ConnectionState.InCall
    val isTerminal = state is ConnectionState.PsmExchanged ||
        state is ConnectionState.CallEnded ||
        state is ConnectionState.Failed ||
        state is ConnectionState.Idle

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                isInCall -> Button(onClick = onEndCall) { Text("End call") }
                canStartCall -> Button(onClick = onStartCall) {
                    Text(
                        if (state is ConnectionState.CallEnded) "Call again" else "Start full-duplex call",
                    )
                }
                else -> Unit
            }
            if (isTerminal) {
                OutlinedButton(onClick = onBack) { Text("Back") }
            } else if (!isInCall) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private fun phyLabel(phy: Int): String = when (phy) {
    2 -> "LE 2M"
    3 -> "LE Coded"
    1 -> "LE 1M"
    else -> "PHY ?"
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
        "Negotiated ${phyLabel(state.negotiatedPhy)} · ready to open L2CAP.",
        false,
    )
    is ConnectionState.InCall -> Triple(
        "Call live",
        "Capture ⇄ Opus ⇄ L2CAP ⇄ playback on both ends.",
        false,
    )
    is ConnectionState.CallEnded -> Triple(
        "Call complete",
        "L2CAP closed · ${state.framesSent} sent / ${state.framesReceived} received.",
        false,
    )
    is ConnectionState.Failed -> Triple(
        "Connection failed",
        state.reason,
        true,
    )
}
