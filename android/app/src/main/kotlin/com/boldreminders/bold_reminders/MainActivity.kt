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
 * Flutter screens use to talk to the native alarm layer.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "bold_reminders/alarm"
        private const val TEST_ALARM_ID = 1
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        AlarmService.ensureChannels(this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "status" -> result.success(status())
                    "log" -> result.success(AlarmStore.logEntries(this))
                    "clearLog" -> { AlarmStore.clearLog(this); result.success(null) }
                    "scheduleTest" -> {
                        val minutes = call.argument<Int>("minutes") ?: 2
                        val at = System.currentTimeMillis() + minutes * 60_000L
                        val ok = AlarmScheduler.schedule(this, TEST_ALARM_ID, "Test alarm", at)
                        if (ok) AlarmStore.log(this, "sync", "Test alarm scheduled for ${AlarmStore.timeText(at)}")
                        result.success(ok)
                    }
                    "cancelTest" -> {
                        AlarmScheduler.cancel(this, TEST_ALARM_ID)
                        AlarmStore.log(this, "sync", "Test alarm cancelled")
                        result.success(null)
                    }
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

    private fun status(): Map<String, Any?> {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val next = AlarmScheduler.next(this)
        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            nm.canUseFullScreenIntent() else true
        return mapOf(
            "notifications" to nm.areNotificationsEnabled(),
            "exactAlarm" to AlarmScheduler.canScheduleExact(this),
            "fullScreen" to fullScreen,
            "battery" to pm.isIgnoringBatteryOptimizations(packageName),
            "nextAlarmAt" to next?.at,
            "nextAlarmTitle" to next?.title,
            "ringing" to (AlarmService.current != null),
            "sdk" to Build.VERSION.SDK_INT,
        )
    }

    private fun appUri(): Uri = Uri.parse("package:$packageName")

    private fun requestNotifications() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
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
