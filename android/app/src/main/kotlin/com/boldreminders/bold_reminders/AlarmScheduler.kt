package com.boldreminders.bold_reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules alarms with AlarmManager.setAlarmClock: exact time, works in Doze,
 * and shows the alarm icon in the status bar like the phone's own Clock app.
 */
object AlarmScheduler {
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_TITLE = "alarm_title"
    const val EXTRA_AT = "alarm_at"

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    /** Returns true if the alarm was scheduled. */
    fun schedule(ctx: Context, id: Int, title: String, at: Long, detail: String = ""): Boolean {
        if (!canScheduleExact(ctx)) {
            AlarmStore.log(ctx, "error", "Could not schedule \"$title\": exact alarms not allowed")
            return false
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(at, showIntent(ctx))
        am.setAlarmClock(info, firePendingIntent(ctx, id, title, at))
        AlarmStore.putPending(ctx, PendingAlarm(id, title, at, detail))
        return true
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val existing = AlarmStore.pending(ctx).firstOrNull { it.id == id }
        val pi = firePendingIntent(ctx, id, existing?.title ?: "", existing?.at ?: 0L)
        am.cancel(pi)
        pi.cancel()
        AlarmStore.removePending(ctx, id)
    }

    /**
     * Re-registers every stored alarm with AlarmManager. Called after reboot,
     * app update, time or time-zone change. Alarms whose time passed while the
     * phone could not ring are logged as missed.
     */
    fun restoreAll(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        var restored = 0
        AlarmStore.pending(ctx).forEach { a ->
            if (a.at > now) {
                if (schedule(ctx, a.id, a.title, a.at, a.detail)) restored++
            } else {
                AlarmStore.removePending(ctx, a.id)
                if (a.id != CalendarSync.TEST_ALARM_ID) AlarmStore.addToIdSet(ctx, CalendarSync.KEY_FIRED, a.id)
                AlarmStore.log(
                    ctx, "missed",
                    "${a.title} (${AlarmStore.timeText(a.at)}) did not ring: phone was off or restarting"
                )
            }
        }
        AlarmStore.log(ctx, "sync", "Alarms restored after $reason: $restored")
    }

    fun next(ctx: Context): PendingAlarm? {
        val now = System.currentTimeMillis()
        return AlarmStore.pending(ctx).firstOrNull { it.at > now }
    }

    private fun firePendingIntent(ctx: Context, id: Int, title: String, at: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction("com.boldreminders.ALARM_$id")
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(
            ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
