// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.pm.ApplicationInfo
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Bloatware manager: built-in apps grouped as Safe / Caution / Keep / Unknown. */
class BloatActivity : BaseActivity() {
    /** One built-in app with everything its row shows, read off the main thread (P-02). */
    private data class Item(val info: ApplicationInfo, val e: BloatEntry, val label: String, val state: AppState)

    private var tab = BloatCat.SAFE
    private var items: List<Item>? = null // null until the first load finishes

    override fun onResume() {
        super.onResume()
        if (!isFinishing) { render(); load() } // shows the last list at once, then refreshes it
    }

    /** Reads every system app's category, name and state in the background (~355 apps), then redraws. */
    private fun load() {
        val m = Mdm(this)
        inBackground({
            Bloat.systemApps(this).map { info ->
                val e = Bloat.classify(this, info.packageName)
                Item(info, e, if (e.name != e.pkg) e.name else Apps.label(this, info), Bloat.state(m, info))
            }
        }) { items = it; render() }
    }

    private fun render() {
        val m = Mdm(this)
        val owner = m.isOwner
        val all = items
        scrollPage("Bloatware", back = true, subtitle = if (all == null) "Loading…" else "${all.size} built-in apps") {
            // Category tabs: 2 × 2 compact rounded tabs
            tabGrid(BloatCat.entries.map { cat ->
                Tile(
                    label = cat.label,
                    icon = when (cat) {
                        BloatCat.SAFE -> R.drawable.ic_delete
                        BloatCat.CAUTION -> R.drawable.ic_warning
                        BloatCat.KEEP -> R.drawable.ic_shield
                        BloatCat.UNKNOWN -> R.drawable.ic_info
                    },
                    color = cat.color,
                    sub = if (all == null) "…" else "${all.count { it.e.cat == cat }} apps",
                ) { tab = cat; render() }
            }, selected = BloatCat.entries.indexOf(tab), columns = 2)

            card {
                text(tab.help, 13f)
                text("Use the switch next to each app to turn it on or off. Tap the app's name for details and ADB options.", 12f).alpha = 0.7f
                if (!owner) text(
                    "Not Device Owner: switches are unavailable. Use the ADB commands (tap an app) or the PC script tools\\debloat.ps1.",
                    12f, color = Palette.ORANGE,
                )
            }

            if (all == null) { card { text("Reading built-in apps…", 13f) }; return@scrollPage }
            val list = all.filter { it.e.cat == tab }
                .sortedWith(compareBy({ it.state != AppState.ACTIVE }, { it.label.lowercase() }))
            card {
                if (list.isEmpty()) text("Nothing here.", 13f)
                list.forEach { addView(row(m, owner, it)) }
            }
        }
    }

    private fun row(m: Mdm, owner: Boolean, item: Item): LinearLayout {
        val (info, e, label, state) = item
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val icon = ImageView(context)
            Apps.loadIcon(icon, e.pkg, info)
            if (state != AppState.ACTIVE) icon.alpha = 0.4f
            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply { text = label; textSize = 15f })
            texts.addView(TextView(context).apply { text = e.pkg; textSize = 11f; alpha = 0.55f })
            if (e.note.isNotBlank() && e.name != e.pkg) texts.addView(TextView(context).apply { text = e.note; textSize = 11f; alpha = 0.75f })
            val badgeColor = if (state == AppState.ACTIVE) e.cat.color else Palette.SLATE
            texts.addView(TextView(context).apply {
                text = state.label
                textSize = 10f
                setTextColor(badgeColor)
                background = roundedBg(withAlpha(badgeColor, 0x26), 50)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(3) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            texts.setOnClickListener { manage(m, owner, item) }
            icon.setOnClickListener { manage(m, owner, item) }

            // Per-app on/off switch: ON = app active, OFF = disabled by the MDM
            val sw = com.google.android.material.materialswitch.MaterialSwitch(context)
            sw.isChecked = state == AppState.ACTIVE
            sw.isEnabled = owner && state != AppState.DISABLED
            val listener = object : android.widget.CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(b: android.widget.CompoundButton, on: Boolean) {
                    sw.setOnCheckedChangeListener(null); sw.isChecked = !on; sw.setOnCheckedChangeListener(this) // wait for result
                    if (on) setActive(m, e, state, true) else guarded(e) { setActive(m, e, state, false) }
                }
            }
            sw.setOnCheckedChangeListener(listener)
            addView(sw, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
    }

    /** Turn one app on or off and refresh the list. */
    private fun setActive(m: Mdm, e: BloatEntry, state: AppState, on: Boolean) {
        act {
            if (on) {
                when (state) {
                    AppState.HIDDEN -> Holds.set(this, Hold.HIDE_MANUAL, e.pkg, false)
                    AppState.REMOVED -> m.dpm.enableSystemApp(m.admin, e.pkg)
                    else -> {}
                }
                if (m.isHidden(e.pkg)) toast("Still hidden by: ${Holds.describe(this, e.pkg, hide = true)}")
                else toast("${e.name.takeIf { it != e.pkg } ?: e.pkg} enabled")
            } else {
                if (Holds.set(this, Hold.HIDE_MANUAL, e.pkg, true)) toast("${e.name.takeIf { it != e.pkg } ?: e.pkg} disabled")
                else toast("Android doesn't allow disabling this app")
            }
        }
        load() // re-reads the states in the background, then redraws
    }

    private fun manage(m: Mdm, owner: Boolean, item: Item) {
        val (_, e, label, state) = item
        val pkg = e.pkg
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (owner) when (state) {
            AppState.ACTIVE -> actions += "Disable" to { guarded(e) { setActive(m, e, state, false) } }
            AppState.HIDDEN -> actions += "Enable" to { setActive(m, e, state, true) }
            AppState.REMOVED -> actions += "Restore" to { setActive(m, e, state, true) }
            AppState.DISABLED -> {}
        }
        when (state) {
            AppState.ACTIVE, AppState.HIDDEN -> {
                actions += "Remove completely (copy ADB command)" to { guarded(e) { Bloat.copy(this, Bloat.adbRemove(pkg)) } }
                actions += "Disable via ADB (copy command)" to { guarded(e) { Bloat.copy(this, Bloat.adbDisable(pkg)) } }
            }
            AppState.DISABLED -> actions += "Enable via ADB (copy command)" to { Bloat.copy(this, Bloat.adbEnable(pkg)) }
            AppState.REMOVED -> actions += "Restore via ADB (copy command)" to { Bloat.copy(this, Bloat.adbRestore(pkg)) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage("${e.cat.label.uppercase()} · ${state.label}\n$pkg\n\n${e.note}\n\nRemoving keeps the app on the system partition, so it can always be restored.")
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton("Close", null).show()
    }

    /** Extra confirmation for risky categories. */
    private fun guarded(e: BloatEntry, go: () -> Unit) = when (e.cat) {
        BloatCat.KEEP -> confirm("⚠️ System-critical app", "${e.name}: ${e.note}\n\nRemoving or disabling it can break the phone (calls, SMS, setup, boot). Continue only if you're sure.", "I understand") { go() }
        BloatCat.UNKNOWN -> confirm("Unknown app", "This app isn't in the bloatware list. Removing unknown system apps can break features.", "Continue") { go() }
        else -> go()
    }
}
