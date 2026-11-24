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

    // NextDNS state (loaded from the API in the background)
    private var nd: NextDns.State? = null
    private var ndError: String? = null

    override fun onResume() {
        super.onResume()
        if (!isFinishing) { render(); loadNextDns() }
    }

    private fun loadNextDns() {
        if (!NextDns.configured(this)) return
        Thread {
            val r = runCatching { NextDns.load(this) }
            runOnUiThread {
                nd = r.getOrNull(); ndError = r.exceptionOrNull()?.message
                if (!isFinishing) render()
            }
        }.start()
    }

    /** Runs a NextDNS API change in the background, then reloads the state. */
    private fun ndCall(block: () -> Unit) {
        Thread {
            val r = runCatching(block)
            runOnUiThread { r.exceptionOrNull()?.let { toast(it.message) }; loadNextDns() }
        }.start()
    }

    private fun nextDnsSetup() {
        val col = column()
        val id = EditText(this).apply { hint = "Profile ID, e.g. abc123"; setText(NextDns.id(this@WebActivity) ?: ""); isSingleLine = true }
        val key = EditText(this).apply {
            hint = "API key"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.text("Profile ID: nextdns.io → Setup tab (\"ID: abc123\").", 12f).alpha = 0.7f
        col.addView(id)
        col.text("API key: nextdns.io → Account → API. Leave empty to keep the saved key.", 12f).alpha = 0.7f
        col.addView(key)
        MaterialAlertDialogBuilder(this).setTitle("Connect NextDNS").setView(col)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setStr(this, Prefs.NEXTDNS_ID, id.text.toString().trim())
                if (key.text.isNotBlank()) Prefs.setStr(this, Prefs.NEXTDNS_KEY, key.text.toString().trim())
                nd = null; ndError = null
                render(); loadNextDns()
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WebFilter.VPN_CONSENT_REQUEST) {
            if (resultCode == Activity.RESULT_OK) WebFilter.finishEnable(this)
            else {
                // Cancelled: undo the always-on setting, keep the current filter as it was.
                val m = Mdm(this)
                if (m.isOwner && !Prefs.webBlock(this)) runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, null, false) }
                toast("VPN filter not started. Your current filter is unchanged.")
            }
            render()
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

    /** Switch helper: turning ON (stronger) applies directly; turning OFF (weaker) needs the filter guard. */
    private fun guardOff(what: String, on: Boolean, apply: () -> Unit): Boolean {
        if (on) { apply(); render(); return true }
        Guard.require(this, what) { apply(); render() }
        return false
    }

    private val FAMILY_HOSTS = setOf("family.cloudflare-dns.com", "family.adguard-dns.com")

    /**
     * Leaves NextDNS for a built-in family filter (no filter guard: the phone stays filtered).
     * The family filter used last is offered first.
     */
    private fun stopNextDns() {
        val last = Prefs.getStr(this, PrivateDns.LAST_FAMILY)?.takeIf { it in FAMILY_HOSTS } ?: "family.adguard-dns.com"
        val options = PrivateDns.PROVIDERS.filter { it.host in FAMILY_HOSTS }.sortedBy { if (it.host == last) 0 else 1 }
        MaterialAlertDialogBuilder(this).setTitle("Switch back to")
            .setItems(options.map { "${it.name}: ${it.desc}" }.toTypedArray()) { _, i -> setPrivateDns(options[i].host) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun choosePrivateDns() {
        val items = PrivateDns.PROVIDERS.map { "${it.name}: ${it.desc}" } + "Custom (NextDNS, etc.)"
        MaterialAlertDialogBuilder(this).setTitle("Family-safe filter")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < PrivateDns.PROVIDERS.size) {
                    val h = PrivateDns.PROVIDERS[i].host
                    // Switching between family filters is free; Off or a weaker filter needs the guard.
                    if (h in FAMILY_HOSTS) setPrivateDns(h)
                    else Guard.require(this, if (h == null) "turn off the website filter" else "switch to a weaker filter") { setPrivateDns(h) }
                } else Guard.require(this, "use a custom DNS") { customPrivateDns() }
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
            guardOff("turn off \"$title\"", on) {
                Prefs.setFlag(this, key, on)
                ChromePolicy.apply(this)?.let { toast(it) }
            }
        }
    }

    private fun hideOtherBrowsers() {
        val pkgs = ChromePolicy.otherBrowsers(this)
        if (pkgs.isEmpty()) { toast("No other browsers found"); return }
        val labels = pkgs.map { p ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) + "\n$p"
        }.toTypedArray()
        val pick = BooleanArray(pkgs.size) { true }
        MaterialAlertDialogBuilder(this).setTitle("Hide these browsers?")
            .setMultiChoiceItems(labels, pick) { _, i, v -> pick[i] = v }
            .setPositiveButton("Hide") { _, _ ->
                val chosen = pkgs.filterIndexed { i, _ -> pick[i] }
                val n = chosen.size - Holds.set(this, Hold.HIDE_MANUAL, chosen, true).size
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

            // ---- Filter guard (self-control lock) ----
            card("Filter guard") {
                text(
                    "Makes it hard to weaken filtering on impulse. Turning anything off needs a typing puzzle and a maths question, " +
                        "then a cooling-off wait before you can confirm. Turning protections ON is always instant.",
                    13f,
                ).alpha = 0.8f
                switchRow("Filter guard", if (Guard.enabled(context)) "On" else "Off", Guard.enabled(context), owner) { _, on ->
                    guardOff("turn off the filter guard", on) { Prefs.setFlag(this@WebActivity, Prefs.GUARD_ON, on) }
                }
                if (Guard.enabled(context)) {
                    val d = Guard.delayMin(context)
                    text("Cooling-off wait: " + if (d == 0) "none (puzzle only)" else "$d minutes", 14f, bold = true)
                    button("Change wait time", outlined = true) {
                        val opts = Guard.DELAYS
                        MaterialAlertDialogBuilder(this@WebActivity).setTitle("Cooling-off wait")
                            .setItems(opts.map { if (it == 0) "None (puzzle only)" else "$it minutes" }.toTypedArray()) { _, i ->
                                val v = opts[i]
                                val set = { Prefs.setInt(this@WebActivity, Prefs.GUARD_DELAY, v); render() }
                                if (v >= d) set() else Guard.require(this@WebActivity, "shorten the wait time") { set() }
                            }.show()
                    }
                }
            }
            if (owner && !WebFilter.anyFilterActive(context)) card {
                text("⚠️ No website filter is active", 16f, bold = true, color = Palette.RED)
                text("Sites are only blocked inside Chrome right now. Tap Choose filter below (e.g. AdGuard Family) to protect every browser and app.", 13f)
            }

            // ---- 1. Family-safe (Private DNS) ----
            card("Family-safe filter · no VPN") {
            text("Forces the phone's Private DNS to a filtering service and locks the setting. Works in every app and browser.", 13f).alpha = 0.8f
            val chosen = Prefs.privateDnsHost(context)
            val backup = SmartDns.fallbackActive(context)
            text("Current: " + when {
                backup && chosen != null ->
                    (PrivateDns.PROVIDERS.firstOrNull { it.host == chosen }?.name ?: chosen) + " · paused: Automatic on this network"
                host == null -> "Off"
                provider != null -> "${provider.name} ($host)"
                else -> "Custom ($host)"
            }, 15f, bold = true, color = if (backup) Palette.ORANGE else if (host != null) Palette.GREEN else null)

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

            // ---- NextDNS categories ----
            card("NextDNS categories") {
                text(
                    "Use your own NextDNS profile as the filter and choose categories (adult, gambling, social…) here. " +
                        "Works in every app and in Private Space. Needs a free nextdns.io account.",
                    13f,
                ).alpha = 0.8f
                if (!NextDns.configured(context)) {
                    button("Connect NextDNS…") { nextDnsSetup() }.isEnabled = owner
                } else {
                    val using = Prefs.privateDnsHost(context) == NextDns.host(context)
                    text("Profile: ${NextDns.id(context)}" + if (using) " · ✅ in use" else "", 14f, bold = true)
                    if (!using) button("Use NextDNS as the filter") { setPrivateDns(NextDns.host(this@WebActivity)) }.isEnabled = owner
                    else button("Stop using NextDNS", outlined = true) { stopNextDns() }.isEnabled = owner
                    // NextDNS switches change the online profile directly; no filter guard (removed on request).
                    val st = nd
                    when {
                        ndError != null -> text("⚠️ $ndError", 12f, color = Palette.ORANGE)
                        st == null -> text("Loading categories…", 12f).alpha = 0.6f
                        else -> {
                            NextDns.CATEGORIES.forEach { (id, name) ->
                                switchRow(name, null, st.categories[id] == true, owner) { _, on ->
                                    ndCall { NextDns.setCategory(this@WebActivity, id, on) }; true
                                }
                            }
                            switchRow("SafeSearch", "Google, Bing, YouTube search", st.safeSearch, owner) { _, on ->
                                ndCall { NextDns.setFlag(this@WebActivity, "safeSearch", on) }; true
                            }
                            switchRow("YouTube restricted mode", null, st.youtube, owner) { _, on ->
                                ndCall { NextDns.setFlag(this@WebActivity, "youtubeRestrictedMode", on) }; true
                            }
                            switchRow("Block bypass methods", "VPNs, proxies, other DNS", st.blockBypass, owner) { _, on ->
                                ndCall { NextDns.setFlag(this@WebActivity, "blockBypass", on) }; true
                            }
                        }
                    }
                    button("Send my blocked sites to NextDNS", outlined = true) {
                        Thread {
                            val r = runCatching { NextDns.syncDenylist(this@WebActivity) }
                            runOnUiThread { toast(r.fold({ "$it site(s) added to NextDNS" }, { it.message })) }
                        }.start()
                    }.isEnabled = owner
                    button("Change NextDNS account…", outlined = true) { nextDnsSetup() }
                }
            }

            // ---- Smart DNS: switch to Automatic on networks that block the filter ----
            if (Prefs.privateDnsHost(context) != null) card("When the filter's DNS can't connect") {
                text(
                    "If the filter's secure DNS can't connect (office Wi-Fi blocking port 853, the filter service down, a mobile data problem), " +
                        "the internet would stop working. DeviceGuard notices within seconds and switches Private DNS to Automatic, so the internet keeps working " +
                        "(no family filter meanwhile; Chrome rules still apply). The filter comes back when you change network, and on mobile data it's " +
                        "re-checked every 3 minutes and when the screen turns on. No VPN is used.",
                    13f,
                ).alpha = 0.8f
                switchRow(
                    "Switch to Automatic when blocked",
                    if (PrivateDns.canWrite(context)) "Recommended" else "Needs the one-time setup command above",
                    SmartDns.autoOn(context), owner && PrivateDns.canWrite(context),
                ) { _, on -> SmartDns.setAuto(this@WebActivity, on); render(); true }
                text(
                    if (SmartDns.fallbackActive(context)) "⚠️ Now: Automatic (the filter's DNS can't be reached on this network)"
                    else "✅ Now: Private DNS filter",
                    13f, bold = true,
                    color = if (SmartDns.fallbackActive(context)) Palette.ORANGE else Palette.GREEN,
                )
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
                    Guard.require(this@WebActivity, "unblock $d") {
                        confirm("Unblock $d?", "This site will be reachable again.", "Unblock") {
                            Prefs.setDomains(this@WebActivity, Prefs.domains(this@WebActivity) - d)
                            onDomainsChanged(this@WebActivity)
                            render()
                        }
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
                guardOff("turn off the Chrome rules", on) {
                    Prefs.setFlag(this@WebActivity, Prefs.CHROME_ON, on)
                    ChromePolicy.apply(this@WebActivity)?.let { toast(it) }
                }
            }
            val sub = owner && chromeOn
            chromeSwitch(this, "Adult site filter", "Google SafeSites", Prefs.CHROME_SAFESITES, sub)
            chromeSwitch(this, "Force Google SafeSearch", "Explicit results hidden in Google Search", Prefs.CHROME_SAFESEARCH, sub)
            chromeSwitch(this, "YouTube restricted mode", "Moderate restricted mode on youtube.com", Prefs.CHROME_YOUTUBE, sub)
            switchRow(
                "Disable incognito / private browsing",
                "Other browsers can't have private mode turned off, so this also turns on Allow only Chrome below",
                Prefs.getFlag(context, Prefs.CHROME_NO_INCOGNITO), sub,
            ) { _, on ->
                guardOff("allow incognito browsing", on) {
                    Prefs.setFlag(this@WebActivity, Prefs.CHROME_NO_INCOGNITO, on)
                    ChromePolicy.apply(this@WebActivity)?.let { toast(it) }
                    if (on && !Prefs.getFlag(this@WebActivity, Prefs.CHROME_ONLY)) {
                        val n = ChromePolicy.setChromeOnly(this@WebActivity, true)
                        toast("Incognito off. Only Chrome allowed now ($n other browser(s) hidden)")
                    }
                }
            }
            switchRow(
                "Allow only Chrome",
                "Hides every other browser now and any browser installed later, so Chrome's rules can't be avoided",
                Prefs.getFlag(context, Prefs.CHROME_ONLY), owner,
            ) { _, on ->
                guardOff("allow other browsers", on) {
                    val n = ChromePolicy.setChromeOnly(this@WebActivity, on)
                    toast(if (on) "Only Chrome allowed ($n other browser(s) hidden)" else "Other browsers allowed again ($n restored)")
                }
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
            ) { _, on ->
                // No filter guard on the VPN switches (removed on request).
                if (!on) { WebFilter.disable(this@WebActivity); render(); true }
                else WebFilter.enable(this@WebActivity)
            }
            switchRow(
                "Family-safe upstream for VPN",
                "VPN filter also blocks adult and malware sites (Cloudflare 1.1.1.3)",
                Prefs.familyDns(context),
            ) { _, on -> Prefs.setFamilyDns(this@WebActivity, on); DnsVpnService.reload(this@WebActivity); true }
            }

            text("Tip: browsers cache lookups. If a newly blocked site still opens, close and reopen the browser.", 12f).alpha = 0.6f
        }
    }
}
