// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.text.InputType
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Website blocking, two layers:
 *  1. Family-safe filter via forced Private DNS (whole phone, every app), e.g. AdGuard Family
 *  2. Your own blocked-site list enforced in Chrome via managed policies
 * The VPN filter was removed (R-03, 2026-11-25).
 */
class WebActivity : BaseActivity() {

    /** The search keyword list is hidden until the admin PIN is entered again; hidden again when leaving the screen. */
    private var showKeywords = false

    override fun onStop() {
        super.onStop()
        showKeywords = false
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    private fun addDomains(d: Set<String>) {
        Prefs.updateSet(this, Prefs.K_DOMAINS) { it + d }
        onDomainsChanged(this)
        render()
    }

    private fun setPrivateDns(host: String?) {
        toast("Applying…")
        inBackground({ try { PrivateDns.apply(this, host) } catch (e: Exception) { e.message ?: "Failed" } }) { msg ->
            if (msg == PrivateDns.UNAFFILIATED_HELP && host != null)
                MaterialAlertDialogBuilder(this).setTitle("Private Space is blocking this").setMessage(msg)
                    .setPositiveButton("Copy setup command") { _, _ -> Bloat.copy(this, PrivateDns.ADB_GRANT) }
                    .setNegativeButton("Close", null).show()
            else toast(msg)
            render()
        }
    }

    /** Switch helper: turning ON (stronger) applies directly; turning OFF (weaker) needs the filter guard. */
    private fun guardOff(what: String, on: Boolean, apply: () -> Unit): Boolean {
        if (on) { apply(); render(); return true }
        Guard.require(this, what) { apply(); render() }
        return false
    }

    private val FAMILY_HOSTS = setOf("family.cloudflare-dns.com", "family.adguard-dns.com")

    private fun choosePrivateDns() {
        val items = PrivateDns.PROVIDERS.map { "${it.name}: ${it.desc}" } + "Custom DNS-over-TLS host"
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
            hint = "e.g. dns.example.com"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        MaterialAlertDialogBuilder(this).setTitle("Custom Private DNS host")
            .setMessage("Enter a DNS-over-TLS hostname (port 853).")
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
            Apps.label(this, p) + "\n$p"
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
            card("Family-safe filter (Private DNS)") {
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

            // ---- Smart DNS: switch to Automatic on networks that block the filter ----
            if (Prefs.privateDnsHost(context) != null) card("When the filter's DNS can't connect") {
                text(
                    "Only on Wi-Fi: if a Wi-Fi network (e.g. office Wi-Fi) blocks the filter's secure DNS (port 853), DeviceGuard switches " +
                        "Private DNS to Automatic so the internet works there (no family filter meanwhile; Chrome rules still apply), and re-checks " +
                        "every 3 minutes. The filter is enforced again the moment Wi-Fi disconnects or is turned off. On mobile data the filter " +
                        "is always on: it never switches to Automatic.",
                    13f,
                ).alpha = 0.8f
                switchRow(
                    "Switch to Automatic when blocked",
                    if (PrivateDns.canWrite(context)) "Recommended" else "Needs the one-time setup command above",
                    SmartDns.autoOn(context), owner && PrivateDns.canWrite(context),
                ) { _, on -> SmartDns.setAuto(this@WebActivity, on); render(); true }
                text(
                    if (SmartDns.fallbackActive(context)) "⚠️ Now: Automatic (this Wi-Fi blocks the filter's DNS)"
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
            text("Blocked by the Private DNS filter and by Chrome rules (in Chrome and every browser that accepts the policy profile). Blocking a domain also blocks its subdomains.", 13f).alpha = 0.8f
            val input = EditText(context).apply {
                hint = "example.com"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            input.keepText("blockSite") // B-07: typed text survives redraws
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
                            Prefs.updateSet(this@WebActivity, Prefs.K_DOMAINS) { it - d }
                            onDomainsChanged(this@WebActivity)
                            render()
                        }
                    }
                }
            }

            }

            // ---- Search keyword blocker (Chrome policy) ----
            card("Search keyword blocker") {
                text(
                    "Google, Bing, DuckDuckGo, Yahoo and YouTube searches that are one of these words or start with it are refused " +
                        "in Chrome and every browser that accepts the policy profile (see Chrome rules). End a word with * to also " +
                        "block longer words (porn* blocks pornography). Removing a keyword needs the filter guard.",
                    13f,
                ).alpha = 0.8f
                val kws = SearchBlocker.keywords(context).sorted()
                if (kws.isNotEmpty()) {
                    val targets = ChromePolicy.lastTargets(context)
                    val on = targets.isNotEmpty()
                    text(
                        if (on) "✅ On in: " + targets.joinToString { Apps.label(context, it) }
                        else "⚠️ Not applied: no browser here accepts the policy profile (install Chrome)",
                        13f, bold = true, color = if (on) Palette.GREEN else Palette.ORANGE,
                    )
                    val sites = if (Prefs.getFlag(context, Prefs.CHROME_ON)) Prefs.domains(context).size + Prefs.stSites(context).size else 0
                    val rules = SearchBlocker.chromeRules(context).size
                    if (sites + rules > ChromePolicy.MAX_BLOCKLIST) text(
                        "Chrome takes 1000 rules, so only some keywords fit (your blocked sites come first).", 12f, color = Palette.ORANGE,
                    )
                }
                val input = EditText(context).apply {
                    hint = "word, or several separated by commas"
                    isSingleLine = true
                }
                input.keepText("blockKeyword") // typed text survives redraws (B-07)
                addView(input)
                button("Block these keywords") {
                    val n = SearchBlocker.add(this@WebActivity, input.text.toString())
                    if (n == 0) toast("Enter a word of at least 2 letters (or it's already blocked)")
                    else { toast("$n keyword(s) blocked"); input.text.clear() }
                    render()
                }.isEnabled = owner
                if (kws.isEmpty()) text("No keywords blocked yet.", 13f).alpha = 0.6f
                else if (!showKeywords) {
                    text("Blocked keywords: ${kws.size} (hidden)", 13f, bold = true)
                    button("Show keywords (PIN)", outlined = true) { askPin { showKeywords = true; render() } }
                } else {
                    text("Blocked keywords (${kws.size}): tap one to remove", 13f, bold = true)
                    button("Hide keywords", outlined = true) { showKeywords = false; render() }
                }
                if (showKeywords) kws.forEach { k ->
                    text("🔎  $k", 16f).setOnClickListener {
                        Guard.require(this@WebActivity, "remove the search keyword \"$k\"") {
                            confirm("Remove \"$k\"?", "Searches containing it won't be blocked any more.", "Remove") {
                                SearchBlocker.remove(this@WebActivity, k)
                                render()
                            }
                        }
                    }
                }
            }

            // ---- 2. Chrome policies ----
            card("Chrome rules") {
            if (!ChromePolicy.installed(context)) text("Chrome isn't installed on this phone.", 13f, bold = true)
            run {
                val got = ChromePolicy.lastTargets(context)
                val ignore = SearchBlocker.browserPackages(context).filter { it !in got && it != SearchBlocker.GOOGLE_APP }
                if (got.isNotEmpty()) text("Policy profile installed in: " + got.joinToString { Apps.label(context, it) }, 13f, bold = true)
                if (ignore.isNotEmpty()) text(
                    "These browsers don't accept policies (Android lets each browser decide): " + ignore.joinToString { Apps.label(context, it) } +
                        ". Blocked sites and keywords aren't enforced in them; Allow only Chrome hides them.",
                    12f,
                ).alpha = 0.7f
            }
            text("Enforced inside Chrome through its enterprise policies. Restart Chrome after changes; check them at chrome://policy.", 13f).alpha = 0.8f
            val chromeOn = Prefs.getFlag(context, Prefs.CHROME_ON)
            switchRow("Enforce in Chrome", "Chrome's own rules for your list and the search keywords", chromeOn, owner) { _, on ->
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
                    "Use Allow only Chrome to hide other browsers; the family filter above still covers every app.",
                12f,
            ).alpha = 0.65f

            }

            text("Tip: browsers cache lookups. If a newly blocked site still opens, close and reopen the browser.", 12f).alpha = 0.6f
        }
    }
}
