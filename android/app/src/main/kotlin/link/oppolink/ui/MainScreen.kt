package link.oppolink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import uniffi.oppolink_protocol.greet

/**
 * Sprint 1 D1 verification surface: shows a string returned by the Rust core
 * via UniFFI. If you see "Hello from Rust, world!" the build pipeline works.
 */
@Composable
fun MainScreen() {
    val message = remember { greet("world") }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "OppoLink", style = MaterialTheme.typography.headlineMedium)
                Text(text = message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    OppoLinkTheme {
        // Preview cannot link against the native library, so we render a static
        // approximation. Run on a device or emulator to exercise the UniFFI call.
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("OppoLink", style = MaterialTheme.typography.headlineMedium)
                    Text("Hello from Rust, world!", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
