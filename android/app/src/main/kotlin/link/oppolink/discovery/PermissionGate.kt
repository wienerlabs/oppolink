package link.oppolink.discovery

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import link.oppolink.bluetooth.BluetoothPermissions

/**
 * Renders [granted] only after the user has accepted every Bluetooth runtime
 * permission. On entry we request the permissions exactly once; the user can
 * retry from the inline button if any were denied.
 *
 * The waiting state lists the specific permissions OppoLink still needs and a
 * one-line explanation per permission. We recompute the missing set on every
 * onResume so a manual grant in Settings flips the screen forward without
 * having to relaunch the activity.
 */
@Composable
fun PermissionGate(
    contentPadding: PaddingValues = PaddingValues(24.dp),
    granted: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(BluetoothPermissions.hasBle(context)) }
    var missingAll by remember {
        mutableStateOf(BluetoothPermissions.missing(context, BluetoothPermissions.all))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = BluetoothPermissions.hasBle(context)
        missingAll = BluetoothPermissions.missing(context, BluetoothPermissions.all)
    }

    // Re-check on resume so a Settings round-trip (user toggles a permission
    // from the system surface) lights the gate up without an activity recreate.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffectOnResume(lifecycle) {
        hasPermissions = BluetoothPermissions.hasBle(context)
        missingAll = BluetoothPermissions.missing(context, BluetoothPermissions.all)
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
                text = "OppoLink needs a few permissions before it can find nearby peers.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            MissingPermissionList(missingAll)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(missingAll.toTypedArray()) }) {
                Text("Grant ${missingAll.size} permission${if (missingAll.size == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun MissingPermissionList(missing: List<String>) {
    if (missing.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (permission in missing) {
                MissingPermissionRow(permission)
            }
        }
    }
}

@Composable
private fun MissingPermissionRow(permission: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "·",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                describePermission(permission),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                explainPermission(permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describePermission(permission: String): String = when (permission) {
    Manifest.permission.BLUETOOTH_SCAN -> "Nearby devices (scan)"
    Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices (connect)"
    Manifest.permission.BLUETOOTH_ADVERTISE -> "Nearby devices (broadcast)"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
    Manifest.permission.RECORD_AUDIO -> "Microphone"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> permission.substringAfterLast('.').replace('_', ' ').lowercase()
        .replaceFirstChar { it.uppercase() }
}

private fun explainPermission(permission: String): String = when (permission) {
    Manifest.permission.BLUETOOTH_SCAN ->
        "Find other phones running OppoLink within Bluetooth range."
    Manifest.permission.BLUETOOTH_CONNECT ->
        "Open the encrypted L2CAP channel with a peer you select."
    Manifest.permission.BLUETOOTH_ADVERTISE ->
        "Let nearby peers see this phone in their discovery list."
    Manifest.permission.ACCESS_FINE_LOCATION ->
        "Required by Android 11 and older to scan for nearby BLE devices."
    Manifest.permission.RECORD_AUDIO ->
        "Capture your voice while a call is active."
    Manifest.permission.POST_NOTIFICATIONS ->
        "Show the in-call status notification while OppoLink runs in the background."
    else -> "Required by OppoLink to operate."
}

/**
 * Compose-friendly observer that runs [onResume] every time the host
 * Lifecycle transitions to RESUMED. Used so the gate re-checks the system
 * permission state after a Settings detour without forcing an activity
 * recreate.
 */
@Composable
private fun DisposableEffectOnResume(
    lifecycle: Lifecycle,
    onResume: () -> Unit,
) {
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
