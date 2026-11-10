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
            runOnUiThread {
                if (msg == PrivateDns.UNAFFILIATED_HELP && host != null)
                    MaterialAlertDialogBuilder(this).setTitle("Private Space is blocking this").setMessage(msg)
                        .setPositiveButton("Copy setup command") { _, _ -> Bloat.copy(this, PrivateDns.ADB_GRANT) }
                        .setNeutralButton("Use VPN filter") { _, _ -> useVpnFamily() }
                        .setNegativeButton("Close", null).show()
                else toast(msg)
                if (!isFinishing) render()
            }
        }.start()
    }

    /** Family-safe filtering through the app's own VPN (works with Private Space present). */
    private fun useVpnFamily() {
        Prefs.setFamilyDns(this, true)
        DnsVpnService.reload(this)
        if (WebFilter.enable(this)) toast("VPN filter on with family-safe DNS")
        render()
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
            val chosen = Prefs.privateDnsHost(context)
            val backup = SmartDns.fallbackActive(context)
            text("Current: " + when {
                backup && chosen != null ->
                    (PrivateDns.PROVIDERS.firstOrNull { it.host == chosen }?.name ?: chosen) + " (backup mode on this network)"
                host == null -> "Off"
                provider != null -> "${provider.name} ($host)"
                else -> "Custom ($host)"
            }, 15f, bold = true, color = if (host != null || backup) Palette.GREEN else null)

            val canWrite = PrivateDns.canWrite(context)
            val locked = Prefs.privateDnsHost(context)
            when {
                locked != null && canWrite ->
                    text("🔒 Locked: if anyone changes Private DNS, it's switched back automatically.", 12f, color = Palette.GREEN)
                !canWrite -> {
                    text(
                        "⚠️ One-time setup needed. vivo Settings ignores Android's Private DNS lock" +
                            (if (PrivateDns.hasOtherUsers(context)) ", and Private Space blocks the normal method" else "") +
                            ". Connect the phone to the PC and run this once:",
                        12f, color = Palette.ORANGE,
                    )
                    text(PrivateDns.ADB_GRANT, 12f, bold = true).setTextIsSelectable(true)
                    button("Copy setup command", outlined = true) { Bloat.copy(this@WebActivity, PrivateDns.ADB_GRANT) }
                }
            }
            button("Choose filter") { choosePrivateDns() }.isEnabled = owner
            }

            // ---- Smart DNS: office-network backup ----
            if (Prefs.privateDnsHost(context) != null) card("Office network backup") {
                text(
                    "Some networks (often office Wi-Fi) block the filter's secure DNS (port 853), so the internet stops working. " +
                        "DeviceGuard notices within seconds and keeps filtering through a backup over HTTPS, so the internet keeps working. " +
                        "It switches back automatically on other networks.",
                    13f,
                ).alpha = 0.8f
                switchRow(
                    "Automatic backup",
                    if (PrivateDns.canWrite(context)) "Recommended" else "Needs the one-time setup command above",
                    SmartDns.autoOn(context), owner && PrivateDns.canWrite(context),
                ) { _, on -> SmartDns.setAuto(this@WebActivity, on); render(); true }
                text(
                    if (SmartDns.fallbackActive(context)) "🛡️ Now: backup filter (this network blocks secure DNS)"
                    else "✅ Now: normal Private DNS filter",
                    13f, bold = true,
                    color = if (SmartDns.fallbackActive(context)) Palette.ORANGE else Palette.GREEN,
                )
                text("Note: in backup mode the filter covers the main profile only, not Private Space.", 12f).alpha = 0.6f

                section("Office domains (intranet)")
                text("Names that only your office DNS knows, e.g. corp.local or intranet.company.com. Used in backup mode.", 12f).alpha = 0.7f
                val od = EditText(context).apply {
                    hint = "intranet.company.com"
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                addView(od)
                button("Add office domain", outlined = true) {
                    val d = WebFilter.normalize(od.text.toString())
                        ?: od.text.toString().trim().lowercase().takeIf { Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*$").matches(it) }
                    if (d == null) toast("Enter a domain like corp.local") else {
                        Prefs.setOfficeDomains(this@WebActivity, Prefs.officeDomains(this@WebActivity) + d)
                        DnsVpnService.reload(this@WebActivity); render()
                    }
                }
                Prefs.officeDomains(context).sorted().forEach { d ->
                    text("🏢  $d", 15f).setOnClickListener {
                        confirm("Remove $d?", "It will be resolved through the family filter again.", "Remove") {
                            Prefs.setOfficeDomains(this@WebActivity, Prefs.officeDomains(this@WebActivity) - d)
                            DnsVpnService.reload(this@WebActivity); render()
                        }
                    }
                }
                val remembered = Prefs.dnsBlockedNets(context).size
                if (remembered > 0) button("Forget $remembered remembered network(s)", outlined = true) {
                    SmartDns.forgetNetworks(this@WebActivity); GuardService.refresh(this@WebActivity); render()
                }
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
            switchRow(
                "Allow only Chrome",
                "Hides every other browser now and any browser installed later, so Chrome's rules can't be avoided",
                Prefs.getFlag(context, Prefs.CHROME_ONLY), owner,
            ) { _, on ->
                val n = ChromePolicy.setChromeOnly(this@WebActivity, on)
                toast(if (on) "Only Chrome allowed ($n other browser(s) hidden)" else "Other browsers allowed again ($n restored)")
                render(); true
            }
            text(
                "Chrome rules don't reach browsers inside Private Space or the built-in browsers of apps like Instagram. " +
                    "To block your site list there too, use a custom Private DNS (e.g. NextDNS) in the Family-safe filter above.",
                12f,
            ).alpha = 0.65f

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
