// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.content.pm.ApplicationInfo

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
