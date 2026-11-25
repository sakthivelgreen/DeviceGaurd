// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings: PIN & recovery, two-factor, profile backup, QR provisioning, app updates, about. */
class SettingsActivity : BaseActivity() {

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            toast("Saving profile…")
            // Export checks every package and writes a file: off the main thread (P-01).
            inBackground({
                val json = Profile.export(this)
                val out = contentResolver.openOutputStream(uri) ?: error("Couldn't open the file")
                out.use { it.write(json.toByteArray()) }
            }) { toast("Profile saved") }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            if (text == null) { toast("Couldn't read the file"); return@registerForActivityResult }
            // S-02: a profile can turn the filter guard off and replace the website filters, so importing
            // goes through the filter guard (when it's on), like any other way of weakening the filters.
            Guard.require(this, "import a profile (it can change or turn off your website filters)") {
                confirm("Import this profile?", "Its restrictions, filters, lists and schedules replace the current ones. Your PIN stays the same.", "Import") {
                    toast("Importing…")
                    inBackground({ Profile.import(this, text) }) { msg -> toast(msg); render() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    private fun render() {
        val owner = Mdm(this).isOwner
        val version = packageManager.getPackageInfo(packageName, 0)
        scrollPage("Settings", back = true, subtitle = "Version ${version.versionName} (${version.longVersionCode})") {

            card("Admin PIN & recovery") {
                button("Change admin PIN", outlined = true) { changePinDialog() }
                button("Create a new recovery code", outlined = true) {
                    confirm("New recovery code?", "The old recovery code stops working.", "Create") {
                        showRecoveryCodeDialog(Prefs.newRecoveryCode(this@SettingsActivity)) { render() }
                    }
                }
                button("How to reset the PIN from the PC", outlined = true) { pcResetHelpDialog() }
            }

            card("Two-factor unlock") {
                text(
                    "After the PIN, also ask for a 6-digit code from Zoho OneAuth or Google Authenticator. Works offline. " +
                        "The recovery code or the PC reset turns it off if you lose the authenticator.",
                    13f,
                ).alpha = 0.8f
                if (Totp.enabled(context)) {
                    text("✅ On", 15f, bold = true, color = Palette.GREEN)
                    button("Turn off", outlined = true) {
                        totpPrompt { Totp.disable(this@SettingsActivity); toast("Two-factor unlock is off"); render() }
                    }
                } else button("Turn on") { setupTotpDialog { render() } }
            }

            card("Lock screen & branding") {
                text(
                    "Android always shows that this phone is managed. These texts replace \"your organization\" and add your own messages. " +
                        "Tap one to change it; leave it empty to hide it.",
                    13f,
                ).alpha = 0.8f
                Branding.FIELDS.forEach { f ->
                    val v = Branding.value(context, f)
                    text(f.title, 12f).alpha = 0.7f
                    text(v.ifBlank { "(hidden)" }, 15f, bold = true).apply {
                        setPadding(0, 0, 0, dp(8))
                        setOnClickListener { if (owner) editBranding(f) }
                    }
                }
                button("Reset to defaults (Sakthivel J)", outlined = true) {
                    Branding.resetDefaults(this@SettingsActivity); toast("Defaults applied"); render()
                }.isEnabled = owner
                text("Lock screen changes show after the screen turns off and on. vivo may shorten long messages.", 12f).alpha = 0.6f
            }

            card("Backup & restore profile") {
                text("Save every setting (restrictions, filters, blocked apps and sites, screen time, background list) to a file, " +
                    "e.g. to restore after a factory reset. The PIN and secrets are not included.", 13f).alpha = 0.8f
                button("Export profile…") {
                    exportLauncher.launch("deviceguard-profile-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json")
                }.isEnabled = owner
                button("Import profile…", outlined = true) { importLauncher.launch(arrayOf("application/json", "*/*")) }.isEnabled = owner
            }

            card("Set up another phone (QR code)") {
                text("On a new or factory-reset phone, tap the welcome screen 6 times to open the QR reader, then scan this code. " +
                    "The phone downloads DeviceGuard and makes it Device Owner, with no PC needed.", 13f).alpha = 0.8f
                text("Needs a public direct-download link to the RELEASE build (tools\\release.ps1 builds it). A private GitHub repo link won't work.", 12f, color = Palette.ORANGE)
                button("Show setup QR code") { provisioningDialog() }
            }

            card("App updates") {
                text("Installs new versions from GitHub Releases silently. Tag each release build-<versionCode> (e.g. build-3) and attach the release APK.", 13f).alpha = 0.8f
                text("Repository: ${Updater.repo(context)}", 13f, bold = true)
                text(if (!Secrets.has(context, Prefs.UPD_TOKEN)) "Token: not set (needed for a private repo)" else "Token: saved (encrypted)", 12f).alpha = 0.7f
                button("Repository & token…", outlined = true) { updateSettings() }
                switchRow("Check daily (auto-install)", "Checked when the screen turns on, at most once a day", Prefs.getFlag(context, Prefs.UPD_AUTO), owner) { _, on ->
                    Prefs.setFlag(this@SettingsActivity, Prefs.UPD_AUTO, on); GuardService.refresh(this@SettingsActivity); true
                }
                button("Check for updates now") { checkNow() }
                val last = Prefs.getLong(context, Prefs.UPD_LAST)
                if (last > 0) text("Last automatic check: " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(last)), 12f).alpha = 0.6f
            }

            card {
                button("About DeviceGuard", outlined = true) { startActivity(Intent(this@SettingsActivity, AboutActivity::class.java)) }
            }
        }
    }

    private fun editBranding(f: Branding.Field) {
        val input = EditText(this).apply {
            setText(Branding.value(this@SettingsActivity, f))
            filters = arrayOf(android.text.InputFilter.LengthFilter(f.maxLen))
            if (f.maxLen > 120) { minLines = 2; isSingleLine = false } else isSingleLine = true
        }
        val col = column().apply { addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(f.title).setView(col)
            .setPositiveButton("Save") { _, _ ->
                Branding.set(this, f.key, input.text.toString()); toast("Saved"); render()
            }
            .setNeutralButton("Default") { _, _ -> Prefs.setStr(this, f.key, null); Branding.apply(this); render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateSettings() {
        val col = column()
        val repo = EditText(this).apply { hint = "owner/repo"; setText(Updater.repo(this@SettingsActivity)); isSingleLine = true }
        val token = EditText(this).apply {
            hint = "GitHub token (read-only, optional)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.text("Repository", 12f).alpha = 0.7f; col.addView(repo)
        col.text("Token: GitHub → Settings → Developer settings → Fine-grained token → this repo → Contents: Read-only. Leave empty to keep the saved one.", 12f).alpha = 0.7f
        col.addView(token)
        MaterialAlertDialogBuilder(this).setTitle("App updates").setView(col)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setStr(this, Prefs.UPD_REPO, repo.text.toString().trim())
                if (token.text.isNotBlank()) act { Secrets.put(this, Prefs.UPD_TOKEN, token.text.toString().trim()) }
                render()
            }
            .setNeutralButton("Remove token") { _, _ -> Secrets.put(this, Prefs.UPD_TOKEN, null); render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun checkNow() {
        toast("Checking…")
        inBackground({ Updater.latest(this) }) { rel ->
            val cur = Updater.currentCode(this)
            if (rel.code <= cur) toast("You have the latest version ($cur)")
            else MaterialAlertDialogBuilder(this).setTitle("Update available: ${rel.tag}")
                .setMessage((rel.notes.ifBlank { "New version ${rel.code} (you have $cur)." }) +
                    "\n\nIt installs silently; DeviceGuard restarts afterwards.")
                .setPositiveButton("Install") { _, _ ->
                    toast("Downloading…")
                    inBackground({ Updater.install(this, rel) }) { err -> toast(err ?: "Installing… DeviceGuard will restart") }
                }
                .setNegativeButton("Later", null).show()
        }
    }

    private fun provisioningDialog() {
        val col = column()
        val url = EditText(this).apply {
            hint = "https://…/DeviceGuard.apk"
            setText(Prefs.getStr(this@SettingsActivity, "provUrl") ?: "")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val ssid = EditText(this).apply { hint = "Wi-Fi name (optional)"; isSingleLine = true }
        val pass = EditText(this).apply { hint = "Wi-Fi password (optional)"; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.text("Direct download link to the release APK", 12f).alpha = 0.7f; col.addView(url)
        col.text("Optional: Wi-Fi for the new phone to use during setup", 12f).alpha = 0.7f; col.addView(ssid); col.addView(pass)
        MaterialAlertDialogBuilder(this).setTitle("Setup QR code").setView(col)
            .setPositiveButton("Show QR") { _, _ ->
                val link = url.text.toString().trim()
                if (!link.startsWith("https://")) { toast("Enter an https:// link to the APK"); return@setPositiveButton }
                Prefs.setStr(this, "provUrl", link)
                val json = Device.provisioningJson(this, link, ssid.text.toString().trim(), pass.text.toString())
                val view = column().apply {
                    addView(ImageView(this@SettingsActivity).apply { setImageBitmap(Qr.bitmap(json, 900)) },
                        LinearLayout.LayoutParams(dp(300), dp(300)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    text("On the new phone: welcome screen → tap 6 times → connect to Wi-Fi if asked → scan. " +
                        "Then set the PIN in DeviceGuard and import your profile (Settings → Import).", 13f)
                }
                MaterialAlertDialogBuilder(this).setTitle("Scan on the new phone").setView(view)
                    .setPositiveButton("Done", null).setNeutralButton("Copy JSON") { _, _ -> Bloat.copy(this, json) }.show()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
