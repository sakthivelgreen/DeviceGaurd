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

    /**
     * [text] with the blocked keywords taken out and the rest kept ("best casino hotels" → "best hotels"), using the
     * same rules as [match] (whole words, `*` = word start, an unfinished last word kept while [editing]).
     * Null if no keyword could be found in the raw text (e.g. %-encoded in a URL): then the box is cleared instead.
     */
    fun removeKeywords(text: String, keywords: Collection<String>, editing: Boolean = false): String? {
        data class Tok(val start: Int, val end: Int, val word: String)
        val toks = ArrayList<Tok>()
        var i = 0
        while (i < text.length) {
            if (isWordChar(text[i])) {
                val st = i
                while (i < text.length && isWordChar(text[i])) i++
                toks += Tok(st, i, text.substring(st, i).lowercase())
            } else i++
        }
        val unfinished = editing && toks.isNotEmpty() && toks.last().end == text.length
        val cut = BooleanArray(toks.size)
        for (k in keywords) {
            val star = k.endsWith("*")
            val words = k.removeSuffix("*").split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            for (a in 0..toks.size - words.size) {
                val lastIdx = a + words.size - 1
                if (!star && unfinished && lastIdx == toks.size - 1) continue // still being typed
                val ok = words.indices.all { j ->
                    val w = toks[a + j].word
                    if (star && j == words.size - 1) w.startsWith(words[j]) else w == words[j]
                }
                if (ok) for (j in a..lastIdx) cut[j] = true
            }
        }
        if (cut.none { it }) return null
        val sb = StringBuilder()
        var pos = 0
        toks.forEachIndexed { idx, t ->
            if (cut[idx]) { sb.append(text, pos, t.start); pos = t.end }
        }
        sb.append(text, pos, text.length)
        return sb.toString().replace(Regex(" {2,}"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")       // no space left before punctuation
            .replace(Regex("^[\\s,.;:!?]+"), "").trim()    // nor punctuation at the start
    }

    /**
     * The search terms in an address bar: the q= / p= / search_query= / query= / text= value of a URL, or the bar's
     * text itself when it isn't an address (Chrome shows the search terms). Null for a plain address.
     */
    fun queryOf(bar: String): String? {
        val t = bar.trim()
        if (t.isEmpty()) return null
        Regex("[?&#](q|p|search_query|query|text)=([^&#]*)").find(t)?.let { m ->
            return runCatching { URLDecoder.decode(m.groupValues[2], "UTF-8") }.getOrDefault(m.groupValues[2]).takeIf { it.isNotBlank() }
        }
        return if (hostOf(t) == null) t else null
    }

    /**
     * The address to search again without the blocked words: when the bar shows a search URL (Google, Bing,
     * YouTube…), the same URL with its query replaced by [rest] (so the same site searches it, echo parameters such as
     * oq dropped); otherwise [rest] itself (the browser's default engine). Null if [bar] isn't a search.
     */
    fun researchTarget(bar: String, rest: String): String? {
        val t = bar.trim()
        val m = Regex("([?&#])(q|p|search_query|query|text)=([^&#]*)").find(t) ?: return if (hostOf(t) == null) rest else null
        val enc = URLEncoder.encode(rest, "UTF-8")
        var url = t.replaceRange(m.range, "${m.groupValues[1]}${m.groupValues[2]}=$enc")
        url = url.replace(Regex("[&](oq|pq|sp|aqs|gs_lcrp)=[^&#]*"), "")
        if (!url.contains("://")) url = "https://$url"
        return url
    }

    // ---- stored list ----
    @Volatile private var cache: Set<String>? = null

    fun keywords(c: Context): Set<String> = cache ?: Prefs.getSet(c, Prefs.K_SEARCH_KEYWORDS).also { cache = it }

    // ---- blocked sites in every browser (your list + active screen-time sites) ----
    @Volatile private var siteCache: Set<String>? = null

    /** Your blocked sites plus sites of an active screen-time schedule, lowercase. */
    fun sites(c: Context): Set<String> =
        siteCache ?: (Prefs.domains(c) + Prefs.stSites(c)).map { it.lowercase() }.toSet().also { siteCache = it }

    /** On while there are keywords or blocked sites (the accessibility service, force-stop protection). */
    fun active(c: Context) = !Mdm.releasing && (keywords(c).isNotEmpty() || sites(c).isNotEmpty())

    /** The host shown in an address bar ("https://www.Example.com/x" or "example.com/x" → "example.com"), or null. */
    fun hostOf(text: String): String? {
        // First word only: Chrome shows "example.org. Connection is not secure" in its address bar.
        var t = text.trim().lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (t.isEmpty()) return null
        t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').trimEnd('.')
        t = t.removePrefix("www.")
        return t.takeIf { Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(it) }
    }

    /** The blocked site [text]'s host belongs to (the site itself or a subdomain), or null. */
    fun blockedSite(text: String, sites: Set<String>): String? {
        var h = hostOf(text) ?: return null
        while (true) {
            if (h in sites) return h
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return null // stop at the registrable part ("example.com")
            h = h.substring(i + 1)
        }
    }

    /** The site list changed (Websites, screen time, profile import): refresh the service and its protection. */
    fun sitesChanged(c: Context) {
        siteCache = null
        runCatching { ensureService(c) }
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
     * Danger zone → Release all: switch the service and the force-stop protection off while still Device Owner.
     * Keywords and sites stay saved (Mdm.releasing makes [active] false meanwhile).
     */
    fun clearAll(c: Context) {
        cache = null
        siteCache = null
        runCatching { ensureService(c) }
        runCatching { applyUserControl(c) }
        runCatching { SearchBlockService.instance?.disableSelf() } // off even without the secure-settings grant
    }

    /** After the list changed here or by a profile import: refresh the service, Chrome's profile and GuardService. */
    fun changed(c: Context) {
        cache = null
        siteCache = null
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
 * keyword. Other apps (YouTube, Instagram, Facebook, Telegram, X…): their search boxes, found by id or by
 * a "Search" hint, plus an in-app browser's address bar and typing inside web content. Chat boxes aren't read.
 * A search is acted on only once it's complete (submitted; never while the keyboard is open on it), and only the
 * blocked word is removed. Window scans run on a background thread, at most one per [SCAN_GAP_MS], only for the
 * window of the app that sent the event, and never act on DeviceGuard itself, the keyboard or System UI.
 */
class SearchBlockService : AccessibilityService() {
    companion object {
        @Volatile var instance: SearchBlockService? = null
            private set

        private val BROWSER_HINTS = listOf("url", "search", "query", "omnibox", "address", "location", "title")
        /** Other apps (YouTube, Instagram, Facebook, Telegram…): search boxes and in-app browser address bars. */
        private val APP_HINTS = listOf("search", "query", "url", "address", "omnibox")
        private val ADDRESS_HINTS = listOf("url", "omnibox", "omnibar", "address", "location")
        /** Neutral pages a browser tab is sent to; the first one not on the blocked list is used. */
        private val NEUTRAL_PAGES = listOf("https://www.google.com", "https://duckduckgo.com", "about:blank")
        private const val MAX_NODES = 300
        private const val SCAN_GAP_MS = 400L
        private const val SYSTEM_UI = "com.android.systemui"
        /** Apps the keyword and site blocker leaves alone (the Play Store: by request). */
        private val EXCLUDED = setOf("com.android.vending")
        /**
         * Apps whose search results screen shows the search as plain text in the top bar (no id, no hint; Compose
         * UIs). For these, text in the top bar of the screen is read too.
         */
        private val SEARCH_APPS = setOf(
            "com.google.android.youtube", "com.instagram.android", "com.facebook.katana",
            "com.facebook.lite", "org.telegram.messenger", "app.nicegram", "org.thunderdog.challegram", "com.twitter.android",
            "com.reddit.frontpage", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.pinterest",
            "com.snapchat.android", "com.google.android.apps.youtube.music",
        )
        private const val TOP_BAR_FRACTION = 0.22
    }

    private val worker = HandlerThread("search-block").apply { start() }
    private val bg = Handler(worker.looper)
    @Volatile private var browsers: Set<String> = emptySet()
    @Volatile private var ignored: Set<String> = emptySet()
    @Volatile private var lastBlock = 0L
    @Volatile private var lastScan = 0L
    @Volatile private var scanPkg: String? = null

    /**
     * A keyword found in a SUBMITTED search; [field] is the editable box holding it (the word is taken out of it).
     * [site]: a blocked site is open in the address bar (the page is left instead).
     */
    private class Hit(val keyword: String, val field: AccessibilityNodeInfo?, val site: Boolean = false, val partial: Boolean = false)

    /**
     * Last text seen in an app's search box while the keyboard was open. If the box goes away (the user tapped a
     * suggestion or result while still typing), that text counts as the submitted search.
     */
    @Volatile private var pendingPkg: String? = null
    @Volatile private var pendingText: String? = null
    @Volatile private var pendingAt = 0L

    /** Nothing to do: no keywords or sites, Release all running, or no longer Device Owner (checked every 10 s). */
    private fun nothingToBlock(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - ownerCheckedAt > 10_000) { owner = runCatching { Mdm(this).isOwner }.getOrDefault(false); ownerCheckedAt = now }
        return !owner || !SearchBlocker.active(this)
    }
    @Volatile private var owner = true
    @Volatile private var ownerCheckedAt = 0L

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
        ignored = (imes + packageName + SYSTEM_UI + EXCLUDED).toSet()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        // Interactive windows: to see whether the keyboard is open (= still typing).
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 250
        info.packageNames = null
        serviceInfo = info
    }

    /**
     * A search is checked only once it's COMPLETE: never while the keyboard is open on it. It counts as complete when
     * it's submitted (Enter / search key, a results page, the keyboard closed), so typos and half-typed words are
     * never touched.
     */
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { // e.g. the keyboard closed after Enter
            if (!nothingToBlock()) requestScan(null)
            return
        }
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored) return
        if (nothingToBlock()) return
        if (SystemClock.elapsedRealtime() - lastBlock < 1500) return // just acted: let it finish
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val types = e.contentChangeTypes
                val relevant = types == 0 || types and (AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0
                if (relevant) requestScan(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> requestScan(pkg)
        }
    }

    /** At most one scan per [SCAN_GAP_MS]; a later request in the gap runs once at its end. [pkg] null = active app. */
    private fun requestScan(pkg: String?) {
        scanPkg = pkg
        val wait = SCAN_GAP_MS - (SystemClock.elapsedRealtime() - lastScan)
        bg.removeCallbacks(scanRunnable)
        bg.postDelayed(scanRunnable, wait.coerceAtLeast(0))
    }

    private val scanRunnable = Runnable {
        lastScan = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - lastBlock < 1500) return@Runnable
        val pkg = scanPkg ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return@Runnable
        if (pkg in ignored) return@Runnable
        if (checkPending(pkg)) return@Runnable
        val hit = runCatching { findHit(pkg) }.getOrNull() ?: return@Runnable
        block(hit, pkg)
    }

    /**
     * The user was typing in [pkg]'s search box and the box is gone or lost focus (tapped a suggestion / result):
     * the remembered text counts as submitted. True if it acted.
     */
    private fun checkPending(pkg: String): Boolean {
        val text = pendingText ?: return false
        if (pendingPkg != pkg || SystemClock.elapsedRealtime() - pendingAt > 5000) { pendingText = null; return false }
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        if (root.packageName?.toString() != pkg) return false
        val focus = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focus != null && focus.isEditable && isSearchField(focus)) return false // still in the box
        pendingText = null
        val k = SearchBlocker.match(text, SearchBlocker.keywords(this)) ?: return false
        block(Hit(k, null), pkg)
        return true
    }

    /** The keyboard is on screen (the user is still typing). Unknown → treated as open (never act on a guess). */
    private fun keyboardOpen(): Boolean = runCatching {
        val ws = windows
        ws.isEmpty() || ws.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(true)

    /**
     * A keyword in a submitted search, or a blocked site, in [pkg]'s focused window; null if none. Never reads a
     * window of another app. A box that's focused while the keyboard is open is still being typed: skipped, unless
     * [afterBlock] (the follow-up right after DeviceGuard itself acted).
     */
    private fun findHit(pkg: String, afterBlock: Boolean = false): Hit? {
        if (nothingToBlock()) return null
        val kws = SearchBlocker.keywords(this)
        val sites = SearchBlocker.sites(this)
        val root = rootInActiveWindow ?: return null
        val rp = root.packageName?.toString() ?: return null
        if (rp != pkg || rp in ignored) return null
        val typing = !afterBlock && keyboardOpen()
        fun stillTyping(n: AccessibilityNodeInfo) = typing && n.isEditable && n.isFocused
        val browser = rp in browsers
        val focusNode = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        // Browser with the keyboard open on a box: the user is typing; nothing in this window is acted on.
        if (browser && focusNode != null && focusNode.isEditable && stillTyping(focusNode)) return null
        val hints = if (browser) BROWSER_HINTS else APP_HINTS
        val topBar = if (rp in SEARCH_APPS) (resources.displayMetrics.heightPixels * TOP_BAR_FRACTION).toInt() else 0
        val r = android.graphics.Rect()
        if (!browser) {
            // An app's search box with input focus: check only that box (not the suggestion / history rows around it).
            val focus = focusNode
            if (focus != null && focus.isEditable) {
                if (!isSearchField(focus)) return null
                val t = typedText(focus) ?: return null
                if (stillTyping(focus)) { // remember it, in case a suggestion / result is tapped before submitting
                    pendingPkg = rp; pendingText = t; pendingAt = SystemClock.elapsedRealtime()
                    return null
                }
                // Focused but keyboard closed: finished words only (a hidden keyboard isn't a submit).
                val partial = !afterBlock
                return SearchBlocker.match(t, kws, editing = partial)?.let { Hit(it, focus, partial = partial) }
            }
        }
        // Breadth-first so the toolbar is reached first; page content (WebView) is not entered.
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        var readOnly: Hit? = null // a read-only match; an editable box holding a keyword is preferred (the word can be removed)
        var siteInApp: Hit? = null // apps: a blocked site in an address bar, confirmed only if the window shows a web page
        var sawWeb = false
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst(); seen++
            if (!stillTyping(n)) {
                val id = n.viewIdResourceName?.substringAfter('/')?.lowercase().orEmpty()
                val inTopBar = topBar > 0 && n.text != null && run { n.getBoundsInScreen(r); r.height() > 0 && r.bottom <= topBar }
                val idHit = hints.any { it in id }
                val field = if (browser) (idHit || n.isEditable || hintSaysSearch(n)) && !isInList(n)
                // Apps: the search box / the query in the top bar, never suggestion, history or result rows.
                else (idHit || hintSaysSearch(n) || inTopBar) && !isInList(n)
                // A blocked site open in a real address bar (never suggestion / history / download rows or titles).
                val addressBar = !isInList(n) && (ADDRESS_HINTS.any { it in id } ||
                    (browser && n.isEditable && ("search" in id || "query" in id)))
                if (addressBar && sites.isNotEmpty()) typedText(n)?.let { t ->
                    SearchBlocker.blockedSite(t, sites)?.let {
                        if (browser) return Hit(it, null, site = true)
                        if (siteInApp == null) siteInApp = Hit(it, null, site = true)
                    }
                }
                if (field) typedText(n)?.let { t ->
                    // A focused box with the keyboard closed: finished words only (it may not be submitted).
                    val partial = !afterBlock && n.isEditable && n.isFocused
                    SearchBlocker.match(t, kws, editing = partial)?.let { k ->
                        if (n.isEditable) return Hit(k, n, partial = partial)
                        if (readOnly == null) readOnly = Hit(k, null)
                    }
                }
            }
            if (isWebContent(n)) { sawWeb = true; continue }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (siteInApp != null && sawWeb) return siteInApp
        return readOnly
    }

    /** The node's real text, or null if it's empty or only its placeholder (hint) is showing. */
    private fun typedText(n: AccessibilityNodeInfo): String? {
        val t = n.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (n.isShowingHintText || t == n.hintText?.toString()) return null
        return t
    }

    /** Inside a list or grid (suggestions, history, results): walks up a few parents. */
    private fun isInList(n: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        var depth = 0
        while (p != null && depth++ < 6) {
            if (p.collectionItemInfo != null || p.collectionInfo != null) return true
            val cls = p.className?.toString().orEmpty()
            if (cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")) return true
            p = p.parent
        }
        return false
    }

    /** A search box without a helpful id (Telegram, Compose apps): its hint text says "Search". */
    private fun hintSaysSearch(n: AccessibilityNodeInfo): Boolean =
        n.hintText?.toString()?.lowercase()?.contains("search") == true

    private fun isSearchField(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        val id = n.viewIdResourceName?.substringAfter('/')?.lowercase().orEmpty()
        return "search" in id || "query" in id || hintSaysSearch(n)
    }

    private fun isWebContent(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString().orEmpty()
        return cls == "android.webkit.WebView" || cls.startsWith("org.chromium.")
    }

    /**
     * Acts on a submitted search / an open blocked site, keeping as much of what the user typed as possible:
     *  - Browser search: the same search runs again WITHOUT the blocked word ("best casino hotels" → "best hotels");
     *    nothing left → the tab goes to a neutral page.
     *  - App search: the blocked word is taken out of the search box (the rest stays); a results page with no box
     *    is left with Back, then the word is taken out of the box underneath.
     *  - Blocked site: browser tab → neutral page; in-app browser → one Back.
     * Home is never used (the app would reopen on the same screen and block again in a loop).
     */
    private fun block(hit: Hit, pkg: String) {
        lastBlock = SystemClock.elapsedRealtime()
        val browser = pkg in browsers
        val action = when {
            hit.site && browser -> if (navigateAway(pkg)) "navigated" else back()
            hit.site -> back()
            // Not submitted yet (focused box, keyboard closed), or a box that isn't the address bar: take the word out only.
            hit.field != null && (hit.partial || (browser && !isAddressBar(hit.field))) ->
                if (clear(hit.field, hit.partial)) "word removed" else "none"
            browser -> when {
                researchWithout(pkg) -> "re-searched"
                navigateAway(pkg) -> "navigated"
                else -> back()
            }
            // App, submitted search in its box: take the word out and search again without it.
            hit.field != null && clear(hit.field) -> if (resubmit(hit.field)) "re-searched" else back()
            else -> back()
        }
        android.util.Log.i("SearchBlock", "hit in $pkg site=${hit.site} field=${hit.field?.viewIdResourceName} action=$action")
        // After Back in an app: take the word out of the search box underneath (once).
        if (action == "back" && !hit.site) bg.postDelayed({ followUp(pkg, tries = 3) }, 900)
        Notify.post(
            // The keyword itself isn't shown: the list is private (hidden behind the PIN in DeviceGuard).
            this, Channel.BLOCKED, SearchBlocker.NOTIFY_ID, if (hit.site) "Site blocked" else "Search blocked",
            when {
                hit.site -> "This site is on the blocked list"
                action == "re-searched" || action == "word removed" || action == "none" -> "The blocked word was removed from your search"
                else -> "It contains a blocked keyword"
            } + " (${Apps.label(this, pkg)}).",
        )
    }

    private fun back(): String { performGlobalAction(GLOBAL_ACTION_BACK); return "back" }

    private fun isAddressBar(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.substringAfter('/')?.lowercase().orEmpty()
        return listOf("url", "omnibox", "omnibar", "address", "location", "search").any { it in id }
    }

    /** Submits an app's search box again (its text already has the word taken out). Android 11+. */
    private fun resubmit(field: AccessibilityNodeInfo): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 30) return false
        field.refresh()
        if (field.text.isNullOrBlank()) return false // nothing left: Back instead
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }.getOrDefault(false)

    /** Right after a Back in an app: the search box underneath still holds the word → take it out (no more Back). */
    private fun followUp(pkg: String, tries: Int = 1) {
        lastBlock = SystemClock.elapsedRealtime() // normal scans wait while the follow-up works
        val h = runCatching { findHit(pkg, afterBlock = true) }.getOrNull()
        if (h?.field == null) { // the box isn't there yet: try again shortly
            if (tries > 1) bg.postDelayed({ followUp(pkg, tries - 1) }, 700)
            return
        }
        val ok = clear(h.field)
        android.util.Log.i("SearchBlock", "follow-up field=${h.field.viewIdResourceName} removed=$ok")
    }

    /**
     * Browser: runs the search shown in the address bar again without the blocked words (the query is read from the
     * URL's q= / p= / search_query= or from the bar's text). False if nothing is left or the bar can't be used.
     */
    private fun researchWithout(pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val bar = findAddressBar(root) ?: return false
        val barText = bar.text?.toString().orEmpty()
        val query = SearchBlocker.queryOf(barText) ?: return false
        val rest = SearchBlocker.removeKeywords(query, SearchBlocker.keywords(this))?.trim()
        if (rest.isNullOrBlank() || SearchBlocker.match(rest, SearchBlocker.keywords(this)) != null) return false
        val target = SearchBlocker.researchTarget(barText, rest) ?: return false
        if (SearchBlocker.match(target, SearchBlocker.keywords(this)) != null) return false
        submit(bar, target)
    }.getOrDefault(false)

    /** Puts a neutral page (not on the blocked list) in the browser's address bar and presses Enter. */
    private fun navigateAway(pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val bar = findAddressBar(root) ?: return false
        val sites = SearchBlocker.sites(this)
        val page = NEUTRAL_PAGES.firstOrNull { SearchBlocker.blockedSite(it, sites) == null } ?: return false
        submit(bar, page)
    }.getOrDefault(false)

    /** Types [text] into the address bar and presses Enter (Android 11+ IME action). */
    private fun submit(bar: AccessibilityNodeInfo, text: String): Boolean {
        bar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!bar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        return bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** The browser's editable address bar (by id), searched breadth-first outside page content. */
    private fun findAddressBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst(); seen++
            val id = n.viewIdResourceName?.substringAfter('/')?.lowercase().orEmpty()
            if (n.isEditable && listOf("url", "omnibox", "omnibar", "address", "location", "search").any { it in id }) return n
            if (isWebContent(n)) continue
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /**
     * Takes only the blocked word(s) out of an editable box and keeps the rest, cursor at the end (the whole box is
     * cleared only if the word can't be located). False if the app refused.
     */
    private fun clear(field: AccessibilityNodeInfo, editing: Boolean = false): Boolean = runCatching {
        field.refresh()
        val old = field.text?.toString().orEmpty()
        val kept = SearchBlocker.removeKeywords(old, SearchBlocker.keywords(this), editing) ?: if (editing) return false else ""
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, kept)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && kept.isNotEmpty()) field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, kept.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, kept.length)
        })
        ok
    }.getOrDefault(false)

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.quitSafely()
        super.onDestroy()
    }
}
