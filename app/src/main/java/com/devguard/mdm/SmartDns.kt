// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.content.Intent

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
