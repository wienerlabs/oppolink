package link.oppolink.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import link.oppolink.colorosCompat.ColorOsSettings

/**
 * First-run wizard backing model.
 *
 * The wizard only appears on ColorOS — on stock Android `shouldShowWizard`
 * is `false` and `MainScreen` falls straight through to the discovery
 * surface. Completion is one-way: there is no "show me again" affordance
 * in v1; the user can always tap the per-capability "Open settings"
 * action manually from a future settings screen if they need to revisit.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setupPrefs: SetupPreferences,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _shouldShowWizard = MutableStateFlow(
        ColorOsSettings.isColorOs() && !setupPrefs.colorOsSetupComplete,
    )
    val shouldShowWizard: StateFlow<Boolean> = _shouldShowWizard.asStateFlow()

    /** Display-only ColorOS version string, e.g. "V14.0.1". Empty on stock Android. */
    val colorOsVersion: String get() = ColorOsSettings.colorOsVersion()

    /**
     * Fire the deep-link Intent for [capability]. Swallow
     * `ActivityNotFoundException` — point-release variations in ColorOS
     * routinely break component names and the wizard is advisory; the
     * worst case is the user has to navigate the settings tree manually.
     */
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
            // Some OEMs lock down third-party deep links to internal
            // settings activities; nothing we can do beyond logging.
            Log.w(TAG, "Intent for $capability denied: ${e.message}")
        }
    }

    /** Persist that the wizard has been seen + dismissed. */
    fun markComplete() {
        setupPrefs.colorOsSetupComplete = true
        _shouldShowWizard.value = false
    }

    /**
     * Re-read the persisted flag and re-evaluate. MainScreen calls this
     * after returning from SettingsScreen so a `Re-run wizard` reset
     * there propagates back to the gate.
     */
    fun refresh() {
        _shouldShowWizard.value = ColorOsSettings.isColorOs() && !setupPrefs.colorOsSetupComplete
    }

    private companion object {
        const val TAG = "SetupViewModel"
    }
}
