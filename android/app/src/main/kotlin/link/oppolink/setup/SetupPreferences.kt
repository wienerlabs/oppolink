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

    private companion object {
        const val FILE = "oppolink_setup"
        const val KEY_COLOROS_COMPLETE = "coloros_setup_complete"
    }
}
