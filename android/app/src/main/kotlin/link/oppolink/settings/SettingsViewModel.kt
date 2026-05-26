package link.oppolink.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import link.oppolink.BuildConfig
import link.oppolink.colorosCompat.ColorOsSettings
import link.oppolink.setup.SetupPreferences

/**
 * Backing model for the Settings screen.
 *
 * Surfaces:
 *   - app version + wire protocol version (static),
 *   - ColorOS quick-actions (the same intent table as the first-run
 *     wizard, exposed permanently),
 *   - a "re-run setup wizard" toggle that flips the
 *     `coloros_setup_complete` flag back to `false` so the wizard fires
 *     the next time the Activity recomposes.
 *
 * No network anywhere; the screen reads from local state only.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val setupPrefs: SetupPreferences,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val appVersion: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE
    val isColorOs: Boolean = ColorOsSettings.isColorOs()
    val colorOsVersion: String = ColorOsSettings.colorOsVersion()

    /** Currently-persisted custom nickname, or empty string. */
    fun currentNickname(): String = setupPrefs.customNickname.orEmpty()

    /**
     * Persist `nickname` (trimmed). Blank input clears the override so
     * we fall back to `Build.MANUFACTURER + MODEL`. The 16-byte on-wire
     * truncation lives in the BLE advertise path; we keep the UI value
     * verbatim so the user sees what they typed.
     */
    fun updateNickname(nickname: String) {
        setupPrefs.customNickname = nickname.trim().ifBlank { null }
    }

    /** Whether the first-run wizard would fire today. */
    val wizardOutstanding: Boolean
        get() = isColorOs && !setupPrefs.colorOsSetupComplete

    fun openCapability(capability: ColorOsSettings.Capability) {
        val intent = ColorOsSettings.intentFor(appContext, capability)
        if (intent == null) {
            Log.w(TAG, "No deep link available for $capability on this device")
            return
        }
        try {
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Intent for $capability not resolved: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Intent for $capability denied: ${e.message}")
        }
    }

    /**
     * Flip the wizard back to "outstanding". MainScreen sees the flag
     * change via the SetupViewModel's StateFlow and re-renders the
     * ColorOsSetupScreen the next time the user navigates back.
     */
    fun resetWizard() {
        setupPrefs.colorOsSetupComplete = false
    }

    /**
     * Hand the source-code URL off to the system browser. The OppoLink
     * process itself never opens an internet socket - the ACTION_VIEW
     * intent is resolved by a separate browser process, which keeps the
     * offline-only invariant intact while still letting the user verify
     * the AGPL-3.0 source.
     */
    fun viewSource() = launchExternal(SOURCE_URL)

    /** Same shape as `viewSource`, but points at the AGPL-3.0 text. */
    fun viewLicense() = launchExternal(LICENSE_URL)

    private fun launchExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        try {
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser to handle $url: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
        const val SOURCE_URL = "https://github.com/wienerlabs/oppolink"
        const val LICENSE_URL = "https://github.com/wienerlabs/oppolink/blob/main/LICENSE"
    }
}
