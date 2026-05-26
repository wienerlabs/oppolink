package link.oppolink.discovery

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.oppolink.bluetooth.AudioLevels
import link.oppolink.bluetooth.ConnectionState
import link.oppolink.bluetooth.Peer
import uniffi.oppolink_protocol.JitterStats

@Composable
fun ConnectionScreen(
    peer: Peer,
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioLevels by viewModel.audioLevels.collectAsStateWithLifecycle()
    // Sprint 4 polish - the user must tap "Matches" on the SAS card
    // before "Start full-duplex call" enables. Scoped to the peer
    // address so changing peers resets the flag.
    var sasVerified by remember(peer.bdAddress) { mutableStateOf(false) }

    // Sprint 4 D12 - push-to-talk mode toggle. UI-local state; the
    // ViewModel only sees `setMuted(true|false)` calls. When PTT mode
    // is enabled we auto-mute and surface a hold-to-talk button.
    var pttMode by remember { mutableStateOf(false) }
    LaunchedEffect(pttMode) {
        // Entering PTT mode auto-mutes. Leaving PTT mode unmutes. Both
        // are intentional defaults; the user can still tap End call.
        viewModel.setMuted(pttMode)
    }

    DisposableEffect(peer.bdAddress) {
        viewModel.start(peer)
        onDispose {
            viewModel.setMuted(false)
            viewModel.cancel()
        }
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
            if (state is ConnectionState.PsmExchanged) {
                SasEmojiCard(
                    psm = state as ConnectionState.PsmExchanged,
                    verified = sasVerified,
                    onMatches = { sasVerified = true },
                    onDoesNotMatch = {
                        viewModel.cancel()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state is ConnectionState.Reconnecting) {
                ReconnectingCard(
                    reconnecting = state as ConnectionState.Reconnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (state) {
                is ConnectionState.InCall -> {
                    val inCall = state as ConnectionState.InCall
                    InCallCard(
                        inCall = inCall,
                        pttMode = pttMode,
                        onPttModeChange = { pttMode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pttMode) {
                        HoldToTalkButton(
                            onHoldStart = { viewModel.setMuted(false) },
                            onHoldEnd = { viewModel.setMuted(true) },
                        )
                    }
                    AudioLevelCard(
                        levels = audioLevels,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    JitterDiagnosticsCard(
                        stats = inCall.jitterStats,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is ConnectionState.CallEnded -> CallEndedCard(
                    ended = state as ConnectionState.CallEnded,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
            Spacer(Modifier.height(8.dp))
            Footer(
                state = state,
                sasVerified = sasVerified,
                onStartCall = { viewModel.startCall() },
                onEndCall = { viewModel.cancel() },
                onRetry = {
                    sasVerified = false
                    viewModel.start(peer)
                },
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
private fun InCallCard(
    inCall: ConnectionState.InCall,
    pttMode: Boolean,
    onPttModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Call in progress", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inCall.muted) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Muted") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text("Encrypted") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(phyLabel(inCall.negotiatedPhy)) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
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
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Push-to-talk", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Mic off unless you hold the button below.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pttMode, onCheckedChange = onPttModeChange)
            }
        }
    }
}

@Composable
private fun HoldToTalkButton(
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    val label = if (isPressed) "Speaking…" else "Hold to talk"
    Button(
        onClick = { /* gestures wired below */ },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onHoldStart()
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                            onHoldEnd()
                        }
                    },
                )
            },
        colors = if (isPressed) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
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
            Stat("reconnects", ended.reconnectAttempts)
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
private fun Stat(name: String, value: Int) = Stat(name, value.toString())

@Composable
private fun Stat(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReconnectingCard(
    reconnecting: ConnectionState.Reconnecting,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Reconnecting, attempt ${reconnecting.attempt}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Reopening L2CAP on PSM ${reconnecting.psm} - 5 s window.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudioLevelCard(levels: AudioLevels, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Audio level", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LevelRow(label = "mic", level = levels.tx)
            Spacer(Modifier.height(6.dp))
            LevelRow(label = "peer", level = levels.rx)
        }
    }
}

@Composable
private fun LevelRow(label: String, level: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(40.dp),
        )
        LinearProgressIndicator(
            progress = { (level / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
        )
        Text(
            "$level",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JitterDiagnosticsCard(stats: JitterStats?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Jitter buffer", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (stats == null) {
                Text(
                    "Warming up…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Card
            }
            Stat("buffered / target", "${stats.buffered} / ${stats.targetDepth}")
            Stat("pushes", stats.pushCount.toString())
            Stat("pops", stats.popCount.toString())
            Stat("PLC fires", stats.plcCount.toString())
            Stat("late drops", stats.lateArrivalCount.toString())
            Stat("duplicates", stats.duplicateCount.toString())
        }
    }
}

@Composable
private fun Footer(
    state: ConnectionState,
    sasVerified: Boolean,
    onStartCall: () -> Unit,
    onEndCall: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    // PsmExchanged with a non-empty SAS requires the user to confirm
    // the emoji match before "Start full-duplex call" enables. The
    // CallEnded path doesn't re-show the SAS card, so we gate on
    // sasVerified || state is CallEnded.
    val sasGateOk = sasVerified || state is ConnectionState.CallEnded
    val canStartCall =
        (state is ConnectionState.PsmExchanged && sasGateOk) ||
            state is ConnectionState.CallEnded
    val isInCall =
        state is ConnectionState.InCall || state is ConnectionState.Reconnecting
    val isFailed = state is ConnectionState.Failed
    val isTerminal = state is ConnectionState.PsmExchanged ||
        state is ConnectionState.CallEnded ||
        state is ConnectionState.Failed ||
        state is ConnectionState.Idle

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                isFailed -> Button(onClick = onRetry) { Text("Retry") }
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

/**
 * Signal-style SAS palette - 64 visually distinct glyphs the user can
 * tell apart at a glance. Index by byte AND 0x3F.
 */
private val SAS_EMOJI_PALETTE = listOf(
    "🐶", "🐱", "🦁", "🐯",
    "🦊", "🐻", "🐼", "🐨",
    "🐭", "🐹", "🐰", "🦝",
    "🐺", "🐗", "🐮", "🐷",
    "🐸", "🐵", "🦄", "🐔",
    "🐧", "🦆", "🦉", "🦅",
    "🐝", "🐛", "🐌", "🦋",
    "🐠", "🐟", "🐬", "🐳",
    "🐙", "🦞", "🦀", "🐢",
    "🐍", "🦎", "🐉", "🌵",
    "🌲", "🌳", "🌴", "🌱",
    "🌿", "☘️", "🍀", "🎋",
    "🌷", "🌹", "🌺", "🌸",
    "🌼", "🌻", "🌝", "🌞",
    "🌍", "🌎", "🌏", "⭐",
    "🌟", "🔥", "💧", "🌈",
)

private fun emojisForSas(bytes: ByteArray): String =
    bytes.joinToString("  ") { byte ->
        SAS_EMOJI_PALETTE[(byte.toInt() and 0x3F)]
    }

@Composable
private fun SasEmojiCard(
    psm: ConnectionState.PsmExchanged,
    verified: Boolean,
    onMatches: () -> Unit,
    onDoesNotMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (psm.sasEmoji.isEmpty()) return // legacy / pre-D13 path
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Verify SAS", style = MaterialTheme.typography.titleMedium)
                if (verified) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Verified") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Both phones should show the same six emoji. If they don't " +
                    "match, an attacker is forwarding your handshake - hang up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = emojisForSas(psm.sasEmoji),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Decimal fallback: ${"%06d".format(psm.sasCode.toLong())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!verified) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onMatches) { Text("Matches") }
                    OutlinedButton(onClick = onDoesNotMatch) { Text("Doesn't match") }
                }
            }
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
        "${phyLabel(state.negotiatedPhy)} · ChaCha20-Poly1305 ready - verify the SAS below.",
        false,
    )
    is ConnectionState.InCall -> Triple(
        "Call live",
        "Capture ⇄ Opus ⇄ L2CAP ⇄ playback on both ends.",
        false,
    )
    is ConnectionState.Reconnecting -> Triple(
        "Reconnecting (attempt ${state.attempt})",
        "L2CAP socket dropped - reopening on PSM ${state.psm} for up to 5 s.",
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
