package com.boldreminders.bold_reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter host. The "bold_reminders/alarm" channel is the remote control the
 * Flutter screens use to talk to the native alarm and calendar layer.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "bold_reminders/alarm"
        private const val REQ_NOTIFICATIONS = 42
        private const val REQ_CALENDAR = 43
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        AlarmService.ensureChannels(this)
        SyncJobs.schedule(this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "status" -> result.success(status())
                    "alarms" -> result.success(alarms())
                    "log" -> result.success(AlarmStore.logEntries(this))
                    "clearLog" -> { AlarmStore.clearLog(this); result.success(null) }
                    "sync" -> {
                        val trigger = call.argument<String>("trigger") ?: "manual"
                        Thread {
                            val s = try {
                                CalendarSync.sync(applicationContext, trigger)
                            } catch (e: Exception) {
                                AlarmStore.log(applicationContext, "error", "Sync ($trigger) failed: ${e.message}")
                                CalendarSync.Summary(0, 0, 0, 0, e.message)
                            }
                            runOnUiThread {
                                result.success(
                                    mapOf(
                                        "upcoming" to s.upcoming, "added" to s.added,
                                        "cancelled" to s.cancelled, "skipped" to s.skipped, "error" to s.error,
                                    )
                                )
                            }
                        }.start()
                    }
                    "calendars" -> {
                        val selected = CalendarSync.selectedIds(this)
                        result.success(CalendarSync.calendars(this).map {
                            mapOf(
                                "id" to it.id, "name" to it.name, "account" to it.account,
                                "color" to it.color, "selected" to (it.id in selected),
                            )
                        })
                    }
                    "setCalendars" -> {
                        val ids = (call.argument<List<Any>>("ids") ?: emptyList())
                            .map { (it as Number).toLong() }.toSet()
                        CalendarSync.setSelected(this, ids)
                        AlarmStore.log(this, "sync", "Ringing calendars changed: ${ids.size} selected")
                        result.success(null)
                    }
                    "scheduleTest" -> {
                        val minutes = call.argument<Int>("minutes") ?: 2
                        val at = System.currentTimeMillis() + minutes * 60_000L
                        val ok = AlarmScheduler.schedule(this, CalendarSync.TEST_ALARM_ID, "Test alarm", at, "Test")
                        if (ok) AlarmStore.log(this, "sync", "Test alarm scheduled for ${AlarmStore.timeText(at)}")
                        result.success(ok)
                    }
                    "cancelTest" -> {
                        AlarmScheduler.cancel(this, CalendarSync.TEST_ALARM_ID)
                        AlarmStore.log(this, "sync", "Test alarm cancelled")
                        result.success(null)
                    }
                    "requestCalendar" -> { requestCalendar(); result.success(null) }
                    "requestNotifications" -> { requestNotifications(); result.success(null) }
                    "openExactAlarmSettings" -> { openExactAlarmSettings(); result.success(null) }
                    "openFullScreenSettings" -> { openFullScreenSettings(); result.success(null) }
                    "openBatterySettings" -> { openBatterySettings(); result.success(null) }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("native_error", e.message, null)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR && CalendarSync.hasPermission(this)) {
            SyncJobs.schedule(this)
            Thread { CalendarSync.sync(applicationContext, "first run") }.start()
        }
    }

    private fun status(): Map<String, Any?> {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            nm.canUseFullScreenIntent() else true
        val calendarAllowed = CalendarSync.hasPermission(this)
        return mapOf(
            "calendar" to calendarAllowed,
            "notifications" to nm.areNotificationsEnabled(),
            "exactAlarm" to AlarmScheduler.canScheduleExact(this),
            "fullScreen" to fullScreen,
            "battery" to pm.isIgnoringBatteryOptimizations(packageName),
            "selectedCalendars" to (if (calendarAllowed) CalendarSync.selectedIds(this).size else 0),
            "lastSyncAt" to CalendarSync.lastSyncAt(this).takeIf { it > 0 },
            "lastError" to CalendarSync.lastError(this),
            "ringing" to (AlarmService.current != null),
            "sdk" to Build.VERSION.SDK_INT,
        )
    }

    private fun alarms(): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        return AlarmStore.pending(this).filter { it.at > now - 60_000L }.map {
            mapOf(
                "id" to it.id, "title" to it.title, "at" to it.at, "detail" to it.detail,
                "test" to (it.id == CalendarSync.TEST_ALARM_ID),
            )
        }
    }

    private fun appUri(): Uri = Uri.parse("package:$packageName")

    private fun requestCalendar() {
        if (!CalendarSync.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
        } else openAppDetails()
    }

    private fun requestNotifications() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appUri()))
        } else openAppDetails()
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, appUri()))
                return
            } catch (_: Exception) {}
        }
        openAppDetails()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri()))
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri()))
    }
}
