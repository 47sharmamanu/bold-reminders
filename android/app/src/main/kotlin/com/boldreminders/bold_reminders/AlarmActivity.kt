package com.boldreminders.bold_reminders

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The full-screen ringing screen. Shown over the lock screen by the alarm
 * notification's full-screen intent. One big Dismiss button.
 */
class AlarmActivity : Activity() {

    private val closer: () -> Unit = { runOnUiThread { finishAndRemoveTask() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ringing = AlarmService.current
        if (ringing == null) {
            finishAndRemoveTask()
            return
        }
        setContentView(buildView(ringing))
    }

    override fun onResume() {
        super.onResume()
        AlarmService.onStopped = closer
        if (AlarmService.current == null) finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AlarmService.current?.let { setContentView(buildView(it)) }
    }

    override fun onDestroy() {
        if (AlarmService.onStopped === closer) AlarmService.onStopped = null
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildView(r: AlarmService.Ringing): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111317"))
            setPadding(dp(32f), dp(48f), dp(32f), dp(48f))
        }

        val time = TextView(this).apply {
            text = AlarmStore.timeText(r.at)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(time)

        r.titles.forEach { t ->
            root.addView(TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, 0)
            })
        }

        root.addView(TextView(this).apply {
            text = "Bold Reminders"
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(56f))
        })

        val dismiss = Button(this).apply {
            text = "Dismiss"
            isAllCaps = false
            setTextColor(Color.parseColor("#111317"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(40f).toFloat()
                setColor(Color.parseColor("#FFD54F"))
            }
            setOnClickListener {
                startService(
                    Intent(this@AlarmActivity, AlarmService::class.java)
                        .setAction(AlarmService.ACTION_DISMISS)
                )
                finishAndRemoveTask()
            }
        }
        root.addView(dismiss, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80f)))
        return root
    }
}
