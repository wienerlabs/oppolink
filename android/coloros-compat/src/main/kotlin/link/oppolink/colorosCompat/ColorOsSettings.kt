package link.oppolink.colorosCompat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Capability-by-capability deep-link table into the ColorOS settings
 * surfaces OppoLink needs unlocked for backgrounded calls to survive.
 *
 * Two stability caveats live here:
 *   1. **ColorOS component names move.** Oppo / Realme / OnePlus all ship
 *      copies of the same activity under slightly different package +
 *      activity names, and point releases rename them again. We try the
 *      most-recent variant first, then fall back to older known names,
 *      and finally to the platform-generic `Settings.ACTION_*` intent.
 *   2. **Generic Android.** When [isColorOs] returns `false` we hand
 *      back only the platform intent. The setup wizard skips itself
 *      entirely on stock Android.
 *
 * All caller-facing methods are total — they never throw. The wizard
 * surfaces are advisory and the user can always skip them.
 */
object ColorOsSettings {

    /** Capabilities the call path needs the user to whitelist. */
    enum class Capability {
        STARTUP_MANAGER,
        BATTERY_OPTIMIZATION,
        FLOATING_WINDOW,
        AUTO_LAUNCH,
    }

    /**
     * Heuristic ColorOS detection. ColorOS reports its own version through
     * a hidden system property — we read it via reflection so we don't
     * have to depend on the `@hide` `SystemProperties` API.
     *
     * On non-OPPO devices the property is absent, the reflection call
     * returns an empty string, and we fall back to the `Build.MANUFACTURER`
     * sniff (OnePlus and Realme are both OPPO-owned and run a ColorOS
     * derivative; we include them).
     */
    fun isColorOs(): Boolean {
        val raw = colorOsVersion()
        if (raw.isNotBlank()) return true
        val mfg = Build.MANUFACTURER.lowercase()
        return mfg == "oppo" || mfg == "oneplus" || mfg == "realme"
    }

    /**
     * Raw ColorOS version string, or empty if not running ColorOS. Useful
     * for telemetry / the wizard's "ColorOS 14" label.
     */
    fun colorOsVersion(): String = systemProperty("ro.build.version.opporom")
        .ifBlank { systemProperty("ro.build.version.oplusrom") }

    /**
     * Returns an `Intent` that takes the user to the relevant settings
     * screen, or `null` if no reasonable intent is available on this
     * device. The caller MUST wrap `startActivity` in `try / catch
     * (ActivityNotFoundException)` because OEM intents can disappear
     * between point releases.
     */
    fun intentFor(context: Context, capability: Capability): Intent? = when (capability) {
        Capability.STARTUP_MANAGER -> firstResolvable(
            context,
            // ColorOS 14 / 15
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            // ColorOS 13
            componentIntent("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            // Older Oppo / OnePlus
            componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            // Generic fallback
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                android.net.Uri.fromParts("package", context.packageName, null),
            ),
        )

        Capability.BATTERY_OPTIMIZATION -> firstResolvable(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                android.net.Uri.fromParts("package", context.packageName, null),
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        Capability.FLOATING_WINDOW -> firstResolvable(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(
                android.net.Uri.fromParts("package", context.packageName, null),
            ),
        )

        Capability.AUTO_LAUNCH -> firstResolvable(
            context,
            // ColorOS legacy "auto-launch" surface — same package as
            // Startup Manager but a different activity.
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
        )
    }?.also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Picks the first intent the platform actually knows how to resolve. */
    private fun firstResolvable(context: Context, vararg candidates: Intent?): Intent? {
        val pm = context.packageManager
        for (intent in candidates) {
            if (intent == null) continue
            if (intent.resolveActivity(pm) != null) return intent
        }
        return null
    }

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    @Suppress("PrivateApi")
    private fun systemProperty(name: String): String = try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getDeclaredMethod("get", String::class.java)
        (get.invoke(null, name) as? String).orEmpty()
    } catch (t: Throwable) {
        Log.d(TAG, "SystemProperties.get($name) reflection failed: ${t.message}")
        ""
    }

    private const val TAG = "ColorOsSettings"
}
