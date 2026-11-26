// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context

/**
 * Your blocked-site list: input cleanup, presets, and "is any DNS filter on?".
 * R-03 (2026-11-25): the VPN filter was removed; the Private DNS family filter covers every app, Chrome rules block your list in Chrome.
 */
object WebFilter {
    /** True if a DNS-level filter (Private DNS, or its Automatic fallback on a blocking network) is set. */
    fun anyFilterActive(c: Context) =
        SmartDns.fallbackActive(c) || Prefs.privateDnsHost(c) != null || PrivateDns.currentHost(c) != null

    /**
     * Clears what the removed VPN filter may have left (R-03): DeviceGuard as the always-on VPN (with lockdown on,
     * that would cut the internet now that the VPN service is gone) and its two old settings. Cheap; runs at boot,
     * after updates and when the dashboard opens.
     */
    fun removeLegacyVpn(c: Context) {
        val m = Mdm(c)
        if (m.isOwner && runCatching { m.dpm.getAlwaysOnVpnPackage(m.admin) }.getOrNull() == c.packageName)
            runCatching { m.dpm.setAlwaysOnVpnPackage(m.admin, null, false) }
        Prefs.removeLegacyVpnKeys(c)
    }

    /**
     * NextDNS support was removed (2026-11-26): delete its saved API key and profile ID, and move a phone still on a
     * NextDNS host to the family filter it used last (AdGuard Family if none). A direct settings write: no network.
     */
    fun removeNextDns(c: Context) {
        if (Secrets.has(c, Prefs.NEXTDNS_KEY)) Secrets.put(c, Prefs.NEXTDNS_KEY, null)
        if (Prefs.getStr(c, Prefs.NEXTDNS_ID) != null) Prefs.setStr(c, Prefs.NEXTDNS_ID, null)
        val host = Prefs.privateDnsHost(c) ?: return
        if (!host.endsWith(".nextdns.io")) return
        val family = Prefs.getStr(c, PrivateDns.LAST_FAMILY)?.takeIf { f -> PrivateDns.PROVIDERS.any { it.host == f } } ?: "family.adguard-dns.com"
        Prefs.setPrivateDnsHost(c, family)
        if (!SmartDns.fallbackActive(c)) PrivateDns.write(c, family)
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

/** Called whenever the blocked-site list changes: update every enforcement layer. */
fun onDomainsChanged(c: Context) {
    if (Prefs.getFlag(c, Prefs.CHROME_ON)) runCatching { ChromePolicy.apply(c) }
    SearchBlocker.sitesChanged(c) // force-stop protection follows the site list
}
