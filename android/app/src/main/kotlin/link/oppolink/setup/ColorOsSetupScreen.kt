package link.oppolink.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.oppolink.colorosCompat.ColorOsSettings.Capability

/**
 * First-run wizard for ColorOS devices. Walks the user through the four
 * permission surfaces ColorOS gates background BLE calls behind. Each
 * capability has its own card with an "Open settings" deep link; the
 * user toggles "All set" or "Skip for now" to dismiss the wizard.
 *
 * Skipping is treated identically to completing - the wizard never
 * re-appears. We trust the user to come back manually if needed; the
 * call simply fails to survive screen-off until they whitelist.
 */
@Composable
fun ColorOsSetupScreen(
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val show by viewModel.shouldShowWizard.collectAsStateWithLifecycle()
    if (!show) {
        // MainScreen also gates on this StateFlow; this is a defensive
        // early return for the recomposition that races dismissal.
        return
    }
    val version = viewModel.colorOsVersion

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up OppoLink for ColorOS", style = MaterialTheme.typography.headlineSmall)
            Text(
                "ColorOS suspends background apps aggressively. Whitelist OppoLink " +
                    "so your calls survive screen-off and the L2CAP socket stays open.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (version.isNotBlank()) {
                AssistChip(
                    onClick = {},
                    label = { Text("Detected ColorOS $version") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            for (capability in Capability.entries) {
                CapabilityCard(
                    capability = capability,
                    onOpen = { viewModel.openCapability(capability) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { viewModel.markComplete() }) {
                    Text("Skip for now")
                }
                Button(onClick = { viewModel.markComplete() }) {
                    Text("All set")
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(capability: Capability, onOpen: () -> Unit) {
    val (title, description) = describe(capability)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpen) { Text("Open settings") }
        }
    }
}

private fun describe(capability: Capability): Pair<String, String> = when (capability) {
    Capability.STARTUP_MANAGER -> "Startup Manager" to
        "Lets OppoLink launch from a cold start when a peer initiates a call. " +
        "ColorOS Settings → Privacy → Startup Manager."
    Capability.BATTERY_OPTIMIZATION -> "Battery optimization" to
        "Set OppoLink to \"Don't optimize\" so ColorOS doesn't suspend the " +
        "L2CAP socket while the screen is off."
    Capability.FLOATING_WINDOW -> "Floating window" to
        "Optional - lets the call indicator stay on top of other apps " +
        "while a call is active."
    Capability.AUTO_LAUNCH -> "Auto-launch" to
        "Some ColorOS builds piggy-back the foreground-service kill " +
        "policy onto Auto-Launch. Enabling it keeps OppoLink resilient."
}
