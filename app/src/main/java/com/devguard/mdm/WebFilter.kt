// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService

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

/** Called whenever the blocked-site list changes: update every enforcement layer. */
fun onDomainsChanged(c: Context) {
    DnsVpnService.reload(c)
    if (Prefs.getFlag(c, Prefs.CHROME_ON)) runCatching { ChromePolicy.apply(c) }
}
