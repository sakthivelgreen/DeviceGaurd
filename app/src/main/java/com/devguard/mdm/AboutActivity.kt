// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

/** App info and author. */
class AboutActivity : BaseActivity() {

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    private fun render() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        scrollPage("About", back = true) {
            statusCard(
                ok = true,
                headline = getString(R.string.app_name),
                detail = "Version $version · by ${getString(R.string.author)}",
                pills = listOf("Device Owner MDM", "No server", "PIN protected"),
            )
            card("App info") {
                text("Author: ${getString(R.string.author)}", 15f, bold = true)
                text("Package: $packageName", 13f).alpha = 0.7f
                text("Version: $version", 13f).alpha = 0.7f
            }
            card("What it does") {
                text(
                    "• Play Store control: allow, suspend or hide\n" +
                        "• Approved-apps mode: new installs stay paused until approved\n" +
                        "• Hide, suspend, block uninstall or remove any app\n" +
                        "• Bloatware cleanup with Safe / Caution / Keep ratings\n" +
                        "• Website blocking: Private DNS family filter, Chrome rules\n" +
                        "• 20+ device restrictions (factory reset, safe mode, USB, Wi-Fi…)\n" +
                        "• Kiosk mode: lock the phone to chosen apps\n" +
                        "• Everything is managed on the phone, protected by a PIN",
                    14f,
                )
            }
        }
    }
}
