// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    /** Returns true if blocking is running now; false if waiting for VPN consent. */
    fun enable(a: Activity): Boolean {
        // The manual VPN filter replaces the Private DNS filter (they can't run together).
        if (Prefs.privateDnsHost(a) != null) runCatching { PrivateDns.apply(a, null) }
        Prefs.setWebBlock(a, true)
        DnsVpnService.reload(a)
        val m = Mdm(a)
        if (m.isOwner) {
            // Always-on: the system keeps the VPN running and the user cannot switch it off.
            runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, a.packageName, false) }
                .onFailure { a.toast("Always-on VPN failed: ${it.message}") }
            // Stop user-chosen Private DNS (DNS-over-TLS) from bypassing the filter.
            runCatching { m.dpm.setGlobalPrivateDnsModeOpportunistic(m.admin) }
        }
        val consent = VpnService.prepare(a)
        if (consent != null) {
            a.startActivityForResult(consent, VPN_CONSENT_REQUEST)
            return false
        }
        a.startService(Intent(a, DnsVpnService::class.java))
        return true
    }

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
    const val ADB_GRANT = "adb shell pm grant com.devguard.mdm android.permission.WRITE_SECURE_SETTINGS"

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

    /** Checks the host answers DNS-over-TLS on port 853 (blocking). */
    private fun reachable(host: String): Boolean = runCatching {
        (javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket).use { s ->
            s.connect(java.net.InetSocketAddress(host, 853), 4000)
            s.soTimeout = 4000
            s.startHandshake()
        }
        true
    }.getOrDefault(false)

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
            // Backup mode: the VPN filter handles DNS; Private DNS must stay on "automatic" so it doesn't bypass it.
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

    /** ADB commands that set Private DNS phone-wide (works even when the app is blocked by an unaffiliated user). */
    fun adbCommand(host: String) =
        "adb shell settings put global private_dns_mode hostname; adb shell settings put global private_dns_specifier $host"

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
            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                return "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
            null -> {
                if (!canWrite(c)) return UNAFFILIATED_HELP
                if (!reachable(host)) return "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
                if (!write(c, host)) return "Couldn't write the Private DNS setting"
            }
            else -> return "Couldn't set Private DNS"
        }
        Prefs.setPrivateDnsHost(c, host)
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
 *  - Blocked → [enterFallback]: the app's VPN filter starts and sends lookups to the SAME filter over HTTPS (DoH, port 443),
 *    Private DNS is set to "automatic" so it doesn't bypass the VPN. Office domains go to the office DNS (split DNS).
 *  - Networks that blocked 853 are remembered → next time the backup starts instantly (then re-verified in the background).
 *  - 853 works again → [exitFallback]: Private DNS filter restored, VPN stopped.
 */
object SmartDns {
    private const val NOTIFY_ID = 4242
    @Volatile private var lastKey: String? = null
    @Volatile private var lastAt = 0L

    fun autoOn(c: Context) = !Prefs.getFlag(c, Prefs.SMART_DNS_OFF)
    fun enabled(c: Context) = autoOn(c) && Prefs.privateDnsHost(c) != null && PrivateDns.canWrite(c) && Mdm(c).isOwner
    fun fallbackActive(c: Context) = Prefs.getFlag(c, Prefs.DNS_FALLBACK)

    /** Known IPs for the filter servers, so the test doesn't need DNS (which is what's broken). */
    fun bootstrap(host: String): List<String> = when {
        host == "family.cloudflare-dns.com" -> listOf("1.1.1.3", "1.0.0.3")
        host == "security.cloudflare-dns.com" -> listOf("1.1.1.2", "1.0.0.2")
        host == "family.adguard-dns.com" -> listOf("94.140.14.15", "94.140.15.16")
        host == "dns.adguard-dns.com" -> listOf("94.140.14.14", "94.140.15.15")
        host.endsWith(".dns.nextdns.io") -> listOf("45.90.28.0", "45.90.30.0")
        else -> emptyList()
    }

    /** Same filter over HTTPS (port 443). */
    fun dohUrl(host: String): String = when {
        host.endsWith(".dns.nextdns.io") -> "https://dns.nextdns.io/" + host.removeSuffix(".dns.nextdns.io")
        else -> "https://$host/dns-query"
    }

    /** Plain-DNS backup for the same filter (only providers whose filtering works on port 53 without a profile). */
    fun plainBackup(host: String): List<String> = if (host.endsWith(".dns.nextdns.io")) emptyList() else bootstrap(host)

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
    private fun dotReachable(host: String, n: android.net.Network): Boolean {
        val ips = bootstrap(host).mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            .ifEmpty { runCatching { n.getAllByName(host).toList() }.getOrDefault(emptyList()) }
        for (ip in ips.take(2)) {
            val ok = runCatching {
                val raw = n.socketFactory.createSocket()
                raw.connect(java.net.InetSocketAddress(ip, 853), 3000)
                val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(raw, host, 853, true) as javax.net.ssl.SSLSocket
                ssl.soTimeout = 3000
                ssl.startHandshake()
                ssl.close()
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** Runs on a background thread for every network change. */
    @Synchronized
    fun check(c: Context, n: android.net.Network) {
        if (!enabled(c)) { if (fallbackActive(c)) exitFallback(c); return }
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(n) ?: return
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return
        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return // wait for Wi-Fi login
        val key = networkKey(cm, n) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastAt < 60_000) return
        lastKey = key; lastAt = now
        val host = Prefs.privateDnsHost(c) ?: return

        // Known blocking network: switch instantly, then re-verify below.
        if (key in Prefs.dnsBlockedNets(c) && !fallbackActive(c)) enterFallback(c)

        if (dotReachable(host, n)) {
            Prefs.setDnsBlockedNets(c, Prefs.dnsBlockedNets(c) - key)
            if (fallbackActive(c)) exitFallback(c)
        } else {
            Prefs.setDnsBlockedNets(c, Prefs.dnsBlockedNets(c) + key)
            if (!fallbackActive(c)) enterFallback(c)
        }
    }

    fun enterFallback(c: Context) {
        val m = Mdm(c)
        Prefs.setFlag(c, Prefs.DNS_FALLBACK, true)
        DnsVpnService.reload(c)
        // VPN first (so there is no unfiltered gap), then Private DNS → automatic.
        runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, c.packageName, false) }
        runCatching { c.startService(Intent(c, DnsVpnService::class.java)) }
        PrivateDns.write(c, null)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("smartdns", "Network filter", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(c, "smartdns")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Backup web filter on")
            .setContentText("This network blocks secure DNS, so filtering continues through DeviceGuard.")
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    fun exitFallback(c: Context) {
        val m = Mdm(c)
        Prefs.setFlag(c, Prefs.DNS_FALLBACK, false)
        Prefs.privateDnsHost(c)?.let { PrivateDns.write(c, it) } // filter back on Private DNS first
        DnsVpnService.reload(c)
        if (!Prefs.webBlock(c)) {
            runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, null, false) }
            runCatching { c.startService(Intent(c, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
        }
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID) }
    }

    /** Leave backup mode without touching Private DNS (caller sets it) and force a fresh check. */
    fun reset(c: Context) {
        lastKey = null
        if (!fallbackActive(c)) return
        val m = Mdm(c)
        Prefs.setFlag(c, Prefs.DNS_FALLBACK, false)
        DnsVpnService.reload(c)
        if (!Prefs.webBlock(c)) {
            runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, null, false) }
            runCatching { c.startService(Intent(c, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
        }
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID) }
    }

    /** Turn the automatic backup on/off from the UI. */
    fun setAuto(c: Context, on: Boolean) {
        Prefs.setFlag(c, Prefs.SMART_DNS_OFF, !on)
        lastKey = null
        if (!on && fallbackActive(c)) exitFallback(c)
        GuardService.refresh(c)
    }

    fun forgetNetworks(c: Context) { Prefs.setDnsBlockedNets(c, emptySet()); lastKey = null }
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
            b.putStringArray("URLBlocklist", Prefs.domains(c).sorted().toTypedArray())
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
        val m = Mdm(c)
        Prefs.setFlag(c, Prefs.CHROME_ONLY, on)
        var n = 0
        if (on) {
            val hidden = Prefs.hiddenBrowsers(c).toMutableSet()
            otherBrowsers(c).forEach { p -> if (runCatching { m.setHidden(p, true) }.getOrDefault(false)) { hidden += p; n++ } }
            Prefs.setHiddenBrowsers(c, hidden)
        } else {
            Prefs.hiddenBrowsers(c).forEach { p -> if (runCatching { m.setHidden(p, false) }.getOrDefault(false)) n++ }
            Prefs.setHiddenBrowsers(c, emptySet())
        }
        GuardService.refresh(c)
        return n
    }

    /** Called by GuardService for every new install. Returns true if it was a browser and got hidden. */
    fun onInstalled(c: Context, pkg: String): Boolean {
        if (!Prefs.getFlag(c, Prefs.CHROME_ONLY) || !isBrowser(c, pkg)) return false
        val m = Mdm(c)
        if (!runCatching { m.setHidden(pkg, true) }.getOrDefault(false)) return false
        Prefs.setHiddenBrowsers(c, Prefs.hiddenBrowsers(c) + pkg)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("blocked", "Blocked apps", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(c, "blocked")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("${Blocklist.label(c, pkg)} is not allowed")
            .setContentText("Only Chrome can be used as a browser on this phone.")
            .build()
        runCatching { nm.notify(("brw$pkg").hashCode(), n) }
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
    private const val CHANNEL = "blocked"

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

    fun label(c: Context, pkg: String): String = PRESETS[pkg] ?: runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString()
    }.getOrDefault(pkg)

    fun add(c: Context, pkgs: Collection<String>) {
        Prefs.setBlockedPkgs(c, Prefs.blockedPkgs(c) + pkgs)
        GuardService.refresh(c)
        pkgs.forEach { if (isInstalled(c, it)) remove(c, it, notify = false) }
    }

    fun unblock(c: Context, pkg: String) {
        Prefs.setBlockedPkgs(c, Prefs.blockedPkgs(c) - pkg)
        val m = Mdm(c)
        // If it was a system app we hid, show it again.
        if (m.isOwner && m.isHidden(pkg)) runCatching { m.setHidden(pkg, false) }
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
            runCatching { m.setHidden(pkg, true) }
        } else {
            runCatching { if (m.isUninstallBlocked(pkg)) m.setUninstallBlocked(pkg, false) }
            val pi = android.app.PendingIntent.getBroadcast(
                c, pkg.hashCode(),
                Intent("com.devguard.mdm.UNINSTALL_RESULT").setPackage(c.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { c.packageManager.packageInstaller.uninstall(pkg, pi.intentSender) }
        }
        if (notify) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Blocked apps", NotificationManager.IMPORTANCE_HIGH))
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("$name is not allowed")
                .setContentText("This app is blocked by your administrator and was removed.")
                .build()
            runCatching { nm.notify(("blk$pkg").hashCode(), n) }
        }
    }
}

/** Approved-apps mode: newly installed apps stay paused until the admin approves them. */
object Approval {
    private const val CHANNEL = "approvals"

    private fun installed(c: Context) =
        c.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES).map { it.packageName }.toSet()

    fun enable(c: Context) {
        // Everything already on the phone counts as approved.
        Prefs.setApproved(c, Prefs.approved(c) + installed(c))
        Prefs.setApprovalMode(c, true)
        GuardService.refresh(c)
    }

    fun disable(c: Context) {
        Prefs.setApprovalMode(c, false)
        GuardService.refresh(c)
        val paused = Prefs.autoSuspended(c)
        if (paused.isNotEmpty() && Mdm(c).isOwner) runCatching { Mdm(c).setSuspended(paused, false) }
        Prefs.setAutoSuspended(c, emptySet())
    }

    fun onInstalled(c: Context, pkg: String) {
        if (!Prefs.approvalMode(c) || pkg == c.packageName || pkg in Prefs.approved(c)) return
        // Apps the admin installs while the panel is unlocked are approved automatically.
        if (Session.valid()) approve(c, pkg) else pause(c, pkg)
    }

    fun approve(c: Context, pkg: String) {
        Prefs.setApproved(c, Prefs.approved(c) + pkg)
        if (pkg in Prefs.autoSuspended(c)) {
            runCatching { Mdm(c).setSuspended(listOf(pkg), false) }
            Prefs.setAutoSuspended(c, Prefs.autoSuspended(c) - pkg)
        }
    }

    fun unapprove(c: Context, pkg: String) {
        Prefs.setApproved(c, Prefs.approved(c) - pkg)
        if (Prefs.approvalMode(c)) pause(c, pkg)
    }

    private fun pause(c: Context, pkg: String) {
        val m = Mdm(c)
        if (!m.isOwner) return
        val failed = runCatching { m.setSuspended(listOf(pkg), true) }.getOrDefault(listOf(pkg))
        if (failed.isEmpty()) {
            Prefs.setAutoSuspended(c, Prefs.autoSuspended(c) + pkg)
            notifyPaused(c, pkg)
        }
    }

    /** Pause any non-system app that isn't approved (e.g. installed while the service wasn't running). */
    fun sweep(c: Context) {
        if (!Prefs.approvalMode(c)) return
        val approved = Prefs.approved(c)
        val m = Mdm(c)
        c.packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != c.packageName }
            .filter { it.packageName !in approved && !m.isSuspended(it.packageName) }
            .forEach { pause(c, it.packageName) }
    }

    private fun notifyPaused(c: Context, pkg: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App approvals", NotificationManager.IMPORTANCE_DEFAULT))
        val label = runCatching {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("$label is paused")
            .setContentText("New apps need administrator approval.")
            .build()
        runCatching { nm.notify(pkg.hashCode(), n) }
    }
}
