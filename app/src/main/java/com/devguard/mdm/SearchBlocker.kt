// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Search keyword blocker: a web search (or a typed address) that contains a blocked keyword is stopped in every
 * browser. Two layers:
 *  1. [SearchBlockService], an accessibility service, reads the address / search / title bar of every browser (and
 *     the address bar of in-app browsers) and leaves the page (Back, then Home if needed) when a keyword appears.
 *  2. Chrome's managed policy profile ([chromeRules]) blocks Google / Bing / DuckDuckGo / Yahoo / YouTube searches
 *     made of, or starting with, a keyword, even if the accessibility service were stopped.
 *
 * Device Owner control: DeviceGuard switches the service on itself (WRITE_SECURE_SETTINGS), GuardService switches
 * it back on if anyone turns it off, and while keywords exist DeviceGuard can't be force-stopped or cleared from
 * Settings (setUserControlDisabledPackages). Adding keywords is free; removing one needs the filter guard.
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

    /**
     * The first keyword found in [text], or null. [keywords] must already be normalized.
     * [editing]: the text is being typed right now, so its last word may be unfinished ("ass" on the way to
     * "assignment"); whole-word keywords then ignore that last word until a space, punctuation or submit ends it.
     */
    fun match(text: String, keywords: Collection<String>, editing: Boolean = false): String? {
        if (keywords.isEmpty() || text.isBlank()) return null
        val all = clean(text)
        val unfinished = editing && isWordChar(text.trimEnd().lastOrNull() ?: ' ') && text.last() == text.trimEnd().last()
        val done = if (unfinished) all.substringBeforeLast(' ', "") else all
        val tAll = " $all "
        val tDone = " $done "
        return keywords.firstOrNull { k ->
            if (k.endsWith("*")) tAll.contains(" " + k.dropLast(1)) else tDone.contains(" $k ")
        }
    }

    // ---- stored list ----
    @Volatile private var cache: Set<String>? = null

    fun keywords(c: Context): Set<String> = cache ?: Prefs.getSet(c, Prefs.K_SEARCH_KEYWORDS).also { cache = it }
    fun active(c: Context) = keywords(c).isNotEmpty()

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

    /** Danger zone → Release all: drop every keyword and switch the service off while still Device Owner. */
    fun clearAll(c: Context) {
        Prefs.setSet(c, Prefs.K_SEARCH_KEYWORDS, emptySet())
        cache = null
        runCatching { ensureService(c) }
        runCatching { applyUserControl(c) }
    }

    /** After the list changed here or by a profile import: refresh the service, Chrome's profile and GuardService. */
    fun changed(c: Context) {
        cache = null
        runCatching { ensureService(c) }
        runCatching { applyUserControl(c) }
        runCatching { ChromePolicy.apply(c) } // always: also removes the rules of keywords just deleted
        SearchBlockService.instance?.configure()
        GuardService.refresh(c)
    }

    /**
     * While keywords exist, DeviceGuard can't be force-stopped or have its data cleared from Settings (that would
     * stop the accessibility service until the app is opened again). Device Owner API, Android 11+.
     */
    fun applyUserControl(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val m = Mdm(c)
        if (!m.isOwner) return
        m.dpm.setUserControlDisabledPackages(m.admin, if (active(c)) listOf(c.packageName) else emptyList())
    }

    // ---- layer 2: Chrome managed profile ----
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

    // ---- making the service mandatory (Device Owner + WRITE_SECURE_SETTINGS) ----
    fun component(c: Context) = ComponentName(c, SearchBlockService::class.java)

    fun serviceEnabled(c: Context): Boolean {
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == component(c) }
    }

    /**
     * Switches the accessibility service on while there are keywords (and off when there are none), by writing the
     * secure settings directly. Returns true if it had to switch it back ON (someone turned it off).
     * Without the one-time ADB grant it can't: the Websites screen then shows how to turn it on by hand.
     */
    @Synchronized
    fun ensureService(c: Context): Boolean {
        if (!PrivateDns.canWrite(c) || !Mdm(c).isOwner) return false
        val cr = c.contentResolver
        val me = component(c)
        val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
        val others = list.filter { ComponentName.unflattenFromString(it) != me }
        val on = others.size != list.size
        val accessibilityOn = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        return if (active(c)) {
            if (on && accessibilityOn) return false
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } else {
            if (on) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            false
        }
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
    const val NOTIFY_ID = 4343
}

/**
 * Reads browsers' address / search / title bars (not the page content) and leaves a search that contains a blocked
 * keyword. Other apps: only an in-app browser's address bar and typing inside web content.
 * Window scans run on a background thread, at most one per [SCAN_GAP_MS], only for the window of the app that sent
 * the event, and never act on DeviceGuard itself, the keyboard or System UI.
 */
class SearchBlockService : AccessibilityService() {
    companion object {
        @Volatile var instance: SearchBlockService? = null
            private set

        private val BROWSER_HINTS = listOf("url", "search", "query", "omnibox", "address", "location", "title")
        private val INAPP_HINTS = listOf("url", "address", "omnibox")
        private const val MAX_NODES = 300
        private const val SCAN_GAP_MS = 400L
        private const val SYSTEM_UI = "com.android.systemui"
    }

    private val worker = HandlerThread("search-block").apply { start() }
    private val bg = Handler(worker.looper)
    @Volatile private var browsers: Set<String> = emptySet()
    @Volatile private var ignored: Set<String> = emptySet()
    @Volatile private var lastBlock = 0L
    @Volatile private var lastScan = 0L
    @Volatile private var scanPkg: String? = null

    override fun onServiceConnected() {
        instance = this
        configure()
    }

    /** Events from every app (in-app browsers, newly installed browsers); the cheap checks are in [onAccessibilityEvent]. */
    fun configure() {
        browsers = SearchBlocker.browserPackages(this)
        val imes = runCatching {
            getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        ignored = (imes + packageName + SYSTEM_UI).toSet()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 250
        info.packageNames = null
        serviceInfo = info
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored) return
        val kws = SearchBlocker.keywords(this)
        if (kws.isEmpty()) return
        if (SystemClock.elapsedRealtime() - lastBlock < 1500) return // just acted: let Back finish
        val browser = pkg in browsers
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Typing: in a browser anywhere, elsewhere only inside web content (an in-app browser page).
                if (!browser && !insideWebContent(e.source)) return
                val hit = e.text.firstNotNullOfOrNull { SearchBlocker.match(it.toString(), kws, editing = true) } ?: return
                block(hit, pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val types = e.contentChangeTypes
                val relevant = types == 0 || types and (AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0
                if (relevant) requestScan(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> requestScan(pkg)
        }
    }

    /** At most one scan per [SCAN_GAP_MS]; a later request in the gap runs once at its end (trailing scan). */
    private fun requestScan(pkg: String) {
        scanPkg = pkg
        val wait = SCAN_GAP_MS - (SystemClock.elapsedRealtime() - lastScan)
        bg.removeCallbacks(scanRunnable)
        bg.postDelayed(scanRunnable, wait.coerceAtLeast(0))
    }

    private val scanRunnable = Runnable {
        lastScan = SystemClock.elapsedRealtime()
        val pkg = scanPkg ?: return@Runnable
        val hit = runCatching { findHit(pkg) }.getOrNull() ?: return@Runnable
        block(hit, pkg)
    }

    /** The keyword in [pkg]'s focused window, or null. Never reads a window of another app. */
    private fun findHit(pkg: String): String? {
        val kws = SearchBlocker.keywords(this)
        if (kws.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        val rp = root.packageName?.toString() ?: return null
        if (rp != pkg || rp in ignored) return null
        val hints = if (rp in browsers) BROWSER_HINTS else INAPP_HINTS
        val browser = rp in browsers
        // Breadth-first so the toolbar is reached first; page content (WebView) is not entered.
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst(); seen++
            val id = n.viewIdResourceName?.substringAfter('/')?.lowercase().orEmpty()
            val field = hints.any { it in id } || (browser && n.isEditable)
            if (field) n.text?.toString()?.let { t ->
                SearchBlocker.match(t, kws, editing = n.isEditable && n.isFocused)?.let { return it }
            }
            if (isWebContent(n)) continue
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun isWebContent(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString().orEmpty()
        return cls == "android.webkit.WebView" || cls.startsWith("org.chromium.")
    }

    private fun insideWebContent(src: AccessibilityNodeInfo?): Boolean {
        var n = src
        var depth = 0
        while (n != null && depth++ < 30) {
            if (isWebContent(n)) return true
            n = n.parent
        }
        return false
    }

    private fun block(keyword: String, pkg: String) {
        lastBlock = SystemClock.elapsedRealtime()
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Still showing it (nothing to go back to, or Back only closed the keyboard)? Back again, then Home.
        bg.postDelayed({ if (runCatching { findHit(pkg) }.getOrNull() != null) performGlobalAction(GLOBAL_ACTION_BACK) }, 500)
        bg.postDelayed({ if (runCatching { findHit(pkg) }.getOrNull() != null) performGlobalAction(GLOBAL_ACTION_HOME) }, 1100)
        Notify.post(
            this, Channel.BLOCKED, SearchBlocker.NOTIFY_ID, "Search blocked",
            "\"${keyword.removeSuffix("*")}\" is a blocked keyword (${Apps.label(this, pkg)}).",
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.quitSafely()
        super.onDestroy()
    }
}
