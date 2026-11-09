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

    val PROVIDERS = listOf(
        Provider("Off", null, "Phone default, no filtering"),
        Provider("Cloudflare Family", "family.cloudflare-dns.com", "Blocks adult content and malware"),
        Provider("Cloudflare Security", "security.cloudflare-dns.com", "Blocks malware only"),
        Provider("AdGuard Family", "family.adguard-dns.com", "Blocks adult content, ads and trackers; forces SafeSearch"),
    )

    /** Current forced host, or null if not set. */
    fun currentHost(c: Context): String? {
        val m = Mdm(c)
        if (!m.isOwner) return null
        return runCatching {
            if (m.dpm.getGlobalPrivateDnsMode(m.admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)
                m.dpm.getGlobalPrivateDnsHost(m.admin) else null
        }.getOrNull()
    }

    /** Blocking (does a network check): call off the main thread. Returns a message for the user. */
    fun apply(c: Context, host: String?): String {
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        if (host == null) {
            m.dpm.setGlobalPrivateDnsModeOpportunistic(m.admin)
            runCatching { m.setRestriction(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) }
            return "Private DNS filter turned off"
        }
        if (Prefs.webBlock(c)) WebFilter.disable(c) // the VPN filter can't work alongside a forced Private DNS host
        return when (m.dpm.setGlobalPrivateDnsModeSpecifiedHost(m.admin, host)) {
            DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> {
                runCatching { m.setRestriction(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true) }
                "Filter active: $host (Private DNS setting locked)"
            }
            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
            else -> "Couldn't set Private DNS"
        }
    }
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

    /** Installed browsers other than Chrome (apps that open web links). */
    fun otherBrowsers(c: Context): List<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        return c.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != CHROME && it != c.packageName }
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
