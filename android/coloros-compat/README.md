# `:coloros-compat`

OEM-specific glue for ColorOS. Detects the running ColorOS version and exposes
deep links into Oppo's permission UI: Startup Manager, Battery Optimization,
Floating Window. Generic Android falls back to standard `Settings.ACTION_*`
intents.

## Boundary
- **In**: nothing — leaf module.
- **Out**: a `ColorOsSettings` helper that returns the right `Intent` for the
  current device or `null` when no deep link is available.

## Sprint 1 scope
Stubs only. Real intent map + version detection lands in Sprint 3 D10.

## Note on testing
ColorOS deep-link intents are not stable across point releases. Always wrap
the `startActivity` call with a `try/catch (ActivityNotFoundException)` and
fall back to the generic settings screen.
