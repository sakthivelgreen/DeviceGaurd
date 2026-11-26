// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Search keyword blocker, through Chrome's managed policy profile only ([chromeRules]): Google / Bing / DuckDuckGo /
 * Yahoo / YouTube searches made of, or starting with, a keyword are refused in Chrome and every browser that accepts
 * the Chromium policies. Adding keywords is free; removing one needs the filter guard.
 *
 * There is no accessibility service any more: banking and payment apps refuse to open while a third-party
 * accessibility service is on. [removeService] switches off the one older versions turned on.
 */
object SearchBlocker {
    /** Matching: whole words ("ass" doesn't match "class"); a trailing * matches word starts ("porn*" → "pornography"). */
    fun normalizeKeyword(raw: String): String? {
        val star = raw.trim().endsWith("*")
        val base = clean(raw.trim().removeSuffix("*"))
        if (base.length < 2 || base.length > 60) return null
        return if (star) "$base*" else base
    }

    private fun isWordChar(ch: Char) = Character.isLetterOrDigit(ch) || when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Lowercase, URL-decoded, every non-letter/digit (keeping combining marks, for Tamil etc.) turned into one space. */
    fun clean(text: String): String {
        val decoded = if ('%' in text || '+' in text) runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text) else text
        val sb = StringBuilder(decoded.length)
        var space = true
        for (ch in decoded.lowercase()) {
            if (isWordChar(ch)) { sb.append(ch); space = false } else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trim()
    }

    // ---- stored list ----
    @Volatile private var cache: Set<String>? = null

    fun keywords(c: Context): Set<String> = cache ?: Prefs.getSet(c, Prefs.K_SEARCH_KEYWORDS).also { cache = it }

    // ---- your blocked sites + active screen-time sites (they keep the force-stop protection on) ----
    @Volatile private var siteCache: Set<String>? = null

    /** Your blocked sites plus sites of an active screen-time schedule, lowercase. */
    fun sites(c: Context): Set<String> =
        siteCache ?: (Prefs.domains(c) + Prefs.stSites(c)).map { it.lowercase() }.toSet().also { siteCache = it }

    /** On while there are keywords or blocked sites (force-stop protection). */
    fun active(c: Context) = !Mdm.releasing && (keywords(c).isNotEmpty() || sites(c).isNotEmpty())

    /** The site list changed (Websites, screen time, profile import): refresh the force-stop protection. */
    fun sitesChanged(c: Context) {
        siteCache = null
        runCatching { applyUserControl(c) }
        GuardService.refresh(c)
    }

    /** Adds keywords (comma, semicolon or line separated). Returns how many were new. */
    fun add(c: Context, input: String): Int {
        val add = input.split(',', '\n', ';').mapNotNull { normalizeKeyword(it) }.toSet()
        var n = 0
        Prefs.updateSet(c, Prefs.K_SEARCH_KEYWORDS) { cur -> n = (add - cur).size; cur + add }
        changed(c)
        return n
    }

    /** Removal: call only after the filter guard (WebActivity). */
    fun remove(c: Context, keyword: String) {
        Prefs.updateSet(c, Prefs.K_SEARCH_KEYWORDS) { it - keyword }
        changed(c)
    }

    /**
     * Danger zone → Release all: switch the force-stop protection off while still Device Owner.
     * Keywords and sites stay saved (Mdm.releasing makes [active] false meanwhile).
     */
    fun clearAll(c: Context) {
        cache = null
        siteCache = null
        runCatching { applyUserControl(c) }
    }

    /** After the list changed here or by a profile import: refresh Chrome's profile, the protection and GuardService. */
    fun changed(c: Context) {
        cache = null
        siteCache = null
        runCatching { applyUserControl(c) }
        runCatching { ChromePolicy.apply(c) } // always: also removes the rules of keywords just deleted
        GuardService.refresh(c)
    }

    /**
     * While keywords or blocked sites exist, DeviceGuard can't be force-stopped or have its data cleared from Settings
     * (that would stop GuardService until the app is opened again). Device Owner API, Android 11+.
     */
    fun applyUserControl(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val m = Mdm(c)
        if (!m.isOwner) return
        m.dpm.setUserControlDisabledPackages(m.admin, if (active(c)) listOf(c.packageName) else emptyList())
    }

    // ---- Chrome managed profile ----
    private val SEARCH_URLS = listOf(
        "google.com/search?q=", "google.co.in/search?q=", "bing.com/search?q=", "duckduckgo.com/?q=",
        "search.yahoo.com/search?p=", "youtube.com/results?search_query=",
    )

    /**
     * URLBlocklist entries for searches made of, or starting with, a keyword (Chrome policies can't match a word in
     * the middle). Whole words: the exact query and "keyword+…" (word boundary); `*` keywords: any query starting
     * with it. Chrome compares queries case-sensitively, so lower case, Title Case and UPPER case are listed.
     */
    fun chromeRules(c: Context): List<String> = keywords(c).sorted().flatMap { k ->
        val star = k.endsWith("*")
        val base = k.removeSuffix("*")
        val cases = linkedSetOf(base, base.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }, base.uppercase())
        cases.flatMap { v ->
            val q = URLEncoder.encode(v, "UTF-8") // spaces → '+', non-ASCII → %XX, as browsers send it
            SEARCH_URLS.flatMap { u -> if (star) listOf("$u$q*") else listOf("$u$q", "$u$q+*") }
        }
    }

    /**
     * Turns off the accessibility service older versions switched on (it stopped banking and payment apps from
     * opening). Removes it from the enabled list and, when no other service is left, switches accessibility off.
     * Needs the one-time WRITE_SECURE_SETTINGS grant; without it Android drops the entry once the service is gone.
     */
    fun removeService(c: Context) {
        val cr = c.contentResolver
        val me = ComponentName(c.packageName, "${c.packageName}.SearchBlockService")
        val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
        val others = list.filter { ComponentName.unflattenFromString(it) != me }
        if (others.size == list.size) return // not on
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
        if (others.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    /** Every app that can open web links (all browsers, including disabled ones) plus the Google app. */
    fun browserPackages(c: Context): Set<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = android.content.pm.PackageManager.MATCH_ALL or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or
            android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        val found = runCatching { c.packageManager.queryIntentActivities(i, flags).map { it.activityInfo.packageName } }
            .getOrDefault(emptyList())
        return (found + ChromePolicy.CHROME + GOOGLE_APP).filter { it != c.packageName }.toSet()
    }

    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
}

