# `:app`

The application module. Compose UI, `MainActivity`, the (forthcoming)
`CallForegroundService`, and Hilt wiring.

## Boundary
- **In**: `:core-bluetooth`, `:core-audio`, `:core-protocol`, `:coloros-compat`.
- **Out**: the installable APK.

## Sprint 1 D1 scope
A single screen that calls `uniffi.oppolink_protocol.greet("world")` and shows
the returned string. If the string appears on screen, the build pipeline
(Rust → cargo-ndk → .so → UniFFI bindgen → Kotlin → Compose) is verified.

## Hilt
- `OppoLinkApp` carries `@HiltAndroidApp`.
- `MainActivity` carries `@AndroidEntryPoint`.
- No bindings yet (D1). Modules land in D2 when we wire the BLE scanner.
