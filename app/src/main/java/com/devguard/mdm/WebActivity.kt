// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Website blocking, three layers:
 *  1. Family-safe filter via forced Private DNS (no VPN, whole phone)
 *  2. Your own blocked-site list enforced in Chrome via managed policies (no VPN)
 *  3. Optional local VPN DNS filter (your list across all apps)
 */
class WebActivity : BaseActivity() {

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WebFilter.VPN_CONSENT_REQUEST) {
            if (resultCode == Activity.RESULT_OK) startService(Intent(this, DnsVpnService::class.java))
            else { Prefs.setWebBlock(this, false); toast("VPN permission is needed for the VPN filter") }
        }
    }

    private fun addDomains(d: Set<String>) {
        Prefs.setDomains(this, Prefs.domains(this) + d)
        onDomainsChanged(this)
        render()
    }

    private fun setPrivateDns(host: String?) {
        toast("Applying…")
        Thread {
            val msg = try { PrivateDns.apply(this, host) } catch (e: Exception) { e.message ?: "Failed" }
            runOnUiThread { toast(msg); if (!isFinishing) render() }
        }.start()
    }

    private fun choosePrivateDns() {
        val items = PrivateDns.PROVIDERS.map { "${it.name}: ${it.desc}" } + "Custom (NextDNS, etc.)"
        MaterialAlertDialogBuilder(this).setTitle("Family-safe filter")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < PrivateDns.PROVIDERS.size) setPrivateDns(PrivateDns.PROVIDERS[i].host) else customPrivateDns()
            }.show()
    }

    private fun customPrivateDns() {
        val e = EditText(this).apply {
            hint = "e.g. abc123.dns.nextdns.io"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        MaterialAlertDialogBuilder(this).setTitle("Custom Private DNS host")
            .setMessage("Enter a DNS-over-TLS hostname. NextDNS gives you one with your own block list and categories, managed on their website.")
            .setView(padded(e))
            .setPositiveButton("Apply") { _, _ ->
                val h = e.text.toString().trim().lowercase()
                if (h.contains('.')) setPrivateDns(h) else toast("Enter a valid hostname")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chromeSwitch(c: android.widget.LinearLayout, title: String, sub: String, key: String, enabled: Boolean) {
        c.switchRow(title, sub, Prefs.getFlag(this, key), enabled) { _, on ->
            Prefs.setFlag(this, key, on)
            ChromePolicy.apply(this)?.let { toast(it) }
            true
        }
    }

    private fun hideOtherBrowsers() {
        val m = Mdm(this)
        val pkgs = ChromePolicy.otherBrowsers(this)
        if (pkgs.isEmpty()) { toast("No other browsers found"); return }
        val labels = pkgs.map { p ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) + "\n$p"
        }.toTypedArray()
        val pick = BooleanArray(pkgs.size) { true }
        MaterialAlertDialogBuilder(this).setTitle("Hide these browsers?")
            .setMultiChoiceItems(labels, pick) { _, i, v -> pick[i] = v }
            .setPositiveButton("Hide") { _, _ ->
                var n = 0
                pkgs.forEachIndexed { i, p -> if (pick[i] && runCatching { m.setHidden(p, true) }.getOrDefault(false)) n++ }
                toast("Hidden $n browser(s). Unhide them in Apps & Play Store (show system apps).")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun render() {
        val m = Mdm(this)
        val owner = m.isOwner
        val host = PrivateDns.currentHost(this)
        val provider = PrivateDns.PROVIDERS.firstOrNull { it.host != null && it.host == host }
        scrollPage("Websites", back = true, subtitle = "${Prefs.domains(this).size} sites blocked") {
            if (!owner) card { text("⚠️ Device Owner required for most of these controls", 13f, bold = true, color = Palette.ORANGE) }

            // ---- 1. Family-safe (Private DNS) ----
            card("Family-safe filter · no VPN") {
            text("Forces the phone's Private DNS to a filtering service and locks the setting. Works in every app and browser.", 13f).alpha = 0.8f
            text("Current: " + when {
                host == null -> "Off"
                provider != null -> "${provider.name} ($host)"
                else -> "Custom ($host)"
            }, 15f, bold = true, color = if (host != null) Palette.GREEN else null)
            button("Choose filter") { choosePrivateDns() }.isEnabled = owner
            }

            // ---- Blocked-site list ----
            card("Your blocked sites") {
            text("Used by the Chrome rules and the VPN filter. Blocking a domain also blocks its subdomains.", 13f).alpha = 0.8f
            val input = EditText(context).apply {
                hint = "example.com"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            addView(input)
            button("Block this site") {
                val d = WebFilter.normalize(input.text.toString())
                if (d == null) toast("Enter a valid domain, like example.com") else addDomains(setOf(d))
            }
            }

            section("Quick add")
            tileGrid(listOf(
                Tile("Social media", R.drawable.ic_apps, Palette.PINK, "${WebFilter.PRESET_SOCIAL.size} sites") { addDomains(WebFilter.PRESET_SOCIAL) },
                Tile("Video", R.drawable.ic_kiosk, Palette.ORANGE, "${WebFilter.PRESET_VIDEO.size} sites") { addDomains(WebFilter.PRESET_VIDEO) },
                Tile("Games", R.drawable.ic_tune, Palette.PURPLE, "${WebFilter.PRESET_GAMES.size} sites") { addDomains(WebFilter.PRESET_GAMES) },
            ))

            card {
            val domains = Prefs.domains(context).sorted()
            text("Blocked (${domains.size}): tap one to unblock", 13f, bold = true)
            if (domains.isEmpty()) text("No sites blocked yet.", 13f).alpha = 0.6f
            domains.forEach { d ->
                text("🚫  $d", 16f).setOnClickListener {
                    confirm("Unblock $d?", "This site will be reachable again.", "Unblock") {
                        Prefs.setDomains(this@WebActivity, Prefs.domains(this@WebActivity) - d)
                        onDomainsChanged(this@WebActivity)
                        render()
                    }
                }
            }

            }

            // ---- 2. Chrome policies ----
            card("Chrome rules · no VPN") {
            if (!ChromePolicy.installed(context)) text("Chrome isn't installed on this phone.", 13f, bold = true)
            text("Enforced inside Chrome through its enterprise policies. Restart Chrome after changes; check them at chrome://policy.", 13f).alpha = 0.8f
            val chromeOn = Prefs.getFlag(context, Prefs.CHROME_ON)
            switchRow("Enforce in Chrome", "Blocks your site list in Chrome", chromeOn, owner) { _, on ->
                Prefs.setFlag(this@WebActivity, Prefs.CHROME_ON, on)
                ChromePolicy.apply(this@WebActivity)?.let { toast(it) }
                render(); true
            }
            val sub = owner && chromeOn
            chromeSwitch(this, "Adult site filter", "Google SafeSites", Prefs.CHROME_SAFESITES, sub)
            chromeSwitch(this, "Force Google SafeSearch", "Explicit results hidden in Google Search", Prefs.CHROME_SAFESEARCH, sub)
            chromeSwitch(this, "YouTube restricted mode", "Moderate restricted mode on youtube.com", Prefs.CHROME_YOUTUBE, sub)
            chromeSwitch(this, "Disable incognito tabs", "Users can't browse privately", Prefs.CHROME_NO_INCOGNITO, sub)
            button("Hide other browsers", outlined = true) { hideOtherBrowsers() }.isEnabled = owner
            text("Hide other browsers so Chrome's rules can't be avoided. Turn on Approved apps only so new browsers can't be installed.", 12f).alpha = 0.6f

            }

            // ---- 3. VPN filter (optional) ----
            card("VPN filter · optional") {
            text(
                "Blocks your site list in every app through a local, always-on VPN (no traffic leaves the phone through it). " +
                    "Can't run together with the Private DNS filter: turning it on switches Private DNS back to automatic.",
                13f,
            ).alpha = 0.8f
            switchRow(
                "VPN filter",
                if (owner) "Always-on: the user can't turn it off" else "Not Device Owner: the user can turn this off",
                Prefs.webBlock(context),
            ) { sw, on ->
                if (!on) { WebFilter.disable(context); render(); true }
                else if (host != null) {
                    confirm("Turn off Private DNS filter?", "The VPN filter replaces the family-safe Private DNS filter ($host).", "Switch") {
                        act { if (WebFilter.enable(this@WebActivity)) sw.setSilently(true); render() }
                    }
                    false
                } else WebFilter.enable(this@WebActivity)
            }
            switchRow(
                "Family-safe upstream for VPN",
                "VPN filter also blocks adult and malware sites (Cloudflare 1.1.1.3)",
                Prefs.familyDns(context),
            ) { _, on -> Prefs.setFamilyDns(context, on); DnsVpnService.reload(context); true }
            }

            text("Tip: browsers cache lookups. If a newly blocked site still opens, close and reopen the browser.", 12f).alpha = 0.6f
        }
    }
}
