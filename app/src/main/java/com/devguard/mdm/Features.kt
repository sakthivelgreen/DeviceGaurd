// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle

/** Website blocking through the always-on DNS filter VPN. */
object WebFilter {
    const val VPN_CONSENT_REQUEST = 77

    /**
     * Starts the VPN filter. Nothing else changes until the VPN can really start:
     * if VPN permission is needed, [finishEnable] runs only after the user allows it,
     * so a cancelled prompt never leaves the phone with no filter at all.
     * Returns true if blocking is running now; false if waiting for VPN consent.
     */
    fun enable(a: Activity): Boolean {
        val m = Mdm(a)
        // As Device Owner, always-on also grants VPN permission (no prompt).
        if (m.isOwner) runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, a.packageName, false) }
            .onFailure { a.toast("Always-on VPN failed: ${it.message}") }
        if (VpnService.prepare(a) != null) {
            a.startActivityForResult(VpnService.prepare(a), VPN_CONSENT_REQUEST)
            return false
        }
        finishEnable(a)
        return true
    }

    /** Second half of [enable], once VPN permission exists. Replaces the Private DNS filter (they can't run together). */
    fun finishEnable(a: Activity) {
        if (Prefs.privateDnsHost(a) != null) runCatching { PrivateDns.apply(a, null) }
        Prefs.setWebBlock(a, true)
        DnsVpnService.reload(a)
        a.startService(Intent(a, DnsVpnService::class.java))
    }

    /** True if a DNS-level filter (Private DNS, its office backup, or the VPN filter) is protecting the phone. */
    fun anyFilterActive(c: Context) =
        Prefs.webBlock(c) || SmartDns.fallbackActive(c) || Prefs.privateDnsHost(c) != null || PrivateDns.currentHost(c) != null

    fun disable(c: Context) {
        Prefs.setWebBlock(c, false)
        val m = Mdm(c)
        if (m.isOwner) runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, null, false) }
        runCatching { c.startService(Intent(c, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
    }

    /** Turns user input like "https://www.Example.com/path" into "example.com". */
    fun normalize(input: String): String? {
        var d = input.trim().lowercase()
        d = d.removePrefix("http://").removePrefix("https://").removePrefix("www.")
        d = d.substringBefore('/').substringBefore(':').trimEnd('.')
        return if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(d)) d else null
    }

    val PRESET_SOCIAL = setOf(
        "facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com", "tiktok.com", "tiktokcdn.com",
        "snapchat.com", "x.com", "twitter.com", "twimg.com", "reddit.com", "redd.it", "threads.net",
    )
    val PRESET_VIDEO = setOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "netflix.com", "nflxvideo.net",
        "primevideo.com", "hotstar.com", "jiocinema.com", "twitch.tv",
    )
    val PRESET_GAMES = setOf(
        "roblox.com", "rbxcdn.com", "epicgames.com", "steampowered.com", "steamcommunity.com",
        "miniclip.com", "poki.com", "crazygames.com",
    )
}

/**
 * Family-safe filtering without a VPN: force the phone's Private DNS (DNS-over-TLS)
 * to a filtering resolver and lock the setting.
 */
object PrivateDns {
    data class Provider(val name: String, val host: String?, val desc: String)

    const val UNAFFILIATED_HELP =
        "Android won't let the app use its normal method because Private Space (or App Clone / Guest) can't be managed by DeviceGuard.\n\n" +
            "One-time fix, Private Space stays: connect the phone to the PC and run the setup command " +
            "(tap Copy setup command). DeviceGuard can then set and lock Private DNS itself. Then choose the filter again.\n\n" +
            "Other options: VPN filter + Family-safe upstream (main profile only), or Chrome rules (Chrome only)."

    /** One-time ADB grant that lets the app write and lock Private DNS itself. */
    const val ADB_GRANT = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS"

    /** Prefs key: the last built-in filter host used (offered first when leaving NextDNS). */
    const val LAST_FAMILY = "lastFamilyDns"

    private const val MODE = "private_dns_mode"
    private const val SPEC = "private_dns_specifier"

    /** True after the one-time ADB grant: the app can write Private DNS directly and auto-revert edits. */
    fun canWrite(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Writes Private DNS directly. The "Lock Private DNS" restriction makes SettingsProvider silently
     * ignore writes from every app (only the system Settings app is exempt, which is how vivo Settings
     * can still change it), so the restriction is lifted for a moment and restored right after.
     * Returns true only if the new value was actually stored.
     */
    internal fun write(c: Context, host: String?): Boolean {
        val m = Mdm(c)
        val cr = c.contentResolver
        val key = android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        val locked = m.isOwner && m.hasRestriction(key)
        if (locked) runCatching { m.setRestriction(key, false) }
        val ok = runCatching {
            if (host == null) {
                android.provider.Settings.Global.putString(cr, MODE, "opportunistic")
                android.provider.Settings.Global.getString(cr, MODE) == "opportunistic"
            } else {
                android.provider.Settings.Global.putString(cr, SPEC, host)
                android.provider.Settings.Global.putString(cr, MODE, "hostname")
                android.provider.Settings.Global.getString(cr, MODE) == "hostname" &&
                    android.provider.Settings.Global.getString(cr, SPEC) == host
            }
        }.getOrDefault(false)
        if (locked) runCatching { m.setRestriction(key, true) }
        return ok
    }

    /**
     * Checks the host answers DNS-over-TLS on port 853 (blocking). Known filters are tested by their fixed IPs
     * (with the hostname for TLS), because the current filter may block looking up another DNS service's name
     * (e.g. NextDNS "Block bypass methods" answers 127.0.0.1 for family.cloudflare-dns.com).
     */

    /**
     * Lock enforcement: if the chosen filter host was changed (e.g. in vivo Settings, which ignores the
     * "Lock Private DNS" restriction), put it back. Returns true if a change was reverted.
     */
    fun enforce(c: Context): Boolean {
        val want = Prefs.privateDnsHost(c) ?: return false
        if (!canWrite(c) || !Mdm(c).isOwner) return false
        val cr = c.contentResolver
        val mode = android.provider.Settings.Global.getString(cr, MODE)
        val spec = android.provider.Settings.Global.getString(cr, SPEC)
        if (SmartDns.fallbackActive(c)) {
            // Backup mode: this network blocks the filter's DNS, so Private DNS stays on "automatic".
            if (mode == "opportunistic") return false
            write(c, null)
            return true
        }
        if (mode == "hostname" && spec == want) return false
        write(c, want)
        return true
    }

    /** True if users other than the main one exist (they block phone-wide policies like Private DNS). */
    fun hasOtherUsers(c: Context): Boolean = runCatching {
        c.getSystemService(android.os.UserManager::class.java).userProfiles.size > 1 ||
            Mdm(c).dpm.getSecondaryUsers(Mdm(c).admin).isNotEmpty()
    }.getOrDefault(false)

    val PROVIDERS = listOf(
        Provider("Off", null, "Phone default, no filtering"),
        Provider("Cloudflare Family", "family.cloudflare-dns.com", "Blocks adult content and malware"),
        Provider("Cloudflare Security", "security.cloudflare-dns.com", "Blocks malware only"),
        Provider("AdGuard Family", "family.adguard-dns.com", "Blocks adult content, ads and trackers; forces SafeSearch"),
    )

    /** Current Private DNS host (set by the app or from the PC over ADB), or null if not set. */
    fun currentHost(c: Context): String? {
        val m = Mdm(c)
        if (m.isOwner) runCatching {
            if (m.dpm.getGlobalPrivateDnsMode(m.admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)
                return m.dpm.getGlobalPrivateDnsHost(m.admin)
        }
        // Fallback: read the system setting directly (covers a host set with adb "settings put global ...").
        return runCatching {
            val cr = c.contentResolver
            if (android.provider.Settings.Global.getString(cr, "private_dns_mode") == "hostname")
                android.provider.Settings.Global.getString(cr, "private_dns_specifier")?.takeIf { it.isNotBlank() }
            else null
        }.getOrNull()
    }

    const val ADB_OFF = "adb shell settings put global private_dns_mode opportunistic"

    /**
     * Blocking (network check): call off the main thread. Returns a message for the user.
     * Uses the Device Owner API first; if Android refuses (Private Space = unaffiliated user),
     * writes the setting directly when the one-time ADB grant is present.
     * The chosen host is remembered so GuardService can auto-revert edits (vivo Settings ignores the lock).
     */
    fun apply(c: Context, host: String?): String {
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        SmartDns.reset(c) // leave backup mode; the network check runs again for the new choice
        if (host == null) {
            Prefs.setPrivateDnsHost(c, null)
            val ok = runCatching { m.dpm.setGlobalPrivateDnsModeOpportunistic(m.admin); true }.getOrDefault(false) ||
                (canWrite(c) && write(c, null))
            runCatching { m.setRestriction(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) }
            GuardService.refresh(c)
            return if (ok) "Private DNS filter turned off" else "Couldn't turn it off here. From the PC run:\n$ADB_OFF"
        }
        if (Prefs.webBlock(c)) WebFilter.disable(c) // the VPN filter can't work alongside a forced Private DNS host

        val viaDpm: Int? = try {
            m.dpm.setGlobalPrivateDnsModeSpecifiedHost(m.admin, host)
        } catch (e: SecurityException) {
            null // unaffiliated user (Private Space / App Clone) - try the direct method below
        }
        when (viaDpm) {
            DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> Unit
            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> {
                // Android's check looks the name up through the CURRENT filter, which may block it
                // (NextDNS "Block bypass methods"). Re-test by IP and write directly if it really works.
                if (!(canWrite(c) && SmartDns.dotReachable(host) && write(c, host)))
                    return "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
            }
            null -> {
                if (!canWrite(c)) return UNAFFILIATED_HELP
                if (!SmartDns.dotReachable(host)) return "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
                if (!write(c, host)) return "Couldn't write the Private DNS setting"
            }
            else -> return "Couldn't set Private DNS"
        }
        Prefs.setPrivateDnsHost(c, host)
        if (PROVIDERS.any { it.host == host }) Prefs.setStr(c, LAST_FAMILY, host) // for "Stop using NextDNS"
        runCatching { m.setRestriction(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true) }
        GuardService.refresh(c)
        return if (canWrite(c)) "Filter active: $host (locked: any change is reverted automatically)"
        else "Filter active: $host. To stop it being edited in vivo Settings, run the one-time setup command (see Websites)."
    }
}

/**
 * Smart DNS: keeps the family filter working on networks that block DNS-over-TLS (port 853), e.g. office Wi-Fi.
 *
 *  - On every network change GuardService calls [check]. It tests port 853 to the filter on that network (2–4 s).
 *  - Blocked → [enterFallback]: Private DNS is set to "automatic" so the internet works (no family filter on that network).
 *    No VPN is used any more (many apps failed to open through it).
 *  - Networks that blocked 853 are remembered → next time it switches instantly (then re-verified in the background).
 *  - 853 works again → [exitFallback]: Private DNS filter restored.
 */
object SmartDns {
    private const val NOTIFY_ID = 4242
    @Volatile private var lastKey: String? = null
    @Volatile private var lastAt = 0L

    fun autoOn(c: Context) = !Prefs.getFlag(c, Prefs.SMART_DNS_OFF)
    fun enabled(c: Context) = autoOn(c) && Prefs.privateDnsHost(c) != null && PrivateDns.canWrite(c) && Mdm(c).isOwner
    fun fallbackActive(c: Context) = Prefs.getFlag(c, Prefs.DNS_FALLBACK)

    /** Stops a VPN that an older version started for backup mode (backup no longer uses the VPN). */
    fun cleanup(c: Context) {
        if (Prefs.webBlock(c)) return
        val m = Mdm(c)
        if (m.isOwner && runCatching { m.dpm.getAlwaysOnVpnPackage(m.admin) }.getOrNull() == c.packageName)
            runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, null, false) }
        runCatching { c.startService(Intent(c, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
    }

    /** Known IPs for the filter servers, so the test doesn't need DNS (which is what's broken). */
    fun bootstrap(host: String): List<String> = when {
        host == "family.cloudflare-dns.com" -> listOf("1.1.1.3", "1.0.0.3")
        host == "security.cloudflare-dns.com" -> listOf("1.1.1.2", "1.0.0.2")
        host == "family.adguard-dns.com" -> listOf("94.140.14.15", "94.140.15.16")
        host == "dns.adguard-dns.com" -> listOf("94.140.14.14", "94.140.15.15")
        host.endsWith(".dns.nextdns.io") -> listOf("45.90.28.0", "45.90.30.0")
        else -> emptyList()
    }

    private fun networkKey(cm: android.net.ConnectivityManager, n: android.net.Network): String? {
        val caps = cm.getNetworkCapabilities(n) ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        val t = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.sorted().joinToString(",")
        return "$t|${lp.domains ?: ""}|$dns"
    }

    /** True if a TLS connection to [host]:853 works on network [n]. */
    /**
     * True if [host] completes a TLS handshake on port 853 (DNS-over-TLS). Uses the bootstrap IPs first, so it
     * works while DNS itself is broken or filtered (e.g. NextDNS blocking other DNS names); [n] picks the
     * network (null = the default one). One test for Private DNS and Smart DNS (D-05).
     */
    fun dotReachable(host: String, n: android.net.Network? = null, timeoutMs: Int = 4000): Boolean {
        val ips = bootstrap(host).mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            .ifEmpty { runCatching { (n?.getAllByName(host) ?: java.net.InetAddress.getAllByName(host)).toList() }.getOrDefault(emptyList()) }
        val tls = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        for (ip in ips.take(2)) {
            val ok = runCatching {
                (n?.socketFactory?.createSocket() ?: java.net.Socket()).use { raw ->
                    raw.connect(java.net.InetSocketAddress(ip, 853), timeoutMs)
                    (tls.createSocket(raw, host, 853, true) as javax.net.ssl.SSLSocket).use { s ->
                        s.soTimeout = timeoutMs
                        s.startHandshake()
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Runs on a background thread for every network change, and every few minutes from GuardService
     * ([force] = true) so a filter that stops responding on the SAME network also falls back to Automatic,
     * and the filter comes back as soon as it responds again.
     */
    @Synchronized
    fun check(c: Context, n: android.net.Network, force: Boolean = false) {
        if (!enabled(c)) { if (fallbackActive(c)) exitFallback(c); return }
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(n) ?: return
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return
        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return // wait for Wi-Fi login
        val key = networkKey(cm, n) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && key == lastKey && now - lastAt < 60_000) return
        lastKey = key; lastAt = now
        val host = Prefs.privateDnsHost(c) ?: return

        // Known blocking network: switch instantly, then re-verify below.
        if (!force && key in Prefs.dnsBlockedNets(c) && !fallbackActive(c)) enterFallback(c)

        // One retry after 2 s, so a single dropped handshake doesn't switch the filter off.
        if (dotReachable(host, n, timeoutMs = 3000) || (!fallbackActive(c) && run { Thread.sleep(2000); dotReachable(host, n, timeoutMs = 3000) })) {
            Prefs.updateSet(c, Prefs.K_DNS_BLOCKED_NETS) { it - key }
            if (fallbackActive(c)) exitFallback(c)
        } else {
            Prefs.updateSet(c, Prefs.K_DNS_BLOCKED_NETS) { it + key }
            if (!fallbackActive(c)) enterFallback(c)
        }
    }

    fun onMobileData(c: Context): Boolean {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Reconnect check of the current network (GuardService: on mobile data while on Automatic). */
    fun checkNow(c: Context) {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return
        check(c, n, force = true)
    }

    /**
     * The filter's DNS can't be reached on this network: switch Private DNS to "Automatic" so the internet
     * keeps working (no filtering on this network). No VPN is used: many apps failed to open through it.
     */
    fun enterFallback(c: Context) {
        Prefs.setFlag(c, Prefs.DNS_FALLBACK, true)
        PrivateDns.write(c, null)
        Notify.post(
            c, Channel.NETWORK, NOTIFY_ID, "Private DNS on Automatic",
            "The filter's DNS can't be reached right now. It's re-checked every few minutes and comes back on its own.",
            ongoing = true,
        )
    }

    fun exitFallback(c: Context) {
        Prefs.setFlag(c, Prefs.DNS_FALLBACK, false)
        Prefs.privateDnsHost(c)?.let { PrivateDns.write(c, it) } // filter back on Private DNS
        cleanup(c)
        Notify.cancel(c, NOTIFY_ID)
    }

    /** Leave backup mode without touching Private DNS (caller sets it) and force a fresh check. */
    fun reset(c: Context) {
        lastKey = null
        if (!fallbackActive(c)) return
        Prefs.setFlag(c, Prefs.DNS_FALLBACK, false)
        cleanup(c)
        Notify.cancel(c, NOTIFY_ID)
    }

    /** Turn the automatic backup on/off from the UI. */
    fun setAuto(c: Context, on: Boolean) {
        Prefs.setFlag(c, Prefs.SMART_DNS_OFF, !on)
        lastKey = null
        if (!on && fallbackActive(c)) exitFallback(c)
        GuardService.refresh(c)
    }

    fun forgetNetworks(c: Context) { Prefs.setSet(c, Prefs.K_DNS_BLOCKED_NETS, emptySet()); lastKey = null }
}

/** Website rules enforced inside Chrome through its managed (enterprise) policies. No VPN needed. */
object ChromePolicy {
    const val CHROME = "com.android.chrome"

    fun installed(c: Context) = runCatching { c.packageManager.getApplicationInfo(CHROME, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.isSuccess

    /** Pushes the current settings to Chrome. Returns null on success or an error message. */
    fun apply(c: Context): String? {
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        val b = Bundle()
        if (Prefs.getFlag(c, Prefs.CHROME_ON)) {
            // Your list + sites blocked by an active screen-time schedule.
            b.putStringArray("URLBlocklist", (Prefs.domains(c) + Prefs.stSites(c)).sorted().toTypedArray())
            if (Prefs.getFlag(c, Prefs.CHROME_SAFESITES)) b.putInt("SafeSitesFilterBehavior", 1)
            if (Prefs.getFlag(c, Prefs.CHROME_SAFESEARCH)) b.putBoolean("ForceGoogleSafeSearch", true)
            if (Prefs.getFlag(c, Prefs.CHROME_YOUTUBE)) b.putInt("ForceYouTubeRestrict", 1)
            if (Prefs.getFlag(c, Prefs.CHROME_NO_INCOGNITO)) b.putInt("IncognitoModeAvailability", 1)
            b.putString("DnsOverHttpsMode", "off") // keep Chrome on system DNS so Private DNS filtering applies
        }
        m.dpm.setApplicationRestrictions(m.admin, CHROME, b)
        return null
    }

    fun clear(c: Context) {
        val m = Mdm(c)
        if (m.isOwner) runCatching { m.dpm.setApplicationRestrictions(m.admin, CHROME, Bundle()) }
    }

    /** Installed browsers other than Chrome (apps that open any web link). */
    fun otherBrowsers(c: Context): List<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        return c.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != CHROME && it != c.packageName }
    }

    /** True if [pkg] is a general web browser (opens any http link). */
    fun isBrowser(c: Context, pkg: String): Boolean {
        if (pkg == CHROME || pkg == c.packageName) return false
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        return c.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL).isNotEmpty()
    }

    /** "Allow only Chrome": hide every other browser now; GuardService hides new ones on install. */
    fun setChromeOnly(c: Context, on: Boolean): Int {
        Prefs.setFlag(c, Prefs.CHROME_ONLY, on)
        val n = if (on) {
            val pkgs = otherBrowsers(c)
            pkgs.size - Holds.set(c, Hold.HIDE_CHROME_ONLY, pkgs, true).size
        } else {
            // Drops only this feature's claim: a browser you hid yourself stays hidden.
            val pkgs = Prefs.hiddenBrowsers(c)
            Holds.set(c, Hold.HIDE_CHROME_ONLY, pkgs, false)
            pkgs.count { Holds.owners(c, it, hide = true).isEmpty() }
        }
        GuardService.refresh(c)
        return n
    }

    /** Called by GuardService for every new install. Returns true if it was a browser and got hidden. */
    fun onInstalled(c: Context, pkg: String): Boolean {
        if (!Prefs.getFlag(c, Prefs.CHROME_ONLY) || !isBrowser(c, pkg)) return false
        if (!Holds.set(c, Hold.HIDE_CHROME_ONLY, pkg, true)) return false
        Notify.post(c, Channel.BLOCKED, ("brw$pkg").hashCode(), "${Blocklist.label(c, pkg)} is not allowed",
            "Only Chrome can be used as a browser on this phone.")
        return true
    }
}

/** Called whenever the blocked-site list changes: update every enforcement layer. */
fun onDomainsChanged(c: Context) {
    DnsVpnService.reload(c)
    if (Prefs.getFlag(c, Prefs.CHROME_ON)) runCatching { ChromePolicy.apply(c) }
}

/** Kiosk mode: lock the phone to a set of approved apps using lock task mode. */
object Kiosk {
    private const val ALIAS = "com.devguard.mdm.KioskHome"

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    /** Returns null on success or an error message. */
    fun start(a: Activity): String? {
        val m = Mdm(a)
        if (!m.isOwner) return "Device Owner required for kiosk mode"
        val apps = Prefs.kioskPkgs(a)
        if (apps.isEmpty()) return "Choose kiosk apps first (Apps & Play Store → tap an app → Allowed in kiosk)"
        m.dpm.setLockTaskPackages(m.admin, (apps + a.packageName).toTypedArray())
        m.dpm.setLockTaskFeatures(
            m.admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
        )
        val alias = ComponentName(a, ALIAS)
        a.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        m.dpm.addPersistentPreferredActivity(m.admin, homeFilter(), alias)
        Prefs.setKioskOn(a, true)
        a.startActivity(Intent(a, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        return null
    }

    fun stop(a: Activity) {
        val m = Mdm(a)
        Prefs.setKioskOn(a, false)
        if (m.isOwner) runCatching { m.dpm.clearPackagePersistentPreferredActivities(m.admin, a.packageName) }
        runCatching {
            a.packageManager.setComponentEnabledSetting(
                ComponentName(a, ALIAS), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
        }
        runCatching { a.stopLockTask() }
        if (m.isOwner) runCatching { m.dpm.setLockTaskPackages(m.admin, arrayOf()) }
    }
}

/**
 * Always-blocked apps: if a listed app is installed (Play Store, APK, anything),
 * it is uninstalled silently right away. System apps that can't be uninstalled are hidden.
 */
object Blocklist {
    /** Well-known apps for quick add: package -> name. */
    val PRESETS = linkedMapOf(
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.snapchat.android" to "Snapchat",
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.pubg.imobile" to "BGMI",
        "com.roblox.client" to "Roblox",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.netflix.mediaclient" to "Netflix",
    )

    /** Accepts a package name or a Play Store link (…details?id=com.example.app). */
    fun parse(input: String): String? {
        val s = input.trim()
        val fromUrl = Regex("[?&]id=([A-Za-z0-9_.]+)").find(s)?.groupValues?.get(1)
        val p = (fromUrl ?: s).trim()
        return if (Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(p)) p else null
    }

    fun isInstalled(c: Context, pkg: String) = runCatching {
        val ai = c.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }.getOrDefault(false)

    fun label(c: Context, pkg: String): String = PRESETS[pkg] ?: Apps.label(c, pkg)

    fun add(c: Context, pkgs: Collection<String>) {
        Prefs.updateSet(c, Prefs.K_BLOCKED_PKGS) { it + pkgs }
        GuardService.refresh(c)
        pkgs.forEach { if (isInstalled(c, it)) remove(c, it, notify = false) }
    }

    fun unblock(c: Context, pkg: String) {
        Prefs.updateSet(c, Prefs.K_BLOCKED_PKGS) { it - pkg }
        // If it was a system app we hid, drop Blocked apps' claim (it stays hidden if you hid it too).
        Holds.set(c, Hold.HIDE_BLOCKLIST, pkg, false)
        GuardService.refresh(c)
    }

    fun onInstalled(c: Context, pkg: String): Boolean {
        if (pkg !in Prefs.blockedPkgs(c)) return false
        remove(c, pkg, notify = true)
        return true
    }

    fun sweep(c: Context) {
        Prefs.blockedPkgs(c).filter { isInstalled(c, it) }.forEach { remove(c, it, notify = true) }
    }

    /** Silent uninstall (Device Owner). System apps can't be uninstalled, so they're hidden. */
    private fun remove(c: Context, pkg: String, notify: Boolean) {
        val m = Mdm(c)
        if (!m.isOwner || pkg == c.packageName) return
        val name = label(c, pkg)
        val system = runCatching { (c.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrDefault(false)
        if (system) {
            Holds.set(c, Hold.HIDE_BLOCKLIST, pkg, true)
        } else {
            runCatching { AppControl.uninstall(c, pkg) } // a failure shows its own notification
        }
        if (notify) Notify.post(c, Channel.BLOCKED, ("blk$pkg").hashCode(), "$name is not allowed",
            "This app is blocked by your administrator and was removed.")
    }
}

/** Approved-apps mode: newly installed apps stay paused until the admin approves them. */
object Approval {
    private fun installed(c: Context) = Apps.packages(c).toSet()

    fun enable(c: Context) {
        // Everything already on the phone counts as approved.
        Prefs.updateSet(c, Prefs.K_APPROVED) { it + installed(c) }
        Prefs.setApprovalMode(c, true)
        GuardService.refresh(c)
    }

    fun disable(c: Context) {
        Prefs.setApprovalMode(c, false)
        GuardService.refresh(c)
        // Drops only approval's claims; apps paused by you or screen time stay paused.
        Holds.set(c, Hold.SUSPEND_APPROVAL, Prefs.autoSuspended(c), false)
    }

    fun onInstalled(c: Context, pkg: String) {
        if (!Prefs.approvalMode(c) || pkg == c.packageName || pkg in Prefs.approved(c)) return
        // Apps the admin installs while the panel is unlocked are approved automatically.
        if (Session.valid()) approve(c, pkg) else pause(c, pkg)
    }

    fun approve(c: Context, pkg: String) {
        Prefs.updateSet(c, Prefs.K_APPROVED) { it + pkg }
        if (pkg in Prefs.autoSuspended(c)) Holds.set(c, Hold.SUSPEND_APPROVAL, pkg, false)
    }

    fun unapprove(c: Context, pkg: String) {
        Prefs.updateSet(c, Prefs.K_APPROVED) { it - pkg }
        if (Prefs.approvalMode(c)) pause(c, pkg)
    }

    private fun pause(c: Context, pkg: String) {
        if (!Mdm(c).isOwner) return
        if (Holds.set(c, Hold.SUSPEND_APPROVAL, pkg, true)) notifyPaused(c, pkg)
    }

    /** Pause any non-system app that isn't approved (e.g. installed while the service wasn't running). */
    fun sweep(c: Context) {
        if (!Prefs.approvalMode(c)) return
        val approved = Prefs.approved(c)
        val claimed = Prefs.autoSuspended(c)
        Apps.list(c)
            .filter { !Apps.isSystem(it) && it.packageName != c.packageName }
            .filter { it.packageName !in approved && it.packageName !in claimed }
            .forEach { pause(c, it.packageName) }
    }

    private fun notifyPaused(c: Context, pkg: String) =
        Notify.post(c, Channel.APPROVALS, pkg.hashCode(), "${Blocklist.label(c, pkg)} is paused", "New apps need administrator approval.")
}
