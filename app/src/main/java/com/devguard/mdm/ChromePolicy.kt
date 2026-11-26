// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Website rules enforced through browsers' managed (enterprise) policies, written as Device Owner. Like a PC policy,
 * but on Android a browser only obeys it if it reads Android managed configurations: the profile goes to Chrome and to
 * every installed browser that declares the Chromium policies ([policyBrowsers]); each gets only the keys it declares.
 * Browsers that ignore policies get no site or keyword rules; "Allow only Chrome" hides them.
 */
object ChromePolicy {
    const val CHROME = "com.android.chrome"
    /** Chrome ignores URLBlocklist entries beyond 1000. */
    const val MAX_BLOCKLIST = 1000

    fun installed(c: Context) = runCatching { c.packageManager.getApplicationInfo(CHROME, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.isSuccess

    /**
     * Installed browsers that accept the Chromium policy profile (they declare "URLBlocklist" as a managed
     * configuration), with the policy keys each declares. Chrome is always included.
     */
    fun policyBrowsers(c: Context): Map<String, Set<String>> {
        val rm = c.getSystemService(android.content.RestrictionsManager::class.java)
        val out = LinkedHashMap<String, Set<String>>()
        for (p in listOf(CHROME) + SearchBlocker.browserPackages(c).sorted()) {
            val keys = runCatching { rm.getManifestRestrictions(p) }.getOrNull()?.map { it.key }?.toSet().orEmpty()
            if (p == CHROME && installed(c)) out[p] = keys.ifEmpty { ALL_KEYS }
            else if ("URLBlocklist" in keys) out[p] = keys
        }
        return out
    }

    private val ALL_KEYS = setOf("URLBlocklist", "SafeSitesFilterBehavior", "ForceGoogleSafeSearch", "ForceYouTubeRestrict",
        "IncognitoModeAvailability", "DnsOverHttpsMode")
    private const val K_TARGETS = "policyBrowsers" // browsers the profile was last written to (to clear dropped ones)

    /** Browsers that got the profile on the last [apply] (for the Websites screen). */
    fun lastTargets(c: Context): Set<String> = Prefs.getSet(c, K_TARGETS)

    /** Pushes the current settings to every policy browser. Returns null on success or an error message. */
    fun apply(c: Context): String? {
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        val b = Bundle()
        val chromeOn = Prefs.getFlag(c, Prefs.CHROME_ON)
        // Your list + sites blocked by an active screen-time schedule (Chrome rules on), plus searches starting with a
        // blocked keyword (always, while there are keywords: the keyword blocker's Chrome layer).
        // Chrome accepts at most 1000 entries: your sites first, then keyword rules while they fit.
        val sites = if (chromeOn) (Prefs.domains(c) + Prefs.stSites(c)).sorted() else emptyList()
        val keywordRules = SearchBlocker.chromeRules(c).filterNot { it in sites }
        val blocklist = sites + keywordRules.take((MAX_BLOCKLIST - sites.size).coerceAtLeast(0))
        if (blocklist.isNotEmpty()) b.putStringArray("URLBlocklist", blocklist.toTypedArray())
        if (chromeOn) {
            if (Prefs.getFlag(c, Prefs.CHROME_SAFESITES)) b.putInt("SafeSitesFilterBehavior", 1)
            if (Prefs.getFlag(c, Prefs.CHROME_SAFESEARCH)) b.putBoolean("ForceGoogleSafeSearch", true)
            if (Prefs.getFlag(c, Prefs.CHROME_YOUTUBE)) b.putInt("ForceYouTubeRestrict", 1)
            if (Prefs.getFlag(c, Prefs.CHROME_NO_INCOGNITO)) b.putInt("IncognitoModeAvailability", 1)
            b.putString("DnsOverHttpsMode", "off") // keep Chrome on system DNS so Private DNS filtering applies
        }
        val targets = policyBrowsers(c)
        for ((pkg, keys) in targets) {
            val own = Bundle(b).apply { keySet().toList().forEach { if (it !in keys) remove(it) } } // only what it declares
            runCatching { m.dpm.setApplicationRestrictions(m.admin, pkg, own) }
        }
        // A browser that no longer qualifies (or was uninstalled) loses the old profile.
        (lastTargets(c) - targets.keys).forEach { runCatching { m.dpm.setApplicationRestrictions(m.admin, it, Bundle()) } }
        Prefs.setSet(c, K_TARGETS, targets.keys)
        return null
    }

    fun clear(c: Context) {
        val m = Mdm(c)
        if (!m.isOwner) return
        (lastTargets(c) + CHROME).forEach { runCatching { m.dpm.setApplicationRestrictions(m.admin, it, Bundle()) } }
        Prefs.setSet(c, K_TARGETS, emptySet())
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
