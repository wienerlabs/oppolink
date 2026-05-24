# `:coloros-compat`

OEM-specific glue for ColorOS. Detects the running ColorOS version and exposes
deep links into Oppo's permission UI: Startup Manager, Battery Optimization,
Floating Window. Generic Android falls back to standard `Settings.ACTION_*`
intents.

## Boundary
- **In**: nothing — leaf module.
- **Out**: a `ColorOsSettings` helper that returns the right `Intent` for the
  current device or `null` when no deep link is available.

## Sprint 3 D10 scope (shipped)
- `ColorOsSettings.isColorOs()` — Build.MANUFACTURER sniff + reflective
  read of `ro.build.version.opporom` (and `…oplusrom` for OnePlus
  ColorOS derivatives).
- `ColorOsSettings.colorOsVersion()` — display-only version string for
  the wizard UI.
- `ColorOsSettings.intentFor(context, capability)` — returns the first
  Intent the PackageManager actually resolves, across:
    - Startup Manager (ColorOS 13 / 14 / 15 + legacy `com.oppo.safe`)
    - Battery optimization (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
    - Floating window (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`)
    - Auto-launch (legacy ColorOS)
  Falls back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` when no
  OEM intent resolves.

The `:app/.../setup/ColorOsSetupScreen` wizard is opt-in; skipping is
treated identically to completing. Wizard never re-appears once
dismissed (`SetupPreferences.colorOsSetupComplete = true`).

## Note on testing
ColorOS deep-link intents are not stable across point releases. Always wrap
the `startActivity` call with a `try/catch (ActivityNotFoundException)` and
fall back to the generic settings screen.
