package com.boldreminders.bold_reminders

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract

/**
 * Background sync, using Android's own JobScheduler:
 *  - CALENDAR_CHANGED: runs a few seconds after calendar data on the phone changes
 *  - PERIODIC: about every 15 minutes as a safety net (Android may delay it)
 */
object SyncJobs {
    const val CALENDAR_CHANGED = 101
    const val PERIODIC = 102

    fun schedule(ctx: Context) {
        if (!CalendarSync.hasPermission(ctx)) return
        val js = ctx.getSystemService(JobScheduler::class.java)
        val component = ComponentName(ctx, SyncJobService::class.java)

        // Content-change jobs fire once, so this is re-armed after every run.
        js.schedule(
            JobInfo.Builder(CALENDAR_CHANGED, component)
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(2_000L)
                .setTriggerContentMaxDelay(15_000L)
                .build()
        )

        if (js.getPendingJob(PERIODIC) == null) {
            js.schedule(
                JobInfo.Builder(PERIODIC, component)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val trigger = if (params.jobId == SyncJobs.CALENDAR_CHANGED) "calendar change" else "background check"
        Thread {
            try {
                CalendarSync.sync(applicationContext, trigger)
            } catch (e: Exception) {
                AlarmStore.log(applicationContext, "error", "Sync ($trigger) failed: ${e.message}")
            } finally {
                jobFinished(params, false)
                if (params.jobId == SyncJobs.CALENDAR_CHANGED) SyncJobs.schedule(applicationContext)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
