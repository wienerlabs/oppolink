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
import androidx.compose.material3.LinearProgressIndicator
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
import uniffi.oppolink_protocol.EchoStats

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
            if (state is ConnectionState.EchoInProgress) {
                EchoProgress(state as ConnectionState.EchoInProgress)
            }
            if (state is ConnectionState.EchoCompleted) {
                EchoResultCard(
                    completed = state as ConnectionState.EchoCompleted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Footer(
                state = state,
                onRunEcho = { viewModel.runEcho(peer) },
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
private fun EchoProgress(state: ConnectionState.EchoInProgress) {
    val fraction = if (state.total == 0) 0f else state.progress.toFloat() / state.total
    Column {
        Text(
            text = "Echo ${state.progress + 1} of ${state.total}",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EchoResultCard(
    completed: ConnectionState.EchoCompleted,
    modifier: Modifier = Modifier,
) {
    val s: EchoStats = completed.stats
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("L2CAP echo", style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = {},
                    label = { Text(phyLabel(completed.negotiatedPhy)) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Stat("p50", s.p50Ms)
            Stat("p95", s.p95Ms)
            Stat("min", s.minMs)
            Stat("max", s.maxMs)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${s.sampleCount} samples · target p50 < 30 ms on LE 2M",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(name: String, ms: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = String.format("%.1f ms", ms),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Footer(
    state: ConnectionState,
    onRunEcho: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val canRunEcho =
        state is ConnectionState.PsmExchanged || state is ConnectionState.EchoCompleted
    val isTerminal = state is ConnectionState.PsmExchanged ||
        state is ConnectionState.EchoCompleted ||
        state is ConnectionState.Failed ||
        state is ConnectionState.Idle

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canRunEcho) {
                Button(onClick = onRunEcho) {
                    Text(
                        if (state is ConnectionState.EchoCompleted) "Run echo again" else "Run echo",
                    )
                }
            }
            if (isTerminal) {
                OutlinedButton(onClick = onBack) { Text("Back") }
            } else {
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
    is ConnectionState.EchoInProgress -> Triple(
        "Echo in progress",
        "Sending 1 KB packets over L2CAP CoC at PSM ${state.psm}…",
        false,
    )
    is ConnectionState.EchoCompleted -> Triple(
        "Echo complete",
        "L2CAP socket closed after ${state.stats.sampleCount} round-trips.",
        false,
    )
    is ConnectionState.Failed -> Triple(
        "Connection failed",
        state.reason,
        true,
    )
}
