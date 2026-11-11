package com.aiapp.builder

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
import android.os.PowerManager
import com.aiapp.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the app running and online while a model request is in flight, even after the app is sent to
 * the background or the screen turns off.
 *
 * Android suspends an ordinary app in the background, which cuts a streaming reply from Claude,
 * Gemini, a free-cloud model or an offline model mid-answer. A foreground service with a wake lock is
 * the supported way to keep the process and its network alive. Build Mode already does this with
 * [BuildService]; this is the same thing for chat, voice and the Reverse tab's model calls.
 *
 * Usage: call [begin] when a long network/model job starts and [end] when it finishes (always from a
 * `finally`). Calls are reference-counted, so overlapping jobs share one notification, and a short
 * linger keeps the service up across the brief gaps between back-to-back model calls so it does not
 * flicker. The service stops itself once the last job ends.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || active.get() <= 0) {
            stopNow()
            return START_NOT_STICKY
        }
        createChannel()
        val n = notification(label)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIApp:online")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun stopNow() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        runCatching {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Working in the background", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AI App is working")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val ID = 4711
        private const val ACTION_STOP = "com.aiapp.KEEPALIVE_STOP"
        private const val LINGER_MS = 4_000L

        private val active = AtomicInteger(0)
        @Volatile private var label: String = "Staying connected…"
        private val main = Handler(Looper.getMainLooper())
        private var stopRunnable: Runnable? = null

        /** Marks the start of a background-safe job. Pair every call with [end] in a `finally`. */
        fun begin(context: Context, what: String = "Staying connected…") {
            label = what
            active.incrementAndGet()
            stopRunnable?.let { main.removeCallbacks(it) }
            stopRunnable = null
            val app = context.applicationContext
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(Intent(app, KeepAliveService::class.java))
                } else {
                    app.startService(Intent(app, KeepAliveService::class.java))
                }
            }.onFailure {
                // Starting a foreground service can be refused if the app is already in the background;
                // the current job keeps running normally, just without the extra protection.
                com.aiapp.data.Log.w("KeepAlive", "Couldn't start keep-alive: ${it.message}")
            }
        }

        /** Marks the end of a job. The service stops a few seconds after the last job finishes. */
        fun end(context: Context) {
            if (active.decrementAndGet() > 0) return
            val app = context.applicationContext
            val r = Runnable {
                if (active.get() <= 0) {
                    runCatching { app.startService(Intent(app, KeepAliveService::class.java).setAction(ACTION_STOP)) }
                }
            }
            stopRunnable = r
            main.postDelayed(r, LINGER_MS)
        }
    }
}
