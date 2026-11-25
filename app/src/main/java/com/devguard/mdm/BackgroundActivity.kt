// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.pm.ApplicationInfo
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.DateFormat
import java.util.Date

/** Background apps: force stop, and stop chosen apps whenever the screen turns off. */
class BackgroundActivity : BaseActivity() {

    /** Apps that need to run in the background to work (messages, alarms) — shown with a warning. */
    private val NEEDS_BACKGROUND = setOf(
        "com.whatsapp", "org.telegram.messenger", "app.nicegram", "com.zoho.chat", "com.zoho.mail",
        "com.google.android.gm", "com.android.deskclock", "com.zoho.accounts.oneauth", "com.spotify.music",
    )

    /** One user app with its row's state, read off the main thread (P-02). */
    private data class Item(val info: ApplicationInfo, val label: String, val stopped: Boolean, val restricted: Boolean?)

    private var items: List<Item>? = null // null until the first load finishes

    override fun onResume() {
        super.onResume()
        if (!isFinishing) { render(); load() } // shows the last list at once, then refreshes it
    }

    /** Reads the user apps, their names and stopped / Restricted state in the background, then redraws. */
    private fun load() = inBackground({
        Apps.userApps(this).map { (info, label) ->
            Item(info, label, AppControl.isStopped(this, info.packageName), AppControl.osRestricted(this, info.packageName))
        }
    }) { items = it; render() }

    private fun stopNow(pkg: String, label: String) =
        inBackground({ AppControl.forceStop(this, pkg) }) { err -> toast(err ?: "$label stopped"); load() }

    private fun render() {
        val m = Mdm(this)
        val owner = m.isOwner
        val listed = Prefs.noBackgroundPkgs(this)
        val apps = items?.sortedWith(compareBy({ it.info.packageName !in listed }, { it.label.lowercase() }))

        scrollPage("Background apps", back = true, subtitle = "${listed.size} apps can't run in the background") {
            if (!owner) card { text("⚠️ Device Owner required", 14f, bold = true, color = Palette.ORANGE) }

            card("How it works") {
                text(
                    "Apps switched ON below are force-stopped about 10 seconds after the screen turns off, so they can't keep " +
                        "running, syncing or tracking in the background. They start again normally when you open them.",
                    13f,
                )
                switchRow("Stop when screen turns off", "Recommended", !Prefs.getFlag(context, Prefs.BG_SCREEN_OFF_OFF), owner) { _, on ->
                    Prefs.setFlag(this@BackgroundActivity, Prefs.BG_SCREEN_OFF_OFF, !on); render(); true
                }
                val last = Prefs.getLong(context, Prefs.BG_LAST_RUN)
                if (last > 0) text(
                    "Last run: " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)) +
                        " · ${Prefs.getInt(context, Prefs.BG_LAST_COUNT, 0)} app(s) stopped",
                    12f,
                ).alpha = 0.7f
                button("Stop all listed apps now") {
                    toast("Stopping…")
                    inBackground({ AppControl.stopListed(this@BackgroundActivity) }) { n -> toast("$n app(s) stopped"); load() }
                }.isEnabled = owner && listed.isNotEmpty()
            }

            card("Apps: switch = no background · tap = force stop now") {
                if (apps == null) text("Reading apps…", 13f)
                else apps.forEach { addView(row(it, it.info.packageName in listed, owner)) }
            }

            card("Android Restricted mode (PC, optional)") {
                text(
                    "Android's own \"Restricted\" battery mode is stronger: the app can't run any background work at all, " +
                        "and it survives reboots. Only the PC (ADB) can turn it on. Run the command, or use tools\\background.ps1.",
                    13f,
                ).alpha = 0.8f
                button("Copy apply command (${listed.size} apps)", outlined = true) {
                    Bloat.copy(this@BackgroundActivity, AppControl.adbRestrict(listed))
                }.isEnabled = listed.isNotEmpty()
                button("Copy undo command", outlined = true) {
                    Bloat.copy(this@BackgroundActivity, AppControl.adbUnrestrict(listed))
                }.isEnabled = listed.isNotEmpty()
                if (!AppControl.canReadOps(context)) {
                    text("To show which apps are Restricted, run once on the PC:", 12f).alpha = 0.7f
                    text(AppControl.ADB_GRANT_OPS, 12f, bold = true).setTextIsSelectable(true)
                }
            }
        }
    }

    private fun row(item: Item, on: Boolean, owner: Boolean): LinearLayout {
        val (info, label, stopped, restricted) = item
        val pkg = info.packageName
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val icon = ImageView(context)
            Apps.loadIcon(icon, pkg, info)
            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply { text = label; textSize = 15f })
            val status = buildList {
                add(if (stopped) "Stopped" else "Can run")
                if (restricted == true) add("Android Restricted")
            }.joinToString(" · ")
            texts.addView(TextView(context).apply {
                text = status
                textSize = 11f
                setTextColor(if (stopped) Palette.GREEN else Palette.ORANGE)
            })
            if (on && pkg in NEEDS_BACKGROUND) texts.addView(TextView(context).apply {
                text = "Won't get messages, alarms or playback until opened"
                textSize = 11f
                alpha = 0.7f
            })
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            texts.setOnClickListener { askStop(pkg, label) }
            icon.setOnClickListener { askStop(pkg, label) }

            val sw = MaterialSwitch(context)
            sw.isChecked = on
            sw.isEnabled = owner
            sw.setOnCheckedChangeListener { _, v ->
                val reason = AppControl.protectedReason(this@BackgroundActivity, pkg)
                if (v && reason != null) { toast(reason); render(); return@setOnCheckedChangeListener }
                AppControl.setNoBackground(this@BackgroundActivity, pkg, v)
                render()
            }
            addView(sw, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
    }

    private fun askStop(pkg: String, label: String) {
        AppControl.protectedReason(this, pkg)?.let { toast(it); return }
        MaterialAlertDialogBuilder(this).setTitle("Force stop $label?")
            .setMessage("The app is closed completely. Its notifications and alarms stop until it's opened again.")
            .setPositiveButton("Force stop") { _, _ -> stopNow(pkg, label) }
            .setNegativeButton("Cancel", null).show()
    }
}
