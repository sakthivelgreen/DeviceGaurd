// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "Always block" list: these apps are removed automatically whenever they get installed. */
class BlockListActivity : BaseActivity() {

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    /** Confirms (listing anything that will be uninstalled now), then blocks. */
    private fun block(pkgs: Collection<String>) {
        val new = pkgs.filter { it != packageName && it !in Prefs.blockedPkgs(this) }
        if (new.isEmpty()) { toast("Already blocked"); return }
        val installedNow = new.filter { Blocklist.isInstalled(this, it) }
        val names = new.joinToString("\n") { "• " + Blocklist.label(this, it) }
        val msg = if (installedNow.isEmpty()) "$names\n\nThese apps will be removed automatically if anyone installs them."
        else "$names\n\n⚠️ Installed now and will be UNINSTALLED immediately (their data is deleted):\n" +
            installedNow.joinToString("\n") { "• " + Blocklist.label(this, it) }
        confirm("Block ${new.size} app(s)?", msg, "Block") {
            act { Blocklist.add(this, new) }
            toast("Blocked")
            render()
        }
    }

    private fun pickInstalled() = inBackground({
        val blocked = Prefs.blockedPkgs(this)
        Apps.userApps(this).map { it.first.packageName to it.second }.filter { it.first !in blocked }
    }) { apps ->
        if (apps.isEmpty()) { toast("No installed apps to choose from"); return@inBackground }
        val pick = BooleanArray(apps.size)
        MaterialAlertDialogBuilder(this).setTitle("Choose apps to block")
            .setMultiChoiceItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray(), pick) { _, i, v -> pick[i] = v }
            .setPositiveButton("Next") { _, _ -> block(apps.filterIndexed { i, _ -> pick[i] }.map { it.first }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun render() {
        val m = Mdm(this)
        val blocked = Prefs.blockedPkgs(this).sortedBy { Blocklist.label(this, it).lowercase() }
        scrollPage("Blocked apps", back = true, subtitle = "${blocked.size} apps not allowed") {
            if (!m.isOwner) card { text("⚠️ Device Owner required", 14f, bold = true, color = Palette.ORANGE) }

            card("How it works") {
                text(
                    "Apps on this list are not allowed on the phone. If someone installs one from the Play Store (or anywhere else), " +
                        "DeviceGuard removes it straight away and shows “… is not allowed”. Apps already installed are removed when you add them.",
                    13f,
                )
            }

            card("Add an app") {
                text("Paste a Play Store link or a package name.", 13f).alpha = 0.8f
                val input = EditText(context).apply {
                    hint = "play.google.com/…?id=com.example.app"
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                input.keepText("blockAddApp") // B-07: typed text survives redraws
                addView(input)
                button("Block this app") {
                    val p = Blocklist.parse(input.text.toString())
                    if (p == null) toast("Paste a Play Store link or a package name like com.example.app") else block(listOf(p))
                }
                button("Choose from installed apps", outlined = true) { pickInstalled() }
                text(
                    "To get a link: open the app's page in the Play Store → tap ⋮ (top right) → Share → Copy link, then paste it here.",
                    12f,
                ).alpha = 0.65f
            }

            section("Quick add")
            tileGrid(Blocklist.PRESETS.entries.map { (pkg, name) ->
                val on = pkg in blocked
                Tile(name, if (on) R.drawable.ic_lock else R.drawable.ic_apps, if (on) Palette.RED else Palette.SLATE,
                    sub = if (on) "Blocked" else if (Blocklist.isInstalled(context, pkg)) "Installed" else null) {
                    if (on) toast("$name is already blocked") else block(listOf(pkg))
                }
            })

            card("Blocked (${blocked.size}): tap to allow again") {
                if (blocked.isEmpty()) text("No apps blocked yet.", 13f).alpha = 0.6f
                blocked.forEach { pkg -> addView(row(pkg)) }
            }
        }
    }

    private fun row(pkg: String): LinearLayout {
        val installed = Blocklist.isInstalled(this, pkg)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val box = android.widget.FrameLayout(context).apply { background = roundedBg(Palette.RED, 12) }
            box.addView(ImageView(context).apply { setImageResource(R.drawable.ic_lock); setColorFilter(android.graphics.Color.WHITE) },
                android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            addView(box, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply { text = Blocklist.label(context, pkg); textSize = 15f })
            texts.addView(TextView(context).apply { text = pkg; textSize = 11f; alpha = 0.55f })
            texts.addView(TextView(context).apply {
                text = if (installed) "Installed: being removed" else "Not installed"
                textSize = 11f
                setTextColor(if (installed) Palette.ORANGE else Palette.GREEN)
            })
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            setOnClickListener {
                confirm("Allow ${Blocklist.label(context, pkg)}?", "It will be possible to install this app again.", "Allow") {
                    act { Blocklist.unblock(this@BlockListActivity, pkg) }
                    render()
                }
            }
        }
    }
}
