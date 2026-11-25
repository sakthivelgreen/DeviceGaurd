// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Screen time: daily app limits, schedules (apps + sites by time), temporary unlock and usage data.
 *
 * [evaluate] runs every minute from GuardService (TIME_TICK) and on screen on. It works out which apps
 * should be paused right now (limit reached or inside a schedule, minus temporary unlocks), suspends /
 * un-suspends only the apps it manages (Prefs.stSuspended), and keeps the scheduled site list
 * (Prefs.stSites) in sync with the Chrome rules and the VPN filter.
 */
object ScreenTime {
    data class Schedule(
        val id: String,
        val name: String,
        val days: Int,          // bit 0 = Monday … bit 6 = Sunday
        val start: Int,         // minutes after midnight
        val end: Int,           // minutes after midnight (end < start = overnight)
        val apps: Set<String>,
        val sites: Set<String>,
        val enabled: Boolean,
    ) {
        fun toJson() = JSONObject().put("id", id).put("name", name).put("days", days).put("start", start).put("end", end)
            .put("apps", JSONArray(apps.toList())).put("sites", JSONArray(sites.toList())).put("enabled", enabled)

        companion object {
            fun from(o: JSONObject) = Schedule(
                o.getString("id"), o.optString("name", "Schedule"), o.optInt("days", 127),
                o.optInt("start", 0), o.optInt("end", 0),
                o.optJSONArray("apps").toSet(), o.optJSONArray("sites").toSet(), o.optBoolean("enabled", true),
            )
        }
    }

    private fun JSONArray?.toSet(): Set<String> = if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

    // ---------- storage ----------
    fun limits(c: Context): Map<String, Int> {
        val o = runCatching { JSONObject(Prefs.getStr(c, Prefs.ST_LIMITS) ?: "{}") }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }

    @Synchronized // B-08: same lock as evaluate(), so JSON read-modify-writes can't overwrite each other
    fun setLimit(c: Context, pkg: String, minutes: Int?) {
        val o = JSONObject(limits(c).toMutableMap().apply { if (minutes == null) remove(pkg) else put(pkg, minutes) } as Map<*, *>)
        Prefs.setStr(c, Prefs.ST_LIMITS, o.toString())
        GuardService.refresh(c)
    }

    fun schedules(c: Context): List<Schedule> {
        val a = runCatching { JSONArray(Prefs.getStr(c, Prefs.ST_SCHEDULES) ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { runCatching { Schedule.from(a.getJSONObject(it)) }.getOrNull() }
    }

    @Synchronized
    fun saveSchedule(c: Context, s: Schedule) {
        val list = schedules(c).filter { it.id != s.id } + s
        Prefs.setStr(c, Prefs.ST_SCHEDULES, JSONArray(list.map { it.toJson() }).toString())
        GuardService.refresh(c)
    }

    @Synchronized
    fun deleteSchedule(c: Context, id: String) {
        Prefs.setStr(c, Prefs.ST_SCHEDULES, JSONArray(schedules(c).filter { it.id != id }.map { it.toJson() }).toString())
    }

    private fun tempAllow(c: Context): MutableMap<String, Long> {
        val o = runCatching { JSONObject(Prefs.getStr(c, Prefs.ST_TEMP_ALLOW) ?: "{}") }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }

    /** Lets [pkg] run for [minutes] even if a limit or schedule blocks it. */
    @Synchronized
    fun allowFor(c: Context, pkg: String, minutes: Int) {
        val m = tempAllow(c).apply { put(pkg, System.currentTimeMillis() + minutes * 60_000L) }
        Prefs.setStr(c, Prefs.ST_TEMP_ALLOW, JSONObject(m as Map<*, *>).toString())
        evaluate(c)
    }

    fun allowedUntil(c: Context, pkg: String): Long = tempAllow(c)[pkg]?.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun needed(c: Context) = limits(c).isNotEmpty() || schedules(c).any { it.enabled } || Prefs.stSuspended(c).isNotEmpty() ||
        Prefs.stSites(c).isNotEmpty()

    // ---------- usage access ----------
    fun hasUsageAccess(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    const val ADB_USAGE = "adb shell appops set ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow"

    private fun startOfDay(daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Foreground time per app since midnight (ms), from usage events. */
    fun usageToday(c: Context): Map<String, Long> = usageBetween(c, startOfDay(), System.currentTimeMillis())

    /** Foreground time per app for one past day (0 = today). */
    fun usageForDay(c: Context, daysAgo: Int): Map<String, Long> {
        val start = startOfDay(daysAgo)
        val end = if (daysAgo == 0) System.currentTimeMillis() else startOfDay(daysAgo - 1)
        return usageBetween(c, start, end)
    }

    private const val LOOKBACK_MS = 12 * 3600_000L

    private fun usageBetween(c: Context, start: Long, end: Long): Map<String, Long> {
        if (!hasUsageAccess(c)) return emptyMap()
        val usm = c.getSystemService(UsageStatsManager::class.java)
        // B-09: start reading 12 h early, so an app already on screen at [start] (e.g. open at midnight) is seen;
        // only the part inside [start, end] is counted.
        val events = usm.queryEvents(start - LOOKBACK_MS, end)
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        fun add(p: String, from: Long, to: Long) {
            val d = to - maxOf(from, start)
            if (d > 0) total[p] = (total[p] ?: 0L) + d
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open.putIfAbsent(e.packageName, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(e.packageName)?.let { add(e.packageName, it, e.timeStamp) }
            }
        }
        open.forEach { (p, t) -> add(p, t, end) } // still on screen
        return total
    }

    // ---------- schedule time maths ----------
    private fun dayBit(cal: Calendar): Int = 1 shl ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) // Mon=0 … Sun=6

    fun isActive(s: Schedule, now: Calendar = Calendar.getInstance()): Boolean {
        if (!s.enabled || s.days == 0 || s.start == s.end) return false
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = dayBit(now)
        val yesterday = dayBit((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })
        return if (s.start < s.end) (s.days and today) != 0 && minute >= s.start && minute < s.end
        else ((s.days and today) != 0 && minute >= s.start) || ((s.days and yesterday) != 0 && minute < s.end)
    }

    // ---------- engine ----------
    @Synchronized
    fun evaluate(c: Context) {
        val m = Mdm(c)
        if (!m.isOwner) return
        runCatching { Device.enforceAutoTime(c) } // B-05: limits count by the clock, so keep network time on
        val now = System.currentTimeMillis()

        // Prune expired temporary unlocks.
        val temp = tempAllow(c)
        if (temp.values.any { it <= now }) {
            temp.entries.removeAll { it.value <= now }
            Prefs.setStr(c, Prefs.ST_TEMP_ALLOW, JSONObject(temp as Map<*, *>).toString())
        }

        val reasons = HashMap<String, String>()
        val limits = limits(c)
        if (limits.isNotEmpty()) {
            val usage = usageToday(c)
            limits.forEach { (pkg, min) -> if ((usage[pkg] ?: 0L) >= min * 60_000L) reasons[pkg] = "daily limit ($min min) reached" }
        }
        val active = schedules(c).filter { isActive(it) }
        active.forEach { s -> s.apps.forEach { reasons.putIfAbsent(it, "\"${s.name}\" schedule") } }
        temp.keys.forEach { reasons.remove(it) }
        reasons.keys.removeAll { AppControl.protectedReason(c, it) != null }

        val desired = reasons.keys
        val current = Prefs.stSuspended(c)
        val toSuspend = desired - current
        val toRelease = current - desired
        // Holds: releasing only drops Screen time's claim. Apps also paused by you or by approval stay paused.
        val failed = Holds.set(c, Hold.SUSPEND_SCREEN_TIME, toSuspend, true)
        Holds.set(c, Hold.SUSPEND_SCREEN_TIME, toRelease, false)
        (toSuspend - failed.toSet()).forEach { notify(c, it, reasons[it] ?: "screen time") }

        // Scheduled sites → Chrome rules + VPN filter.
        val sites = active.flatMap { it.sites }.toSet()
        if (sites != Prefs.stSites(c)) {
            Prefs.setStSites(c, sites)
            onDomainsChanged(c)
            runCatching { ChromePolicy.apply(c) }
        }
    }

    private fun notify(c: Context, pkg: String, why: String) =
        Notify.post(c, Channel.SCREEN_TIME, ("st$pkg").hashCode(), "${Blocklist.label(c, pkg)} is paused", "Screen time: $why.")

    fun fmt(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    fun fmtTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
}
