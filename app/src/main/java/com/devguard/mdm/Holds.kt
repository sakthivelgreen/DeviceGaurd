// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context

/**
 * Who wants an app paused (suspended) or hidden. Fixes B-02 / A-01 in ISSUES.md.
 *
 * Android has ONE suspended flag and ONE hidden flag per app, but several features use them. Each feature
 * ("source") keeps its own list here; an app stays paused/hidden while ANY source wants it, and is released
 * only when NO source wants it any more. So a schedule ending can't un-pause an app you paused by hand.
 *
 * Every change goes through [set]; never call Mdm.setSuspended / setHidden directly for policy.
 * (Force stop's brief hide → unhide is the only exception: it skips apps that are already hidden.)
 */
enum class Hold(val key: String, val hide: Boolean, val label: String) {
    SUSPEND_MANUAL("suspManual", false, "you (Apps)"),
    SUSPEND_APPROVAL("autoSuspended", false, "Approved apps only"),
    SUSPEND_SCREEN_TIME("stSuspended", false, "Screen time"),
    HIDE_MANUAL("hideManual", true, "you (Apps / Bloatware)"),
    HIDE_CHROME_ONLY("hiddenBrowsers", true, "Allow only Chrome"),
    HIDE_BLOCKLIST("hideBlocked", true, "Blocked apps"),
}

object Holds {
    const val MIGRATED = "holdsV1"

    fun get(c: Context, h: Hold): Set<String> = Prefs.getSet(c, h.key)

    /** Sources that currently want [pkg] paused ([hide] = false) or hidden ([hide] = true). */
    fun owners(c: Context, pkg: String, hide: Boolean): List<Hold> =
        Hold.values().filter { it.hide == hide && pkg in get(c, it) }

    /**
     * Adds ([on] = true) or removes [pkgs] from [h]'s list, then makes Android match the combined wish of all
     * sources. Returns the packages Android refused to pause/hide (they're dropped from [h]'s list).
     */
    @Synchronized
    fun set(c: Context, h: Hold, pkgs: Collection<String>, on: Boolean): List<String> {
        if (pkgs.isEmpty()) return emptyList()
        if (on && Mdm.releasing) return pkgs.toList() // Release all is undoing every hold: don't add new ones
        val before = get(c, h)
        Prefs.setSet(c, h.key, if (on) before + pkgs else before - pkgs.toSet())
        val failed = apply(c, h.hide, pkgs)
        if (on && failed.isNotEmpty()) Prefs.setSet(c, h.key, get(c, h) - failed.toSet())
        return failed
    }

    fun set(c: Context, h: Hold, pkg: String, on: Boolean): Boolean = set(c, h, listOf(pkg), on).isEmpty()

    /** Makes Android's flag for each of [pkgs] match "any source wants it". Returns packages that couldn't be turned on. */
    private fun apply(c: Context, hide: Boolean, pkgs: Collection<String>): List<String> {
        val m = Mdm(c)
        if (!m.isOwner) return pkgs.toList()
        val want = pkgs.filter { p -> owners(c, p, hide).isNotEmpty() }
        val release = pkgs.filter { it !in want }
        val failed = mutableListOf<String>()
        if (hide) {
            want.forEach { p -> if (!runCatching { m.setHidden(p, true) }.getOrDefault(false)) failed += p }
            release.forEach { p -> runCatching { m.setHidden(p, false) } }
        } else {
            if (want.isNotEmpty()) failed += runCatching { m.setSuspended(want, true) }.getOrDefault(want)
            if (release.isNotEmpty()) runCatching { m.setSuspended(release, false) }
        }
        return failed
    }

    /** Re-applies every source's list (after a profile import). */
    fun reapplyAll(c: Context) {
        for (hide in listOf(false, true)) {
            val all = Hold.values().filter { it.hide == hide }.flatMap { get(c, it) }.toSet()
            apply(c, hide, all)
        }
    }

    /** Forgets every source's list (Danger zone → release all; Android flags are cleared there separately). */
    fun clearAll(c: Context) = Hold.values().forEach { Prefs.setSet(c, it.key, emptySet()) }

    /** Readable "paused by Screen time, you (Apps)". */
    fun describe(c: Context, pkg: String, hide: Boolean): String = owners(c, pkg, hide).joinToString(", ") { it.label }

    /**
     * One-time migration from the old version, where manual pauses/hides weren't recorded: every app that's
     * paused or hidden now but not claimed by a feature is recorded as a manual pause/hide (or Blocked apps).
     */
    fun migrate(c: Context) {
        if (Prefs.getFlag(c, MIGRATED)) return
        val m = Mdm(c)
        if (!m.isOwner) return
        val pkgs = Apps.packages(c)
        val blocked = Prefs.blockedPkgs(c)
        val susp = mutableSetOf<String>()
        val hideManual = mutableSetOf<String>()
        val hideBlocked = mutableSetOf<String>()
        for (p in pkgs) {
            if (p == c.packageName) continue
            if (m.isSuspended(p) && owners(c, p, hide = false).isEmpty()) susp += p
            if (m.isHidden(p) && owners(c, p, hide = true).isEmpty()) {
                if (p in blocked) hideBlocked += p else hideManual += p
            }
        }
        Prefs.setSet(c, Hold.SUSPEND_MANUAL.key, get(c, Hold.SUSPEND_MANUAL) + susp)
        Prefs.setSet(c, Hold.HIDE_MANUAL.key, get(c, Hold.HIDE_MANUAL) + hideManual)
        Prefs.setSet(c, Hold.HIDE_BLOCKLIST.key, get(c, Hold.HIDE_BLOCKLIST) + hideBlocked)
        Prefs.setFlag(c, MIGRATED, true)
    }
}
