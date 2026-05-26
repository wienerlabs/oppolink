package link.oppolink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import link.oppolink.R
import link.oppolink.bluetooth.ConnectionState
import link.oppolink.bluetooth.PeerConnector

/**
 * Foreground service that hosts an active OppoLink call.
 *
 * **Why a service?** A coroutine running inside `ConnectionViewModel`
 * dies the moment the activity goes away. ColorOS - and stock Android
 * 14+ - kill background audio capture aggressively. The service holds
 * the system's "active call" flag (via `foregroundServiceType="microphone"`)
 * so the OS keeps the mic open and the L2CAP socket alive while the
 * screen is off.
 *
 * **Pipeline ownership.** This class does NOT own the audio thread pool;
 * `PeerConnector` (Hilt singleton) does. The service is the lifecycle
 * owner - it tells the connector to `runCall(durationMs=null)` and
 * shows the persistent notification.
 *
 * **Notification action.** The notification carries a "End call" button
 * that posts [ACTION_STOP] back to this service. We cancel the running
 * call inside the connector and tear ourselves down.
 *
 * **Peer source.** We read the current peer from
 * `connector.state.value` (must be in `PsmExchanged` when the service
 * is started). This avoids parceling `Peer` through an `Intent` -
 * keeping the data class POJO.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject lateinit var connector: PeerConnector
    @Inject lateinit var batteryProbe: link.oppolink.diagnostics.BatteryProbe

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callJob: Job? = null
    private var tickerJob: Job? = null
    private var startElapsedMs: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            else -> {
                Log.w(TAG, "Unknown action ${intent?.action}; stopping")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val state = connector.state.value
        val peer = when (state) {
            is ConnectionState.PsmExchanged -> state.peer
            is ConnectionState.CallEnded -> state.peer
            is ConnectionState.InCall -> state.peer
            else -> {
                Log.w(TAG, "Cannot start call - connector is in $state")
                stopSelf()
                return
            }
        }

        ensureChannel()
        startElapsedMs = SystemClock.elapsedRealtime()
        startForeground(NOTIFICATION_ID, buildNotification(peer.nickname, 0, state))

        // Sprint 4 D14 instrumentation - capture battery drain across the
        // entire call. Stops + summarises in handleStop / on natural call
        // termination via the finally-block below.
        batteryProbe.start(scope)

        // The ticker re-reads connector.state every second so the
        // notification status suffix (Encrypted / Muted / Reconnecting)
        // refreshes alongside the elapsed-time counter without spawning a
        // second state collector.
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(1_000)
                val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
                updateNotification(peer.nickname, elapsed, connector.state.value)
            }
        }

        callJob?.cancel()
        callJob = scope.launch {
            try {
                connector.runCall(peer, durationMs = null)
            } finally {
                tickerJob?.cancel()
                Log.i(TAG, "call ended: ${batteryProbe.stop()}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleStop() {
        connector.cancel()
        callJob?.cancel()
        tickerJob?.cancel()
        Log.i(TAG, "stop action: ${batteryProbe.stop()}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.call_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.call_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(
        peerNick: String,
        elapsedMs: Long,
        state: ConnectionState,
    ): Notification {
        val mmss = formatElapsed(elapsedMs)
        val stopPi = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, CallForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The status suffix mirrors the in-app state cards so the user can
        // read call status from the lock screen without opening OppoLink.
        // The audio path is always AEAD-encrypted in v2 once we reach the
        // InCall state, so 'Encrypted' is the default suffix; 'Muted' wins
        // when push-to-talk has the mic stopped. Reconnecting takes over
        // entirely because the elapsed counter loses meaning while the
        // socket is down.
        val (title, statusSuffix) = when (state) {
            is ConnectionState.InCall ->
                getString(R.string.call_notification_title) to
                    if (state.muted) "Muted" else "Encrypted"
            is ConnectionState.Reconnecting ->
                getString(R.string.call_notification_reconnecting_title) to
                    "Attempt ${state.attempt}"
            else ->
                getString(R.string.call_notification_title) to "Encrypted"
        }
        val contentText = if (state is ConnectionState.Reconnecting) {
            "$peerNick · $statusSuffix"
        } else {
            "$peerNick · $mmss · $statusSuffix"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, getString(R.string.call_notification_stop), stopPi)
            .build()
    }

    private fun updateNotification(peerNick: String, elapsedMs: Long, state: ConnectionState) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(peerNick, elapsedMs, state))
    }

    // formatElapsed extracted to ElapsedFormatter.kt so unit tests can
    // pin the boundary cases (under-hour, hour mark, multi-hour, zero).

    companion object {
        private const val TAG = "CallFgSvc"
        const val CHANNEL_ID = "oppolink_calls"
        const val NOTIFICATION_ID = 7001
        const val REQUEST_STOP = 1

        const val ACTION_START = "link.oppolink.action.START_CALL"
        const val ACTION_STOP = "link.oppolink.action.STOP_CALL"

        /** Returns an Intent the UI uses to start the foreground call session. */
        fun startIntent(context: Context): Intent =
            Intent(context, CallForegroundService::class.java).apply { action = ACTION_START }

        /** Returns an Intent the UI (or the notification action) uses to end it. */
        fun stopIntent(context: Context): Intent =
            Intent(context, CallForegroundService::class.java).apply { action = ACTION_STOP }
    }
}
