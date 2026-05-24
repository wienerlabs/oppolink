package link.oppolink.settings

import android.content.ActivityNotFoundException
import android.content.Context
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

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
