// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserManager
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Play Store control, install policy, and per-app hide / suspend / uninstall-block / kiosk. */
class AppsActivity : BaseActivity() {
    data class AppItem(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

    private lateinit var m: Mdm
    private var all: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private var showSystem = false
    private var query = ""
    private val icons = HashMap<String, Drawable>()

    private lateinit var header: LinearLayout
    private lateinit var dyn: LinearLayout
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        m = Mdm(this)

        dyn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        header.addView(dyn)
        header.addView(EditText(this).apply {
            hint = "Search apps"
            isSingleLine = true
            addTextChangedListener(afterTextChanged = { query = it.toString().trim().lowercase(); filter() })
        })
        header.addView(CheckBox(this).apply {
            text = "Show system apps"
            setOnCheckedChangeListener { _, v -> showSystem = v; filter() }
        })

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppsActivity)
            adapter = this@AppsActivity.adapter
            setPadding(dp(16), dp(8), dp(16), dp(16))
            clipToPadding = false
        }
        setPage(rv)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) { renderHeader(); adapter.notifyDataSetChanged() }
    }

    private fun load() = Thread {
        val pm = packageManager
        val list = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 }
            .map { AppItem(it.packageName, pm.getApplicationLabel(it).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0, it) }
            .sortedBy { it.label.lowercase() }
        runOnUiThread { all = list; filter() }
    }.start()

    private fun filter() {
        shown = all.filter {
            (showSystem || !it.system || it.pkg == Mdm.PLAY_STORE) &&
                (query.isEmpty() || it.label.lowercase().contains(query) || it.pkg.contains(query))
        }
        adapter.notifyDataSetChanged()
    }

    private fun renderHeader() {
        dyn.removeAllViews()
        dyn.pageHeader(this, "Apps", "Play Store, installs and per-app control", back = true)
        if (!m.isOwner) dyn.card { text("⚠️ Device Owner required for these controls", 13f, bold = true, color = Palette.ORANGE) }

        val current = when {
            m.isHidden(Mdm.PLAY_STORE) -> "Hide"
            m.isSuspended(Mdm.PLAY_STORE) -> "Suspend"
            else -> "Allow"
        }
        dyn.section("Play Store")
        dyn.tileGrid(listOf(
            Triple("Allow", R.drawable.ic_shop, Palette.GREEN),
            Triple("Suspend", R.drawable.ic_lock, Palette.ORANGE),
            Triple("Hide", R.drawable.ic_delete, Palette.RED),
        ).map { (label, icon, color) ->
            Tile(
                label, icon,
                color = if (label == current) color else withAlpha(color, 0x80),
                sub = if (label == current) "● Current" else null,
                enabled = m.isOwner,
            ) { setPlayStore(label) }
        })

        dyn.card("Installs") {
        switchRow(
            "Approved apps only",
            "New installs are paused until you approve them here. Apps installed while this panel is unlocked are approved automatically.",
            Prefs.approvalMode(this@AppsActivity), m.isOwner,
        ) { _, on ->
            if (on) Approval.enable(this@AppsActivity) else Approval.disable(this@AppsActivity)
            adapter.notifyDataSetChanged(); true
        }
        switchRow(
            "Block all installs", "Nothing can be installed, not even from Play Store",
            m.hasRestriction(UserManager.DISALLOW_INSTALL_APPS), m.isOwner,
        ) { _, on -> m.setRestriction(UserManager.DISALLOW_INSTALL_APPS, on); true }
        }

        dyn.section("All apps: tap one to manage")
    }

    private fun setPlayStore(mode: String) {
        act {
            val p = Mdm.PLAY_STORE
            when (mode) {
                "Allow" -> { m.setHidden(p, false); m.setSuspended(listOf(p), false) }
                "Suspend" -> {
                    m.setHidden(p, false)
                    if (m.setSuspended(listOf(p), true).isNotEmpty()) {
                        // On Google phones the Play Store is the system's "package verifier", which Android never lets anyone pause.
                        confirm(
                            "Play Store can't be paused",
                            "On this phone the Play Store also checks every app install for Android (package verifier), so Android doesn't allow pausing it.\n\n" +
                                "Hide it instead? The icon disappears and it can't be opened. App updates also stop while it's hidden.\n\n" +
                                "To block only certain apps, use Blocked apps on the dashboard.",
                            "Hide Play Store",
                        ) { act { m.setHidden(p, true) }; renderHeader() }
                    }
                }
                "Hide" -> if (!m.setHidden(p, true)) toast("Could not hide Play Store")
            }
        }
        renderHeader()
        adapter.notifyDataSetChanged()
    }

    private fun statusOf(a: AppItem): String {
        val tags = mutableListOf<String>()
        if (m.isHidden(a.pkg)) tags += "HIDDEN"
        if (m.isSuspended(a.pkg)) tags += "SUSPENDED"
        if (m.isUninstallBlocked(a.pkg)) tags += "NO-UNINSTALL"
        if (a.pkg in Prefs.kioskPkgs(this)) tags += "KIOSK"
        if (Prefs.approvalMode(this) && a.pkg !in Prefs.approved(this)) tags += "UNAPPROVED"
        if (a.system) tags += "system"
        return tags.joinToString(" · ")
    }

    private fun manage(a: AppItem) {
        if (!m.isOwner) { toast("Device Owner required"); return }
        val labels = arrayOf(
            "Hidden (removed from launcher & disabled)",
            "Suspended (greyed out, can't open)",
            "Block uninstall",
            "Approved (for approved-apps mode)",
            "Allowed in kiosk mode",
        )
        val cur = booleanArrayOf(
            m.isHidden(a.pkg), m.isSuspended(a.pkg), m.isUninstallBlocked(a.pkg),
            a.pkg in Prefs.approved(this), a.pkg in Prefs.kioskPkgs(this),
        )
        val next = cur.copyOf()
        val dlg = MaterialAlertDialogBuilder(this).setTitle(a.label)
            .setMultiChoiceItems(labels, next) { _, i, v -> next[i] = v }
            .setPositiveButton("Apply") { _, _ ->
                val risky = a.system && ((!cur[0] && next[0]) || (!cur[1] && next[1]))
                if (risky) confirm("System app", "Hiding or suspending system apps can break phone features. Continue?") { apply(a, cur, next) }
                else apply(a, cur, next)
            }
            .setNegativeButton("Cancel", null)
        if (!a.system) dlg.setNeutralButton("Uninstall") { _, _ ->
            confirm("Uninstall ${a.label}?", "The app and its data are removed from the phone.", "Uninstall") { uninstall(a) }
        } else dlg.setNeutralButton("Bloatware…") { _, _ -> startActivity(android.content.Intent(this, BloatActivity::class.java)) }
        dlg.show()
    }

    /** Silent uninstall (Device Owner only). */
    private fun uninstall(a: AppItem) = act {
        if (m.isUninstallBlocked(a.pkg)) m.setUninstallBlocked(a.pkg, false)
        val intent = android.content.Intent("com.devguard.mdm.UNINSTALL_RESULT").setPackage(packageName)
        val pi = android.app.PendingIntent.getBroadcast(this, a.pkg.hashCode(), intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        packageManager.packageInstaller.uninstall(a.pkg, pi.intentSender)
        toast("Uninstalling ${a.label}…")
        android.os.Handler(mainLooper).postDelayed({ load() }, 2500)
    }

    private fun apply(a: AppItem, cur: BooleanArray, next: BooleanArray) {
        act {
            if (cur[0] != next[0] && !m.setHidden(a.pkg, next[0])) toast("This app can't be hidden")
            if (cur[1] != next[1] && m.setSuspended(listOf(a.pkg), next[1]).isNotEmpty()) toast("This app can't be suspended")
            if (cur[2] != next[2]) m.setUninstallBlocked(a.pkg, next[2])
            if (cur[3] != next[3]) { if (next[3]) Approval.approve(this, a.pkg) else Approval.unapprove(this, a.pkg) }
            if (cur[4] != next[4]) {
                val k = Prefs.kioskPkgs(this)
                Prefs.setKioskPkgs(this, if (next[4]) k + a.pkg else k - a.pkg)
            }
        }
        // Refresh only this row so the list stays where it is.
        val i = shown.indexOf(a)
        if (i >= 0) adapter.notifyItemChanged(i + 1) else adapter.notifyDataSetChanged()
    }

    private fun iconOf(a: AppItem): Drawable =
        icons.getOrPut(a.pkg) { packageManager.getApplicationIcon(a.info) }

    inner class Row(val root: LinearLayout, val icon: ImageView, val title: TextView, val sub: TextView, val status: TextView) :
        RecyclerView.ViewHolder(root)

    inner class AppAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getItemCount() = shown.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) return object : RecyclerView.ViewHolder(header) {}
            val c = parent.context
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, c.dp(8), 0, c.dp(8))
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                isClickable = true
                isFocusable = true
                val ta = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0); ta.recycle()
            }
            val icon = ImageView(c)
            root.addView(icon, LinearLayout.LayoutParams(c.dp(40), c.dp(40)).apply { marginEnd = c.dp(12) })
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(c).apply { textSize = 16f }
            val sub = TextView(c).apply { textSize = 11f; alpha = 0.6f }
            val status = TextView(c).apply { textSize = 11f; setTextColor(0xFFD9534F.toInt()) }
            texts.addView(title); texts.addView(sub); texts.addView(status)
            root.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            return Row(root, icon, title, sub, status)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is Row) return
            val a = shown[position - 1]
            holder.title.text = a.label
            holder.sub.text = a.pkg
            val s = statusOf(a)
            holder.status.text = s
            holder.status.visibility = if (s.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            holder.icon.setImageDrawable(runCatching { iconOf(a) }.getOrNull())
            holder.root.setOnClickListener { manage(a) }
        }
    }
}
