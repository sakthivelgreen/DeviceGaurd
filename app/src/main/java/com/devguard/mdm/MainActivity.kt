// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** PIN gate + tile dashboard. */
class MainActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Session.touch()
    }

    private fun render() {
        when {
            !Prefs.hasPin(this) -> pinScreen(setup = true)
            !Session.valid() -> pinScreen(setup = false)
            else -> dashboard()
        }
    }

    // ---------------------------------------------------------------- PIN

    private fun pinScreen(setup: Boolean) = scrollPage(null) {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(64), dp(24), dp(24))
        val box = FrameLayout(context).apply { background = roundedBg(Palette.BLUE, 28) }
        box.addView(ImageView(context).apply { setImageResource(R.drawable.ic_lock); setColorFilter(Color.WHITE) },
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        addView(box, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(20) })
        text(if (setup) "Create admin PIN" else "DeviceGuard MDM", 24f, bold = true).gravity = Gravity.CENTER
        text(
            if (setup) "4–12 digits. Every setting is protected by it." else "This device is managed. Enter the admin PIN.",
            14f,
        ).apply { gravity = Gravity.CENTER; alpha = 0.7f }

        card {
            val p1 = pinField(if (setup) "New PIN" else "PIN")
            addView(p1)
            val p2 = if (setup) pinField("Confirm PIN").also { addView(it) } else null
            button(if (setup) "Save PIN" else "Unlock") {
                val a = p1.text.toString()
                if (setup) {
                    when {
                        a.length !in 4..12 -> toast("PIN must be 4–12 digits")
                        a != p2!!.text.toString() -> toast("PINs don't match")
                        else -> {
                            Prefs.setPin(this@MainActivity, a); Session.unlockNow()
                            showRecoveryCode(Prefs.newRecoveryCode(this@MainActivity)) { render() }
                        }
                    }
                } else {
                    val err = Session.tryUnlock(this@MainActivity, a)
                    when {
                        err != null -> { toast(err); p1.text.clear() }
                        // Two-factor: PIN alone isn't enough; ask for the authenticator code.
                        Totp.enabled(this@MainActivity) -> { Session.lock(); totpPrompt { Session.unlockNow(); render() } }
                        else -> render()
                    }
                }
            }
            if (!setup) button("Forgot PIN?", outlined = true) { forgotPin() }
        }
        text("by ${getString(R.string.author)}", 12f).apply { gravity = Gravity.CENTER; alpha = 0.5f }
    }

    // ------------------------------------------------------- PIN recovery

    private fun showRecoveryCode(code: String, then: () -> Unit) = showRecoveryCodeDialog(code, then)

    private fun forgotPin() {
        MaterialAlertDialogBuilder(this).setTitle("Forgot PIN?")
            .setItems(arrayOf("Use my recovery code", "Reset from the PC (ADB)")) { _, i ->
                if (i == 0) recoverWithCode() else pcResetHelp()
            }.show()
    }

    private fun recoverWithCode() {
        if (!Prefs.hasRecovery(this)) {
            toast("No recovery code was set up. Use the PC reset instead.")
            pcResetHelp(); return
        }
        val e = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        MaterialAlertDialogBuilder(this).setTitle("Enter recovery code").setView(padded(e))
            .setPositiveButton("Reset PIN") { _, _ ->
                val err = Session.tryRecovery(this, e.text.toString())
                if (err == null) {
                    Prefs.clearPin(this)
                    Totp.disable(this) // the recovery code also resets the authenticator lock
                    toast("Recovery code accepted. Create a new PIN.")
                    render()
                } else toast(err)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pcResetHelp() = pcResetHelpDialog()

    // ---------------------------------------------------------- dashboard

    private fun dashboard() {
        val m = Mdm(this)
        m.ensureBaseline()
        GuardService.refresh(this)

        val activeRestrictions = RESTRICTION_GROUPS.flatMap { it.second }.count { m.hasRestriction(it.key) }
        val dnsOn = PrivateDns.currentHost(this) != null
        // "On" only when a DNS-level filter protects all browsers/apps (Chrome rules alone don't count).
        val webOn = WebFilter.anyFilterActive(this) || dnsOn
        val playState = when {
            m.isHidden(Mdm.PLAY_STORE) -> "Hidden"
            m.isSuspended(Mdm.PLAY_STORE) -> "Suspended"
            else -> "Allowed"
        }

        scrollPage("DeviceGuard", subtitle = "MDM · by ${getString(R.string.author)}") {
            statusCard(
                ok = m.isOwner,
                headline = if (m.isOwner) "Device protected" else "Setup needed",
                detail = if (m.isOwner) "Device Owner · full control active" else "Not Device Owner: controls are disabled",
                pills = listOf(
                    "Web ${if (webOn) "ON" else "off"}",
                    "Approval ${if (Prefs.approvalMode(context)) "ON" else "off"}",
                    "$activeRestrictions restrictions",
                    "Play Store: $playState",
                ),
            )
            if (!Prefs.hasRecovery(context)) card {
                text("🔑 No recovery code yet", 15f, bold = true, color = Palette.ORANGE)
                text("If you forget the PIN, a recovery code lets you reset it on the phone. Create one now and keep it safe.", 13f)
                button("Create recovery code") { showRecoveryCode(Prefs.newRecoveryCode(this@MainActivity)) { render() } }
            }
            if (!m.isOwner) card {
                text("Make this app Device Owner from a PC (the phone must have no accounts):", 13f)
                text("adb shell dpm set-device-owner $packageName/.AdminReceiver", 12f, bold = true).setTextIsSelectable(true)
                text("Full steps: README.md §6", 12f).alpha = 0.6f
            }

            section("Manage")
            tileGrid(listOf(
                Tile("Apps", R.drawable.ic_apps, Palette.BLUE, if (Prefs.approvalMode(context)) "Approval on" else "Control apps") {
                    open(AppsActivity::class.java)
                },
                Tile("Websites", R.drawable.ic_web, if (webOn) Palette.GREEN else Palette.RED, if (webOn) "Filtering" else "⚠ No filter") {
                    open(WebActivity::class.java)
                },
                Tile("Screen time", R.drawable.ic_kiosk, Palette.INDIGO,
                    "${ScreenTime.limits(context).size} limits · ${ScreenTime.schedules(context).count { it.enabled }} sched") {
                    open(ScreenTimeActivity::class.java)
                },
                Tile("Restrictions", R.drawable.ic_tune, Palette.PURPLE, "$activeRestrictions active") {
                    open(RestrictionsActivity::class.java)
                },
                Tile("Background", R.drawable.ic_restart, Palette.TEAL, "${Prefs.noBackgroundPkgs(context).size} apps") {
                    open(BackgroundActivity::class.java)
                },
                Tile("Blocked apps", R.drawable.ic_lock, Palette.RED, "${Prefs.blockedPkgs(context).size} blocked") {
                    open(BlockListActivity::class.java)
                },
                Tile("Bloatware", R.drawable.ic_delete, Palette.PINK, "Clean up") {
                    open(BloatActivity::class.java)
                },
                Tile("Kiosk", R.drawable.ic_kiosk, Palette.ORANGE, "${Prefs.kioskPkgs(context).size} apps") {
                    confirm(
                        "Start kiosk mode?",
                        "The phone will be locked to the apps marked “Allowed in kiosk”. To exit, long-press the kiosk title and enter the PIN.",
                        "Start",
                    ) { Kiosk.start(this@MainActivity)?.let { toast(it) } }
                },
                Tile("Settings", R.drawable.ic_key, Palette.SLATE, if (Totp.enabled(context)) "2FA on" else "PIN, backup") {
                    open(SettingsActivity::class.java)
                },
            ))

            section("Quick actions")
            tileGrid(listOf(
                Tile("Lock now", R.drawable.ic_lock, Palette.INDIGO, enabled = m.isOwner) { act { m.dpm.lockNow() } },
                Tile("Reboot", R.drawable.ic_restart, Palette.INDIGO, enabled = m.isOwner) {
                    confirm("Reboot?", "The phone will restart now.", "Reboot") { act { m.dpm.reboot(m.admin) } }
                },
                Tile("Lock panel", R.drawable.ic_exit, Palette.SLATE) { Session.lock(); render() },
            ))
            button("Danger zone", outlined = true) { dangerZone(m) }.apply {
                setTextColor(Palette.RED)
                strokeColor = android.content.res.ColorStateList.valueOf(Palette.RED)
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
            }
        }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun dangerZone(m: Mdm) {
        MaterialAlertDialogBuilder(this).setTitle("Danger zone")
            .setItems(arrayOf("Remove Device Owner (release all controls)", "Factory reset this phone")) { _, i ->
                dangerAction(m, i)
            }.show()
    }

    private fun dangerAction(m: Mdm, i: Int) {
        if (i == 0) {
            if (!m.isOwner) { toast("Not Device Owner"); return }
            confirm(
                "Release all controls?",
                "All restrictions, hidden/paused apps, website blocking and kiosk mode are removed, " +
                    "and this app stops being Device Owner. You'd need to factory reset to make it owner again.",
                "Release",
            ) { act { m.releaseAll(this); toast("Device Owner removed"); render() } }
        } else wipe(m)
    }

    private fun wipe(m: Mdm) {
        if (!m.isOwner) { toast("Device Owner required"); return }
        val e = EditText(this).apply { hint = "Type WIPE" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Erase everything?")
            .setMessage("ALL data on this phone will be erased. This cannot be undone.")
            .setView(padded(e))
            .setPositiveButton("Erase") { _, _ ->
                if (e.text.toString() == "WIPE") act {
                    if (Build.VERSION.SDK_INT >= 34) m.dpm.wipeDevice(0) else @Suppress("DEPRECATION") m.dpm.wipeData(0)
                } else toast("Not confirmed")
            }
            .setNegativeButton("Cancel", null).show()
    }
}
