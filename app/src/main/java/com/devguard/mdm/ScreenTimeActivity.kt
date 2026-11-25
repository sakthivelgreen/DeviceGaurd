// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID

/** Screen time: usage today + 7 days, daily limits, temporary unlock, schedules (apps + sites). */
class ScreenTimeActivity : BaseActivity() {
    private var today: Map<String, Long> = emptyMap()
    private var week: List<Long> = emptyList()           // total per day, index 0 = today
    private var weekApps: Map<String, Long> = emptyMap()
    private var loaded = false
    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val LIMIT_OPTIONS = listOf(15, 30, 45, 60, 90, 120, 180, 240)

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        render()
        inBackground({
            val t = ScreenTime.usageToday(this)
            val days = (0..6).map { ScreenTime.usageForDay(this, it) }
            val wa = HashMap<String, Long>()
            days.forEach { d -> d.forEach { (p, v) -> wa[p] = (wa[p] ?: 0L) + v } }
            Triple(t, days, wa)
        }) { (t, days, wa) ->
            today = t; week = days.map { it.values.sum() }; weekApps = wa; loaded = true
            render()
        }
    }

    private fun label(pkg: String) = Blocklist.label(this, pkg)
    /** Reads the user apps (package, name) in the background, then calls [then] (D-02, P-02). */
    private fun withUserApps(then: (List<Pair<String, String>>) -> Unit) =
        inBackground({ Apps.userApps(this).map { it.first.packageName to it.second } }, then)

    private fun render() {
        val owner = Mdm(this).isOwner
        val access = ScreenTime.hasUsageAccess(this)
        val limits = ScreenTime.limits(this)
        val paused = Prefs.stSuspended(this)
        scrollPage("Screen time", back = true, subtitle = if (loaded) "Today: ${ScreenTime.fmt(today.values.sum())}" else "Loading…") {
            if (!owner) card { text("⚠️ Device Owner required", 14f, bold = true, color = Palette.ORANGE) }
            if (!access) card {
                text("⚠️ Usage access needed", 15f, bold = true, color = Palette.ORANGE)
                text("Daily limits and the usage dashboard need DeviceGuard to read app usage. Turn on Usage access for DeviceGuard, or run once on the PC:", 13f)
                text(ScreenTime.ADB_USAGE, 12f, bold = true).setTextIsSelectable(true)
                button("Open Usage access settings", outlined = true) {
                    runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
                text("Schedules work without it.", 12f).alpha = 0.6f
            }

            // ---- Today ----
            if (access) card("Today") {
                val top = (today.keys + limits.keys).distinct()
                    .sortedByDescending { today[it] ?: 0L }
                    .filter { (today[it] ?: 0L) >= 60_000 || it in limits }
                    .take(15)
                if (top.isEmpty()) text(if (loaded) "No app use recorded yet today." else "Loading…", 13f).alpha = 0.6f
                top.forEach { addView(usageRow(it, today[it] ?: 0L, limits[it], it in paused)) }
            }

            // ---- Daily limits ----
            card("Daily limits") {
                text("When an app reaches its limit it's paused until midnight. You can allow extra time from here.", 12f).alpha = 0.7f
                if (limits.isEmpty()) text("No limits yet.", 13f).alpha = 0.6f
                limits.toSortedMap().forEach { (pkg, min) ->
                    text("⏱  ${label(pkg)}: $min min/day", 15f).setOnClickListener { appMenu(pkg) }
                }
                button("Add a daily limit", outlined = true) { pickApp("Limit which app?") { setLimitDialog(it) } }.isEnabled = owner && access
            }

            // ---- Schedules ----
            card("Schedules") {
                text("Block apps and websites at set times, e.g. study hours or bedtime.", 12f).alpha = 0.7f
                val list = ScreenTime.schedules(this@ScreenTimeActivity)
                if (list.isEmpty()) text("No schedules yet.", 13f).alpha = 0.6f
                list.sortedBy { it.start }.forEach { s ->
                    val active = ScreenTime.isActive(s)
                    switchRow(
                        (if (active) "🔴 " else "") + s.name,
                        "${days(s.days)} · ${ScreenTime.fmtTime(s.start)}–${ScreenTime.fmtTime(s.end)} · ${s.apps.size} apps · ${s.sites.size} sites",
                        s.enabled, owner,
                    ) { _, on ->
                        ScreenTime.saveSchedule(this@ScreenTimeActivity, s.copy(enabled = on))
                        inBackground({ ScreenTime.evaluate(this@ScreenTimeActivity) })
                        render(); true
                    }
                    text("Edit ${s.name}", 12f, color = Palette.BLUE).setOnClickListener { editSchedule(s) }
                }
                button("Add a schedule", outlined = true) { editSchedule(null) }.isEnabled = owner
            }

            // ---- Last 7 days ----
            if (access && loaded) card("Last 7 days") {
                val max = (week.maxOrNull() ?: 1L).coerceAtLeast(1L)
                week.forEachIndexed { i, total ->
                    val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                    val name = if (i == 0) "Today" else DateFormat.getDateInstance(DateFormat.SHORT).format(day.time)
                    addView(bar(name, ScreenTime.fmt(total), (total * 100 / max).toInt(), Palette.BLUE))
                }
                section("Most used this week")
                weekApps.entries.sortedByDescending { it.value }.take(8).forEach { (p, v) ->
                    text("${label(p)}: ${ScreenTime.fmt(v)}", 14f)
                }
            }
        }
    }

    private fun days(mask: Int): String = when (mask) {
        127 -> "Every day"
        31 -> "Mon–Fri"
        96 -> "Weekends"
        else -> DAY_NAMES.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(" ")
    }

    private fun bar(name: String, value: String, percent: Int, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(context).apply { text = name; textSize = 13f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply { text = value; textSize = 13f })
        addView(top)
        addView(LinearProgressIndicator(context).apply {
            max = 100; progress = percent.coerceIn(0, 100)
            setIndicatorColor(color)
            trackCornerRadius = dp(4)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun usageRow(pkg: String, used: Long, limit: Int?, isPaused: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val icon = ImageView(context)
        Apps.loadIcon(icon, pkg)
        addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val until = ScreenTime.allowedUntil(this@ScreenTimeActivity, pkg)
        val status = when {
            isPaused -> " · ⛔ paused"
            until > 0 -> " · extra time until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))
            else -> ""
        }
        val pct = if (limit != null) (used * 100 / (limit * 60_000L)).toInt() else 0
        col.addView(bar(label(pkg), ScreenTime.fmt(used) + (limit?.let { " / $it min" } ?: "") + status, if (limit != null) pct else 0,
            if (pct >= 100) Palette.RED else if (pct >= 80) Palette.ORANGE else Palette.GREEN))
        addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        setOnClickListener { appMenu(pkg) }
    }

    private fun appMenu(pkg: String) {
        val opts = mutableListOf("Set daily limit…")
        if (pkg in ScreenTime.limits(this)) opts += "Remove limit"
        opts += listOf("Allow 15 more minutes", "Allow 30 more minutes", "Allow 1 more hour")
        MaterialAlertDialogBuilder(this).setTitle(label(pkg)).setItems(opts.toTypedArray()) { _, i ->
            when (opts[i]) {
                "Set daily limit…" -> setLimitDialog(pkg)
                "Remove limit" -> { ScreenTime.setLimit(this, pkg, null); reevaluate() }
                "Allow 15 more minutes" -> allow(pkg, 15)
                "Allow 30 more minutes" -> allow(pkg, 30)
                "Allow 1 more hour" -> allow(pkg, 60)
            }
        }.show()
    }

    private fun allow(pkg: String, min: Int) {
        inBackground({ ScreenTime.allowFor(this, pkg, min) }) { toast("${label(pkg)} allowed for $min min"); render() }
    }

    private fun reevaluate() = inBackground({ ScreenTime.evaluate(this) }) { render() }

    private fun setLimitDialog(pkg: String) {
        AppControl.protectedReason(this, pkg)?.let { toast(it); return }
        MaterialAlertDialogBuilder(this).setTitle("Daily limit: ${label(pkg)}")
            .setItems(LIMIT_OPTIONS.map { if (it < 60) "$it minutes" else "${it / 60} h ${if (it % 60 > 0) "${it % 60} min" else ""}".trim() }.toTypedArray()) { _, i ->
                ScreenTime.setLimit(this, pkg, LIMIT_OPTIONS[i]); reevaluate()
            }.show()
    }

    private fun pickApp(title: String, onPick: (String) -> Unit) = withUserApps { apps ->
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> onPick(apps[i].first) }.show()
    }

    // ---------- schedule editor ----------
    private fun editSchedule(existing: ScreenTime.Schedule?) {
        var s = existing ?: ScreenTime.Schedule(UUID.randomUUID().toString(), "Study time", 31, 9 * 60, 17 * 60, emptySet(), emptySet(), true)
        val col = column()
        col.text("Name", 12f).alpha = 0.7f
        val name = EditText(this).apply { setText(s.name); isSingleLine = true }
        col.addView(name)

        col.text("Days", 12f).alpha = 0.7f
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val boxes = DAY_NAMES.mapIndexed { i, d ->
            CheckBox(this).apply { text = d.take(2); isChecked = (s.days shr i) and 1 == 1; textSize = 11f }
                .also { dayRow.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        col.addView(dayRow)

        val timeBtn = materialButton("") {}
        fun refreshTime() { timeBtn.text = "Time: ${ScreenTime.fmtTime(s.start)} – ${ScreenTime.fmtTime(s.end)}" }
        refreshTime()
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                s = s.copy(start = h * 60 + m)
                TimePickerDialog(this, { _, h2, m2 -> s = s.copy(end = h2 * 60 + m2); refreshTime() }, s.end / 60, s.end % 60, true)
                    .apply { setTitle("End time") }.show()
            }, s.start / 60, s.start % 60, true).apply { setTitle("Start time") }.show()
        }
        col.addView(timeBtn)

        val appsBtn = materialButton("Apps to block: ${s.apps.size}", outlined = true) {}
        appsBtn.setOnClickListener { withUserApps { apps ->
            val pick = BooleanArray(apps.size) { apps[it].first in s.apps }
            MaterialAlertDialogBuilder(this).setTitle("Apps blocked during this schedule")
                .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), pick) { _, i, v -> pick[i] = v }
                .setPositiveButton("OK") { _, _ ->
                    s = s.copy(apps = apps.filterIndexed { i, _ -> pick[i] }.map { it.first }.toSet())
                    appsBtn.text = "Apps to block: ${s.apps.size}"
                }.show()
        } }
        col.addView(appsBtn)

        col.text("Websites to block (comma separated, e.g. youtube.com, instagram.com)", 12f).alpha = 0.7f
        val sites = EditText(this).apply {
            setText(s.sites.sorted().joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        col.addView(sites)
        col.text("Websites are blocked in Chrome (and in the VPN filter if used).", 11f).alpha = 0.6f

        val dlg = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "New schedule" else "Edit schedule")
            .setView(android.widget.ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val mask = boxes.foldIndexed(0) { i, acc, b -> if (b.isChecked) acc or (1 shl i) else acc }
                val siteSet = sites.text.toString().split(',', ' ', '\n').mapNotNull { WebFilter.normalize(it) }.toSet()
                val final = s.copy(name = name.text.toString().ifBlank { "Schedule" }, days = mask, sites = siteSet)
                if (final.days == 0 || final.start == final.end) { toast("Pick at least one day and different start/end times"); return@setPositiveButton }
                ScreenTime.saveSchedule(this, final)
                reevaluate()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dlg.setNeutralButton("Delete") { _, _ ->
            confirm("Delete ${existing.name}?", "Its apps and sites are unblocked if it's active now.", "Delete") {
                ScreenTime.deleteSchedule(this, existing.id); reevaluate()
            }
        }
        dlg.show()
    }
}
