# ColorOS Compatibility

Tracking matrix for ColorOS version × OppoLink behavior. Filled in as we test
on real hardware.

## Tested device matrix

| Device       | ColorOS | Android | OppoLink build | Result |
| ------------ | ------- | ------- | -------------- | ------ |
| Reno 11      | 14      | 14      | _pending_      | _pending_ |
| Reno 12      | 15      | 15      | _pending_      | _pending_ |
| Find X7      | 14      | 14      | _pending_      | _pending_ |
| Find N3      | 13      | 13      | _pending_      | _pending_ |
| A78          | 13      | 13      | _pending_      | _pending_ |
| A98          | 14      | 14      | _pending_      | _pending_ |
| K12          | 14      | 14      | _pending_      | _pending_ |

## Known ColorOS quirks (to verify on device)

1. **Startup Manager** (`com.coloros.safecenter`) gates background launches.
   OppoLink's foreground service does **not** count as a "background launch"
   while running, but cold-starting the service from a notification action
   does — the user must whitelist the app once.
2. **Battery Optimization** must be set to "Don't optimize" or aggressive
   sleep policies will kill the L2CAP socket within ~3 minutes of the screen
   turning off.
3. **App Auto-Launch**: irrelevant for our use case (we never auto-launch),
   but the wizard should still steer users away from "Disabled" since some
   ColorOS builds piggy-back foreground-service kill policies onto it.
4. **High-frequency BLE scans** are throttled on ColorOS 14+. Stick to one
   scan per 10s in the foreground; back off further on screen-off.

## Deep-link intents (to verify per version)

| Capability             | Intent (verify on device)                                                          |
| ---------------------- | ---------------------------------------------------------------------------------- |
| Startup Manager        | `com.coloros.safecenter/.startupapp.StartupAppListActivity`                        |
| Battery Optimization   | `android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (generic; ColorOS honors)  |
| Floating Window        | `android.settings.action.MANAGE_OVERLAY_PERMISSION` (generic)                      |
| Auto-Launch (legacy)   | `com.coloros.safecenter/.permission.startup.StartupAppListActivity`                |

Always wrap `startActivity(...)` with `try/catch (ActivityNotFoundException)`
and fall back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the app.

## How to add a new device to this matrix

1. Run `:app:installDebug` on the device.
2. Walk the first-run wizard end-to-end.
3. Make a call. Lock the screen. Confirm the call survives 5 minutes.
4. Record battery drain (Settings → Battery → App usage).
5. Update the table above with build SHA + drain figure.
