package io.github.valnesfjord.tgwsproxyrs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * True while this service is the one that started the proxy.  The native
     * worker reports startup failures asynchronously via [NativeProxy.onNativeError];
     * when `running` flips to false underneath us, tear down the foreground
     * notification and stop instead of leaving "Running" up with no listener.
     */
    private var proxyStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Every stop goes through `stopSelf(lastStartId)`, never a bare
     * `stopSelf()`: a Stop followed quickly by a Start queues two commands,
     * and an unconditional stop handled first would destroy the service under
     * the pending `startForegroundService`, which the system then answers with
     * ForegroundServiceDidNotStartInTimeException.  With the id, the stop is
     * ignored once a newer start has arrived.
     */
    private var lastStartId = 0

    /** Arguments of the current run, kept so a crashed run can be restarted as-is. */
    private var currentArgs: String? = null
    private var runStartedAt = 0L
    private var restartAttempt = 0
    private val restartRunnable = Runnable { restartAfterFailure() }

    private var statusText: String? = null
    private val statsRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        scope.launch {
            ProxyBridge.running.collect { isRunning ->
                if (proxyStarted && !isRunning) {
                    proxyStarted = false
                    stopStats()
                    // A run that never reached its listener failed on its
                    // configuration (bad args, port taken): restarting it would
                    // only fail again.  A --check run ends on its own once the
                    // verdict is in, listener or not.  One that was serving and
                    // died, or a retry already in progress, is worth another go.
                    if (worthRestarting()) {
                        scheduleRestart()
                    } else {
                        giveUp()
                    }
                }
            }
        }
        scope.launch {
            // Only a run that reached its listener is worth replaying after a
            // reboot; one that fails on start would just flash an error.
            ProxyBridge.tgLink.collect { link ->
                if (link != null && proxyStarted) {
                    prefs().edit { putBoolean(PREF_WAS_RUNNING, true) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                if (NativeProxy.nativeIsRunning()) {
                    ProxyBridge.setRunning(true)
                    statusText = null
                    goForeground(getString(R.string.status_running))
                } else {
                    startProxy(intent.getStringExtra(EXTRA_ARGS) ?: DEFAULT_ARGS)
                }
            }
            ACTION_STOP -> stopProxy()
            else -> {
                if (NativeProxy.nativeIsRunning()) {
                    ProxyBridge.setRunning(true)
                    goForeground(getString(R.string.status_running))
                } else {
                    val args = prefs().getString(PREF_ARGS, null)
                    if (args != null) {
                        startProxy(args)
                    } else {
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(restartRunnable)
        stopStats()
        ProxyBridge.setRestarting(false)
        scope.cancel()
        stopNative()
        super.onDestroy()
    }

    /**
     * [retrying] marks a restart of a run that died on its own: the saved
     * arguments are left as they are (the field may have been edited since),
     * and a failure schedules the next attempt instead of stopping.
     */
    private fun startProxy(args: String, retrying: Boolean = false) {
        mainHandler.removeCallbacks(restartRunnable)
        if (!retrying) {
            restartAttempt = 0
            prefs().edit { putString(PREF_ARGS, args) }
        }
        ProxyBridge.setRestarting(false)
        currentArgs = args
        runStartedAt = SystemClock.elapsedRealtime()
        ProxyBridge.listenedSinceStart = false
        ProxyBridge.clearError()
        goForeground(getString(R.string.status_starting))
        proxyStarted = true
        val error = NativeProxy.nativeStart(withPersistentSecret(args))
        if (error != null) {
            proxyStarted = false
            if (retrying) {
                ProxyBridge.onLog(error)
                scheduleRestart()
                return
            }
            ProxyBridge.reportError(error)
            giveUp()
        } else if (ProxyBridge.error.value != null) {
            // NativeStart is async: the worker may have already failed and
            // reported the error while we were inside it, and the optimistic
            // flip below would paint "Running" over that error.
            proxyStarted = false
            if (retrying) {
                scheduleRestart()
                return
            }
            giveUp()
        } else {
            ProxyBridge.setRunning(true)
            if (NativeProxy.nativeIsRunning()) {
                goForeground(getString(R.string.status_running))
                startStats()
            } else {
                // The worker may already have exited (e.g. a --check run that
                // finished during nativeStart) before the optimistic flip, in
                // which case nothing will ever flip running back to false.
                // It may also have listened and died already: that is a
                // crash like any other, not a finished --check.
                proxyStarted = false
                if (retrying || worthRestarting()) {
                    scheduleRestart()
                } else {
                    giveUp()
                }
            }
        }
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun stopProxy() {
        proxyStarted = false
        mainHandler.removeCallbacks(restartRunnable)
        restartAttempt = 0
        ProxyBridge.setRestarting(false)
        stopStats()
        prefs().edit { putBoolean(PREF_WAS_RUNNING, false) }
        // Deliberately no `ProxyBridge.setRunning(false)` here.  The shim
        // refuses to start while the previous run is winding down, and the
        // worker only reports the stop once it is startable again, so
        // answering for it would put a Start button in front of the user
        // before a start could be accepted: tap Stop, tap Start, and the
        // shim's internal "proxy is still stopping" lands in the error line.
        // Leaving the running state is [stopNative]'s call instead: it waits
        // for the worker's report and answers for it only when none is coming
        // or none arrives.
        stopNative()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    /**
     * Without `--secret` the native side picks a random one per start, so a
     * START_STICKY restart or a Stop/Start would invalidate the proxy saved in
     * Telegram.  Generate one once and reuse it.
     */
    private fun withPersistentSecret(args: String): String {
        if (SECRET_FLAG.containsMatchIn(args)) return args
        val prefs = prefs()
        val secret = prefs.getString(PREF_SECRET, null) ?: ByteArray(16).let { bytes ->
            SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }.also { prefs.edit { putString(PREF_SECRET, it) } }
        return "$args --secret $secret"
    }

    private fun worthRestarting(): Boolean {
        val isCheck = currentArgs?.let { CHECK_FLAG.containsMatchIn(it) } == true
        return !isCheck && (ProxyBridge.listenedSinceStart || restartAttempt > 0)
    }

    /** Stop for good: nothing to bring back after a reboot either. */
    private fun giveUp() {
        mainHandler.removeCallbacks(restartRunnable)
        restartAttempt = 0
        ProxyBridge.setRestarting(false)
        prefs().edit { putBoolean(PREF_WAS_RUNNING, false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun scheduleRestart() {
        if (SystemClock.elapsedRealtime() - runStartedAt > STABLE_RUN_MS) {
            restartAttempt = 0
        }
        val delay = (RESTART_BASE_MS shl restartAttempt.coerceAtMost(5))
            .coerceAtMost(RESTART_MAX_MS)
        restartAttempt++
        ProxyBridge.setRestarting(true)
        ProxyBridge.onLog(getString(R.string.log_restarting, delay / 1000))
        goForeground(getString(R.string.status_restarting))
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delay)
    }

    private fun restartAfterFailure() {
        val args = currentArgs
        if (args == null) {
            ProxyBridge.setRestarting(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
            return
        }
        if (NativeProxy.nativeIsRunning()) {
            // Someone (the tile, the UI) started a run while we waited.
            ProxyBridge.setRestarting(false)
            proxyStarted = true
            ProxyBridge.setRunning(true)
            goForeground(getString(R.string.status_running))
            startStats()
            return
        }
        startProxy(args, retrying = true)
    }

    /**
     * Polled rather than pushed: Handler time stops in deep sleep, so this
     * never wakes the device, and the notification is only re-posted when the
     * text actually changed.
     */
    private fun startStats() {
        mainHandler.removeCallbacks(statsRunnable)
        statusText = null
        mainHandler.postDelayed(statsRunnable, STATS_INTERVAL_MS)
    }

    private fun stopStats() {
        mainHandler.removeCallbacks(statsRunnable)
        statusText = null
    }

    private fun refreshStatus() {
        if (!proxyStarted) return
        val parts = NativeProxy.nativeStats()?.split(' ')?.mapNotNull { it.toLongOrNull() }
        if (parts == null || parts.size != 3) return
        val text = getString(
            R.string.status_running_stats,
            parts[0],
            Formatter.formatShortFileSize(this, parts[2]),
            Formatter.formatShortFileSize(this, parts[1]),
        )
        if (text != statusText) {
            statusText = text
            goForeground(text)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val ACTION_START = "io.github.valnesfjord.tgwsproxyrs.START"
        const val ACTION_STOP = "io.github.valnesfjord.tgwsproxyrs.STOP"
        const val EXTRA_ARGS = "args"
        const val DEFAULT_ARGS =
            "--dc-ip 2:149.154.167.220 --dc-ip 4:149.154.167.220 --pinned-upstream ws " +
            "--ip-fail-cooldown 0 --ws-connect-timeout 4 --ws-fail-probe-timeout 4 " +
            "--pool-size 1 --quiet " +
            "--host 127.0.0.1 --link-ip 127.0.0.1"

        /**
         * [DEFAULT_ARGS] with the community Cloudflare domains as a fallback
         * tier.  `--ip-fail-cooldown 0`: with a fallback configured, a cooled
         * `--dc-ip` is skipped outright, so one lost SYN would send every
         * client to Cloudflare for the whole window.  At 0 each client still
         * tries direct first and only falls back if that dial fails.
         */
        const val DIRECT_CF_ARGS =
            "--dc-ip 2:149.154.167.220 --dc-ip 4:149.154.167.220 --default-domains " +
            "--ip-fail-cooldown 0 --ws-connect-timeout 4 --ws-fail-probe-timeout 4 " +
            "--pool-size 1 --quiet --host 127.0.0.1 --link-ip 127.0.0.1"
        const val PREFS = "tg_ws_proxy"
        const val PREF_ARGS = "args"

        /** The user left the proxy running; [BootReceiver] brings it back. */
        const val PREF_WAS_RUNNING = "was_running"
        internal const val PREF_SECRET = "secret"
        private val SECRET_FLAG = Regex("""(^|\s)--secret(\s|=|$)""")
        private val CHECK_FLAG = Regex("""(^|\s)--check(\s|=|$)""")

        private const val RESTART_BASE_MS = 2_000L
        private const val RESTART_MAX_MS = 60_000L

        /** A run that lasted this long resets the restart backoff. */
        private const val STABLE_RUN_MS = 60_000L

        private const val STATS_INTERVAL_MS = 5_000L

        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1

        /**
         * Runs the native shutdown off the main thread.  It has to outlive the
         * service: [onDestroy] cancels [scope] before it stops the proxy, so a
         * coroutine launched there would never run.  A single thread also keeps
         * a Stop button press and the [stopSelf] that follows it from entering
         * the JNI shim at the same time — and, now that the first of those two
         * waits for the worker's report, keeps the second from concluding
         * "nothing is running" about a worker the first is still winding down.
         */
        private val stopExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "proxy-stop").apply { isDaemon = true }
        }

        /**
         * How long the worker is given to report its stop.
         *
         * Its tail is a bounded `rt.shutdown_timeout(2s)` followed by the
         * callback, so this is that bound plus room for a loaded emulator, not
         * a guess at how long a shutdown takes.
         */
        private const val STOP_REPORT_TIMEOUT_MS = 3_000L

        private fun stopNative() {
            stopExecutor.execute {
                if (NativeProxy.nativeIsRunning()) {
                    NativeProxy.nativeStop()
                    awaitStopReport()
                } else {
                    // Nothing for the shim to stop.  Either nothing ever ran,
                    // and this write is the only one there will be, or an
                    // earlier stop task has already returned — in which case
                    // the running state was left back then, by the worker's
                    // report or by that task giving up on one.  What this
                    // cannot be is a stop still awaiting its report: the
                    // executor is single-threaded, so that task would not have
                    // let this one start.
                    ProxyBridge.setRunning(false)
                }
            }
        }

        /**
         * Block until `onNativeStopped` (or `onNativeError`) has flipped
         * [ProxyBridge.running] false.
         *
         * On [stopExecutor], never on the main thread — that is the whole point
         * of the executor, and the report comes from a native worker thread, so
         * nothing here waits on the main looper.
         */
        private fun awaitStopReport() {
            val timedOut = runBlocking {
                withTimeoutOrNull(STOP_REPORT_TIMEOUT_MS) {
                    ProxyBridge.running.first { !it }
                } == null
            }
            if (timedOut) {
                // Should be unreachable given the shim's bounded tail.  If a
                // report really is lost, a UI stuck on "Running" with no way
                // back is worse than a Start the shim might still refuse.
                ProxyBridge.setRunning(false)
            }
        }

        fun start(context: Context, args: String) {
            val intent = Intent(context, ProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ARGS, args)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
