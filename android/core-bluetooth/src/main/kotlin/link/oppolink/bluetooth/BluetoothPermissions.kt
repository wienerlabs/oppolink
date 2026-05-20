package link.oppolink.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Single source of truth for the runtime permission set OppoLink needs.
 *
 * Android 12+ (API 31) split the historical `BLUETOOTH` / `BLUETOOTH_ADMIN`
 * permissions into per-action flavors. We additionally need `RECORD_AUDIO`
 * before a call can start, and `POST_NOTIFICATIONS` on API 33+ so the
 * foreground-service notification (Sprint 3) can render.
 */
object BluetoothPermissions {

    /** Permissions required to scan, connect, and advertise on the current OS. */
    val bleRuntime: List<String> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            // API 29–30: legacy BLE permissions + FINE_LOCATION (yes, even for
            // a non-location scan — the platform requires it pre-S).
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    /** Audio + notification permissions required for the full call experience. */
    val callTime: List<String> by lazy {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Convenience: everything OppoLink ever asks for, in one list. */
    val all: List<String> by lazy { bleRuntime + callTime }

    /** `true` if every permission in [bleRuntime] has been granted. */
    fun hasBle(context: Context): Boolean = bleRuntime.all { isGranted(context, it) }

    /** `true` if every permission in [all] has been granted. */
    fun hasAll(context: Context): Boolean = all.all { isGranted(context, it) }

    fun missing(context: Context, requested: List<String>): List<String> =
        requested.filterNot { isGranted(context, it) }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
