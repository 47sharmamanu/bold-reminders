package com.boldreminders.bold_reminders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One scheduled alarm. [id] is unique per alarm; [at] is epoch millis. */
data class PendingAlarm(val id: Int, val title: String, val at: Long)

/**
 * Small on-device store (SharedPreferences) for scheduled alarms and the event log.
 * Survives app restarts and phone reboots, so alarms can be restored.
 */
object AlarmStore {
    private const val PREFS = "bold_reminders"
    private const val KEY_PENDING = "pending_alarms"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 300

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- pending alarms ----------

    @Synchronized
    fun pending(ctx: Context): List<PendingAlarm> {
        val raw = prefs(ctx).getString(KEY_PENDING, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = mutableListOf<PendingAlarm>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(PendingAlarm(o.optInt("id"), o.optString("title"), o.optLong("at")))
        }
        return out.sortedBy { it.at }
    }

    @Synchronized
    fun putPending(ctx: Context, alarm: PendingAlarm) {
        val list = pending(ctx).filter { it.id != alarm.id } + alarm
        savePending(ctx, list)
    }

    @Synchronized
    fun removePending(ctx: Context, id: Int) {
        savePending(ctx, pending(ctx).filter { it.id != id })
    }

    private fun savePending(ctx: Context, list: List<PendingAlarm>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("at", it.at))
        }
        prefs(ctx).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    // ---------- log ----------

    @Synchronized
    fun log(ctx: Context, kind: String, message: String) {
        val raw = prefs(ctx).getString(KEY_LOG, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val entry = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("kind", kind)
            .put("message", message)
        // newest first
        val next = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), MAX_LOG - 1)) next.put(arr.get(i))
        prefs(ctx).edit().putString(KEY_LOG, next.toString()).apply()
    }

    @Synchronized
    fun logEntries(ctx: Context): List<Map<String, Any>> {
        val raw = prefs(ctx).getString(KEY_LOG, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = mutableListOf<Map<String, Any>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                mapOf(
                    "at" to o.optLong("at"),
                    "kind" to o.optString("kind"),
                    "message" to o.optString("message"),
                )
            )
        }
        return out
    }

    @Synchronized
    fun clearLog(ctx: Context) {
        prefs(ctx).edit().putString(KEY_LOG, "[]").apply()
    }

    fun timeText(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
