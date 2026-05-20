package link.oppolink.colorosCompat

import android.content.Intent

/**
 * Returns an `Intent` that takes the user to the ColorOS settings screen
 * required by the given [Capability], or `null` if no deep link is known.
 *
 * Stubbed in Sprint 1 D1. The real version-detection table lands in Sprint 3.
 */
object ColorOsSettings {
    enum class Capability {
        STARTUP_MANAGER,
        BATTERY_OPTIMIZATION,
        FLOATING_WINDOW,
        AUTO_LAUNCH,
    }

    fun intentFor(capability: Capability): Intent? = null
}
