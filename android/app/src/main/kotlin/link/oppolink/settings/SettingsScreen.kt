package link.oppolink.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import link.oppolink.colorosCompat.ColorOsSettings.Capability

/**
 * App settings + diagnostic surface. Reachable from the DiscoveryScreen
 * header. Reads everything from local state - no network, no telemetry.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    BackHandler { onBack() }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onBack = onBack)
            AppInfoSection(viewModel)
            if (viewModel.isColorOs) {
                ColorOsSection(viewModel)
            }
            PrivacySection()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.width(8.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun AppInfoSection(viewModel: SettingsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("About")
            InfoRow("App version", viewModel.appVersion)
            InfoRow("Version code", viewModel.versionCode.toString())
            InfoRow("Wire protocol", "v2 (Curve25519 + ChaCha20-Poly1305)")
            InfoRow("License", "AGPL-3.0")
        }
    }
}

@Composable
private fun ColorOsSection(viewModel: SettingsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("ColorOS")
                if (viewModel.colorOsVersion.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(viewModel.colorOsVersion) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            Text(
                "Re-open the ColorOS settings surfaces that gate background BLE calls. " +
                    "Each link drops you into the matching system screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            for (capability in Capability.entries) {
                CapabilityButton(
                    label = describeCapability(capability),
                    onClick = { viewModel.openCapability(capability) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = viewModel::resetWizard,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Re-run first-run setup wizard") }
        }
    }
}

@Composable
private fun PrivacySection() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Privacy")
            Text(
                "OppoLink collects no telemetry, opens no network sockets, and ships no " +
                    "accounts. All audio stays between you and your peer over a single " +
                    "L2CAP channel, encrypted end-to-end. The L2CAP link is treated as " +
                    "if it were the public internet - see THREAT_MODEL.md in the repo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

private fun describeCapability(capability: Capability): String = when (capability) {
    Capability.STARTUP_MANAGER -> "Startup Manager"
    Capability.BATTERY_OPTIMIZATION -> "Battery Optimization"
    Capability.FLOATING_WINDOW -> "Floating Window"
    Capability.AUTO_LAUNCH -> "Auto-Launch"
}

