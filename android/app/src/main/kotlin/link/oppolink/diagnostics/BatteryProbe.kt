package link.oppolink.diagnostics

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sprint 4 D14 instrumentation. Samples `BATTERY_PROPERTY_CAPACITY` once
 * a minute while a call is active and computes a `%/hour` drain
 * estimate when the probe stops. The numbers feed the v0.1.0 "battery
 * drain on Reno 11" quality bar.
 *
 * No UI surface yet; the probe writes to logcat with the tag
 * "BatteryProbe" so `adb logcat -s BatteryProbe` during a 30-minute
 * hardware test captures the full sample list plus the summary line.
 *
 * Single instance scoped to the application (Hilt @Singleton). The
 * caller (currently `CallForegroundService`) hands it the coroutine
 * scope it should run on; that lets the probe die with the service
 * without keeping a long-lived scope of its own.
 */
@Singleton
class BatteryProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    /** Each entry: elapsed seconds since [start], battery capacity % (0..100). */
    private val samples = mutableListOf<Pair<Long, Int>>()
    private var startElapsedMs: Long = 0
    private var probeJob: Job? = null

    /** Begin sampling on the given [scope]. Idempotent; restarts the timer. */
    fun start(scope: CoroutineScope) {
        probeJob?.cancel()
        samples.clear()
        startElapsedMs = SystemClock.elapsedRealtime()
        val initial = readCapacity()
        samples.add(0L to initial)
        Log.i(TAG, "probe start: capacity=$initial%")
        probeJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val elapsedSec = (SystemClock.elapsedRealtime() - startElapsedMs) / 1_000
                val cap = readCapacity()
                samples.add(elapsedSec to cap)
                Log.i(TAG, "probe sample t=${elapsedSec}s capacity=$cap%")
            }
        }
    }

    /**
     * Stop sampling and return a human-readable summary. Returns
     * `"no samples"` if less than two data points were collected.
     */
    fun stop(): String {
        probeJob?.cancel()
        probeJob = null
        if (samples.size < 2) {
            Log.i(TAG, "probe stop: insufficient samples (${samples.size})")
            return "no samples"
        }
        val (firstSec, firstCap) = samples.first()
        val (lastSec, lastCap) = samples.last()
        val deltaCap = firstCap - lastCap
        val deltaSec = (lastSec - firstSec).coerceAtLeast(1)
        val hourlyDrain = deltaCap.toDouble() * 3_600.0 / deltaSec
        val summary = "battery: $firstCap%% -> $lastCap%% over ${deltaSec}s = " +
            "%.2f%%/hour".format(hourlyDrain)
        Log.i(TAG, "probe stop: $summary")
        return summary
    }

    private fun readCapacity(): Int =
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private companion object {
        const val TAG = "BatteryProbe"
        const val SAMPLE_INTERVAL_MS = 60_000L
    }
}
