package com.boldreminders.bold_reminders

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

/**
 * Reads the calendars already synced to the phone (Android Calendar Provider)
 * and keeps one alarm per start minute for the next 48 hours.
 *
 * Rules:
 *  - only the calendars picked in Settings (default: names containing
 *    "meeting" or "deep work")
 *  - skip all-day, cancelled and declined events
 *  - same event on two calendars -> one alarm (merged on its iCal UID)
 *  - events at the same minute -> one alarm listing all of them
 *  - moved event -> old alarm cancelled, new one created
 *  - event first seen more than 5 minutes after its start -> skipped and logged
 *  - an alarm that already rang is never rescheduled
 */
object CalendarSync {
    const val TEST_ALARM_ID = 1
    private const val WINDOW_MS = 48 * 60 * 60 * 1000L
    private const val LATE_LIMIT_MS = 5 * 60 * 1000L
    private const val LOOKBACK_MS = 60 * 60 * 1000L
    private const val KEEP_IDS_MS = 3 * 24 * 60 * 60 * 1000L

    private const val KEY_SELECTED = "selected_calendars"
    private const val KEY_CHOSEN = "calendars_chosen"
    const val KEY_FIRED = "fired_ids"
    private const val KEY_SEEN = "seen_ids"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_LAST_ERROR = "last_sync_error"

    // Calendar Provider column names (string values of the CalendarContract constants)
    private const val C_ID = "_id"
    private const val C_NAME = "calendar_displayName"
    private const val C_ACCOUNT = "account_name"
    private const val C_COLOR = "calendar_color"
    private const val I_EVENT_ID = "event_id"
    private const val I_BEGIN = "begin"
    private const val I_TITLE = "title"
    private const val I_CAL_ID = "calendar_id"
    private const val I_ALL_DAY = "allDay"
    private const val I_STATUS = "eventStatus"
    private const val I_SELF_STATUS = "selfAttendeeStatus"
    private const val I_UID = "uid2445"
    private const val STATUS_CANCELED = 2
    private const val ATTENDEE_DECLINED = 2

    data class Cal(val id: Long, val name: String, val account: String, val color: Int)

    data class Summary(val upcoming: Int, val added: Int, val cancelled: Int, val skipped: Int, val error: String?)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // ---------- calendars ----------

    fun calendars(ctx: Context): List<Cal> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<Cal>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(C_ID, C_NAME, C_ACCOUNT, C_COLOR),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Cal(
                        c.getLong(0),
                        c.getString(1) ?: "(no name)",
                        c.getString(2) ?: "",
                        if (c.isNull(3)) 0 else c.getInt(3),
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
    }

    fun selectedIds(ctx: Context): Set<Long> {
        val p = AlarmStore.prefs(ctx)
        if (p.getBoolean(KEY_CHOSEN, false)) {
            return (p.getStringSet(KEY_SELECTED, emptySet()) ?: emptySet())
                .mapNotNull { it.toLongOrNull() }.toSet()
        }
        // Default until the user picks: the work calendars.
        return calendars(ctx).filter {
            val n = it.name.lowercase()
            n.contains("meeting") || n.contains("deep work")
        }.map { it.id }.toSet()
    }

    fun setSelected(ctx: Context, ids: Set<Long>) {
        AlarmStore.prefs(ctx).edit()
            .putBoolean(KEY_CHOSEN, true)
            .putStringSet(KEY_SELECTED, ids.map { it.toString() }.toSet())
            .apply()
    }

    fun lastSyncAt(ctx: Context): Long = AlarmStore.prefs(ctx).getLong(KEY_LAST_SYNC, 0L)
    fun lastError(ctx: Context): String? = AlarmStore.prefs(ctx).getString(KEY_LAST_ERROR, null)

    // ---------- sync ----------

    private data class Inst(val begin: Long, val title: String, val calName: String, val key: String)

    @Synchronized
    fun sync(ctx: Context, trigger: String): Summary {
        val now = System.currentTimeMillis()
        val p = AlarmStore.prefs(ctx)

        if (!hasPermission(ctx)) {
            val msg = "Calendar access not allowed"
            if (lastError(ctx) != msg) AlarmStore.log(ctx, "error", "Sync stopped: $msg")
            p.edit().putString(KEY_LAST_ERROR, msg).apply()
            return Summary(0, 0, 0, 0, msg)
        }

        val calIds = selectedIds(ctx)
        val calNames = calendars(ctx).associate { it.id to it.name }
        val instances = if (calIds.isEmpty()) emptyList() else readInstances(ctx, now - LOOKBACK_MS, now + WINDOW_MS, calIds, calNames)

        val fired = AlarmStore.idSet(ctx, KEY_FIRED)
        val seen = AlarmStore.idSet(ctx, KEY_SEEN)

        // One alarm per start minute, duplicates merged.
        val groups = instances
            .distinctBy { it.key }
            .groupBy { (it.begin / 60_000L).toInt() }

        val wanted = mutableMapOf<Int, PendingAlarm>()
        var skipped = 0
        for ((id, items) in groups) {
            if (id in fired) continue
            val begin = items.first().begin
            val title = items.map { it.title }.distinct().joinToString(" + ")
            val detail = items.map { it.calName }.distinct().joinToString(", ")
            when {
                begin >= now -> wanted[id] = PendingAlarm(id, title, begin, detail)
                begin >= now - LATE_LIMIT_MS -> {
                    // Arrived a little late: ring now (once).
                    wanted[id] = PendingAlarm(id, title, now + 3_000L, detail)
                }
                id !in seen -> {
                    skipped++
                    seen.add(id)
                    val late = (now - begin) / 60_000L
                    AlarmStore.log(ctx, "skipped", "Not rung, arrived $late min late: $title (${AlarmStore.timeText(begin)})")
                }
            }
        }

        // Diff against what is scheduled (the test alarm is left alone).
        val current = AlarmStore.pending(ctx).filter { it.id != TEST_ALARM_ID }.associateBy { it.id }
        var cancelled = 0
        for ((id, a) in current) {
            if (id !in wanted) {
                AlarmScheduler.cancel(ctx, id)
                cancelled++
                if (a.at > now) AlarmStore.log(ctx, "sync", "Alarm removed: ${a.title} (${AlarmStore.timeText(a.at)})")
            }
        }
        var added = 0
        for ((id, a) in wanted) {
            val existing = current[id]
            // Same minute, same titles -> leave it. (A late "ring now" alarm keeps its first time.)
            if (existing == null || existing.title != a.title || existing.detail != a.detail) {
                if (AlarmScheduler.schedule(ctx, id, a.title, a.at, a.detail) && existing == null) added++
            }
            seen.add(id)
        }

        // Prune old bookkeeping.
        val oldest = ((now - KEEP_IDS_MS) / 60_000L).toInt()
        AlarmStore.saveIdSet(ctx, KEY_SEEN, seen.filter { it >= oldest }.toSet())
        AlarmStore.saveIdSet(ctx, KEY_FIRED, fired.filter { it >= oldest || it == TEST_ALARM_ID }.toSet())

        p.edit().putLong(KEY_LAST_SYNC, now).remove(KEY_LAST_ERROR).apply()

        if (added > 0 || cancelled > 0 || trigger == "manual" || trigger == "restart" || trigger == "first run") {
            AlarmStore.log(
                ctx, "sync",
                "Sync ($trigger): ${wanted.size} upcoming, $added added, $cancelled removed"
            )
        }
        return Summary(wanted.size, added, cancelled, skipped, null)
    }

    private fun readInstances(
        ctx: Context, from: Long, to: Long, calIds: Set<Long>, calNames: Map<Long, String>
    ): List<Inst> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val selection = "$I_CAL_ID IN (${calIds.joinToString(",")})"
        val out = mutableListOf<Inst>()
        ctx.contentResolver.query(
            uri,
            arrayOf(I_EVENT_ID, I_BEGIN, I_TITLE, I_CAL_ID, I_ALL_DAY, I_STATUS, I_SELF_STATUS, I_UID),
            selection, null, "$I_BEGIN ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = !c.isNull(4) && c.getInt(4) != 0
                val status = if (c.isNull(5)) -1 else c.getInt(5)
                val self = if (c.isNull(6)) -1 else c.getInt(6)
                if (allDay || status == STATUS_CANCELED || self == ATTENDEE_DECLINED) continue
                val begin = c.getLong(1)
                val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(no title)"
                val calName = calNames[c.getLong(3)] ?: ""
                val uid = c.getString(7)
                val key = if (!uid.isNullOrBlank()) "$uid|$begin" else "$title|$begin"
                out.add(Inst(begin, title, calName, key))
            }
        }
        return out
    }
}
