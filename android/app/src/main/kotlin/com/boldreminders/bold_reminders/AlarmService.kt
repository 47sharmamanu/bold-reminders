package com.boldreminders.bold_reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Rings the alarm: loops the alarm tone on the ALARM audio stream, vibrates,
 * and shows a full-screen notification. Stops on Dismiss, or after 10 minutes
 * (then leaves a "Missed alarm" notification and a log entry).
 */
class AlarmService : Service() {

    data class Ringing(val titles: List<String>, val at: Long, val startedAt: Long)

    companion object {
        const val ACTION_START = "com.boldreminders.action.START"
        const val ACTION_DISMISS = "com.boldreminders.action.DISMISS"

        const val CHANNEL_ALARM = "alarm_ringing"
        const val CHANNEL_MISSED = "alarm_missed"
        private const val NOTIF_RINGING = 1001
        private const val NOTIF_MISSED_BASE = 2000

        const val RING_LIMIT_MS = 10 * 60 * 1000L

        /** What is ringing right now (read by the alarm screen). */
        @Volatile var current: Ringing? = null

        /** Set by the alarm screen so it can close itself when ringing stops. */
        @Volatile var onStopped: (() -> Unit)? = null

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
                val ch = NotificationChannel(CHANNEL_ALARM, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Shows the alarm while it rings"
                ch.setSound(null, null) // the service plays the sound itself
                ch.enableVibration(false) // the service vibrates itself
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                ch.setBypassDnd(true)
                nm.createNotificationChannel(ch)
            }
            if (nm.getNotificationChannel(CHANNEL_MISSED) == null) {
                val ch = NotificationChannel(CHANNEL_MISSED, "Missed alarms", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Alarms that rang for 10 minutes without being dismissed"
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { onMissed() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Reminder"
                val at = intent.getLongExtra(AlarmScheduler.EXTRA_AT, System.currentTimeMillis())
                start(title, at)
            }
            ACTION_DISMISS -> onDismissed()
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(title: String, at: Long) {
        ensureChannels(this)
        val existing = current
        if (existing != null) {
            // Two events at the same time: one alarm screen listing both.
            current = existing.copy(titles = existing.titles + title)
            notifyRinging()
            AlarmStore.log(this, "fired", "Rang: $title (together with ${existing.titles.joinToString()})")
            return
        }
        current = Ringing(listOf(title), at, System.currentTimeMillis())
        val notification = buildRingingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_RINGING, notification)
        }
        acquireWakeLock()
        startSound()
        startVibration()
        handler.postDelayed(timeout, RING_LIMIT_MS)
        AlarmStore.log(this, "fired", "Rang: $title (${AlarmStore.timeText(at)})")
    }

    private fun onDismissed() {
        val r = current ?: run { stopSelf(); return }
        val secs = (System.currentTimeMillis() - r.startedAt) / 1000
        AlarmStore.log(this, "dismissed", "Dismissed after ${secs}s: ${r.titles.joinToString()}")
        finish()
    }

    private fun onMissed() {
        val r = current ?: return
        AlarmStore.log(this, "missed", "Missed (rang 10 min, not dismissed): ${r.titles.joinToString()}")
        postMissedNotification(r)
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        stopSoundAndVibration()
        releaseWakeLock()
        current = null
        onStopped?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopSoundAndVibration()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun notifyRinging() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_RINGING, buildRingingNotification())
    }

    private fun buildRingingNotification(): Notification {
        val r = current
        val title = r?.titles?.joinToString(" + ") ?: "Reminder"
        val whenText = r?.let { AlarmStore.timeText(it.at) } ?: ""

        val fullScreen = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getService(
            this, 1,
            Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Dismiss", dismiss
        ).build()

        return Notification.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Alarm · $whenText")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(dismissAction)
            .build()
    }

    private fun postMissedNotification(r: Ringing) {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Missed alarm: ${r.titles.joinToString(" + ")}")
            .setContentText("At ${AlarmStore.timeText(r.at)} · rang 10 minutes, not dismissed")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val id = NOTIF_MISSED_BASE + ((r.at / 60000L) % 100000L).toInt()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    // ---------- sound, vibration, wake lock ----------

    private fun startSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates: List<Uri?> = listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            if (uri == null) continue
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                // try the next sound
            }
        }
        AlarmStore.log(this, "error", "No alarm sound could be played")
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        val pattern = longArrayOf(0, 800, 600)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
    }

    private fun stopSoundAndVibration() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoldReminders:ringing").apply {
            acquire(RING_LIMIT_MS + 60_000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
