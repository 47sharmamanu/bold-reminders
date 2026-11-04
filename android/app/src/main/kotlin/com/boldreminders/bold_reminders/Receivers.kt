package com.boldreminders.bold_reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by AlarmManager at the alarm time: hands over to the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Reminder"
        val at = intent.getLongExtra(AlarmScheduler.EXTRA_AT, System.currentTimeMillis())
        AlarmStore.removePending(context, id)

        val service = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra(AlarmScheduler.EXTRA_ID, id)
            .putExtra(AlarmScheduler.EXTRA_TITLE, title)
            .putExtra(AlarmScheduler.EXTRA_AT, at)
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            AlarmStore.log(context, "error", "Could not start ringing for \"$title\": ${e.message}")
        }
    }
}

/** Restores alarms after reboot, app update, or a time / time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "restart"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app update"
            Intent.ACTION_TIME_CHANGED -> "time change"
            Intent.ACTION_TIMEZONE_CHANGED -> "time-zone change"
            else -> "permission change"
        }
        AlarmScheduler.restoreAll(context, reason)
    }
}
