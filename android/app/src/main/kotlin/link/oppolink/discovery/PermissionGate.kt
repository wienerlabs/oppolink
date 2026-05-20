package link.oppolink.discovery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import link.oppolink.bluetooth.BluetoothPermissions

/**
 * Renders [granted] only after the user has accepted every Bluetooth runtime
 * permission. On entry we request the permissions exactly once; the user can
 * retry from the inline button if any were denied.
 */
@Composable
fun PermissionGate(
    contentPadding: PaddingValues = PaddingValues(24.dp),
    granted: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(BluetoothPermissions.hasBle(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasPermissions = results.all { it.value } || BluetoothPermissions.hasBle(context)
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(BluetoothPermissions.all.toTypedArray())
        }
    }

    if (hasPermissions) {
        granted()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "OppoLink needs Bluetooth and microphone permissions to find nearby peers.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(BluetoothPermissions.all.toTypedArray()) }) {
                Text("Grant permissions")
            }
        }
    }
}
