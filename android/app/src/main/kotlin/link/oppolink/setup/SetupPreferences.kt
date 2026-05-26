package link.oppolink.setup

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps a single `SharedPreferences` file so the rest of the app does
 * not have to know about keys / edit-blocks.
 *
 * Only one flag in v1: whether the user has finished the ColorOS
 * battery-whitelist wizard. The wizard is opt-in - `false` is a valid
 * permanent state.
 */
@Singleton
class SetupPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Did the user complete (or explicitly skip) the ColorOS wizard? */
    var colorOsSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_COLOROS_COMPLETE, false)
        set(value) = prefs.edit { putBoolean(KEY_COLOROS_COMPLETE, value) }

    /**
     * User-chosen display nickname. `null` (or blank) means fall back to
     * `Build.MANUFACTURER + " " + Build.MODEL`. Truncation to the 16-byte
     * on-wire budget happens at the call site.
     */
    var customNickname: String?
        get() = prefs.getString(KEY_CUSTOM_NICKNAME, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) {
                remove(KEY_CUSTOM_NICKNAME)
            } else {
                putString(KEY_CUSTOM_NICKNAME, value)
            }
        }

    private companion object {
        const val FILE = "oppolink_setup"
        const val KEY_COLOROS_COMPLETE = "coloros_setup_complete"
        const val KEY_CUSTOM_NICKNAME = "custom_nickname"
    }
}
