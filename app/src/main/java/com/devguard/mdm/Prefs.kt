// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import android.os.SystemClock

/** Persistent policy state that DevicePolicyManager does not store for us. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("policy", Context.MODE_PRIVATE)

    // ---- Admin PIN + recovery code: salted PBKDF2 hashes, never the secret itself (S-04, D-04) ----
    const val PIN_MIN = 6
    const val PIN_MAX = 12
    private const val PBKDF2_ITERATIONS = 100_000

    fun hasPin(c: Context) = p(c).contains("pinHash")
    fun setPin(c: Context, pin: String) = storeHash(c, "pin", pin)
    fun checkPin(c: Context, pin: String) = checkHash(c, "pin", pin)

    /** Why a new PIN isn't acceptable, or null if it's fine. Used by PIN setup and Change PIN. */
    fun pinProblem(pin: String, confirm: String): String? = when {
        pin.length !in PIN_MIN..PIN_MAX || !pin.all { it.isDigit() } -> "PIN must be $PIN_MIN–$PIN_MAX digits"
        pin.toSet().size == 1 -> "Too easy to guess: all digits are the same"
        "0123456789012345".contains(pin) || "9876543210987654".contains(pin) -> "Too easy to guess: digits in a row"
        pin != confirm -> "PINs don't match"
        else -> null
    }

    fun hasRecovery(c: Context) = p(c).contains("recHash")

    /** Creates a new random recovery code (e.g. K7QM-3PXD-W9TA-6HZN), stores only its hash, returns it to show once. */
    fun newRecoveryCode(c: Context): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val code = (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("").chunked(4).joinToString("-")
        storeHash(c, "rec", normalizeCode(code))
        return code
    }

    fun checkRecovery(c: Context, code: String) = checkHash(c, "rec", normalizeCode(code))

    private fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    /** Removes the PIN; the app then asks to create a new one (used by recovery code and PC reset). */
    fun clearPin(c: Context) = p(c).edit().remove("pinHash").remove("pinSalt").remove("pinAlgo").commit()

    /** Stores a salted PBKDF2 hash of [secret] under "<name>Salt" / "<name>Hash" / "<name>Algo". */
    private fun storeHash(c: Context, name: String, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = Base64.encodeToString(salt, Base64.NO_WRAP)
        p(c).edit().putString("${name}Salt", s).putString("${name}Hash", pbkdf2(secret, salt))
            .putString("${name}Algo", "pbkdf2-sha256:$PBKDF2_ITERATIONS").commit()
    }

    /** Checks [secret]; hashes from older versions (no "<name>Algo") are upgraded to PBKDF2 on the first match. */
    private fun checkHash(c: Context, name: String, secret: String): Boolean {
        val s = p(c).getString("${name}Salt", null) ?: return false
        val stored = p(c).getString("${name}Hash", null) ?: return false
        val legacy = p(c).getString("${name}Algo", null) == null
        val computed = if (legacy) legacyHash(s, secret) else pbkdf2(secret, Base64.decode(s, Base64.NO_WRAP))
        val ok = MessageDigest.isEqual(computed.toByteArray(), stored.toByteArray())
        if (ok && legacy) storeHash(c, name, secret)
        return ok
    }

    private fun pbkdf2(secret: String, salt: ByteArray): String {
        val spec = javax.crypto.spec.PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** Hash format of versions before 2026-11-24 (10k × SHA-256). Only used to verify and upgrade old hashes. */
    private fun legacyHash(salt: String, secret: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var d = (salt + secret).toByteArray()
        repeat(10_000) { d = md.digest(d) }
        return Base64.encodeToString(d, Base64.NO_WRAP)
    }

    // ---- Generic helpers ----
    private fun set(c: Context, k: String): Set<String> = p(c).getStringSet(k, emptySet())!!.toSet()
    private fun putSet(c: Context, k: String, v: Set<String>) = p(c).edit().putStringSet(k, v).commit()
    private fun flag(c: Context, k: String) = p(c).getBoolean(k, false)
    private fun putFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).commit()
    fun getSet(c: Context, k: String): Set<String> = set(c, k)
    fun setSet(c: Context, k: String, v: Set<String>) = synchronized(setLock) { putSet(c, k, v) }

    private val setLock = Any()

    /**
     * B-08: read-modify-write of a string set as ONE step, so two threads (UI, GuardService, screen time,
     * network checks) adding/removing at the same time can't overwrite each other's change.
     */
    fun updateSet(c: Context, k: String, change: (Set<String>) -> Set<String>): Set<String> = synchronized(setLock) {
        change(set(c, k)).also { putSet(c, k, it) }
    }

    // Keys of the string sets changed with updateSet.
    const val K_DOMAINS = "domains"
    const val K_WIFI_ALLOW = "wifiAllow"
    const val K_NO_BG = "noBackgroundPkgs"
    const val K_DNS_BLOCKED_NETS = "dnsBlockedNets"
    const val K_APPROVED = "approved"
    const val K_BLOCKED_PKGS = "blockedPkgs"
    const val K_KIOSK_PKGS = "kioskPkgs"

    // ---- Website blocking ----
    fun domains(c: Context) = set(c, K_DOMAINS)
    fun webBlock(c: Context) = flag(c, "webBlock")
    fun setWebBlock(c: Context, v: Boolean) = putFlag(c, "webBlock", v)
    // ---- Generic string / JSON helpers ----
    fun getStr(c: Context, k: String): String? = p(c).getString(k, null)
    fun setStr(c: Context, k: String, v: String?) = p(c).edit().putString(k, v).commit()

    /** All stored values (for profile export). */
    fun all(c: Context): Map<String, *> = p(c).all

    /** Writes one exported value back (for profile import). */
    @Suppress("UNCHECKED_CAST")
    fun putAny(c: Context, k: String, v: Any?) {
        val e = p(c).edit()
        when (v) {
            null -> e.remove(k)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
            is Set<*> -> e.putStringSet(k, v as Set<String>)
        }
        e.commit()
    }

    // ---- Screen time ----
    const val ST_LIMITS = "stLimits"         // JSON {pkg: minutes}
    const val ST_SCHEDULES = "stSchedules"   // JSON [schedule]
    const val ST_TEMP_ALLOW = "stTempAllow"  // JSON {pkg: untilMillis}
    fun stSuspended(c: Context) = Holds.get(c, Hold.SUSPEND_SCREEN_TIME) // changed only through Holds
    fun stSites(c: Context) = set(c, "stSites")
    fun setStSites(c: Context, v: Set<String>) = putSet(c, "stSites", v)

    // ---- Two-factor (TOTP) ----
    const val TOTP_SECRET = "totpSecret"
    /** Last accepted two-factor step (B-09: a code can't be used twice). */
    const val TOTP_LAST_STEP = "totpLastStep"

    // ---- Wi-Fi allowlist / updates / NextDNS ----
    fun wifiAllow(c: Context) = set(c, K_WIFI_ALLOW)
    const val WIFI_ALLOW_ON = "wifiAllowOn"
    const val UPD_REPO = "updRepo"
    const val UPD_TOKEN = "updToken"
    const val UPD_AUTO = "updAuto"
    const val UPD_LAST = "updLast"
    const val NEXTDNS_KEY = "nextdnsKey"
    const val NEXTDNS_ID = "nextdnsId"

    // ---- Background apps ----
    const val BG_SCREEN_OFF_OFF = "bgScreenOffOff" // default ON (flag is "off")
    const val BG_LAST_RUN = "bgLastRun"
    const val BG_LAST_COUNT = "bgLastCount"
    fun noBackgroundPkgs(c: Context) = set(c, K_NO_BG)

    // ---- Filter guard (self-control lock) ----
    const val GUARD_ON = "guardOn"
    const val GUARD_DELAY = "guardDelayMin"
    const val GUARD_WAIT_START = "guardWaitStart"
    const val GUARD_WAIT_WHAT = "guardWaitWhat"   // the action the pending wait belongs to (B-03)
    fun getInt(c: Context, k: String, def: Int) = p(c).getInt(k, def)
    fun setInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).commit()
    fun getLong(c: Context, k: String) = p(c).getLong(k, 0L)
    fun setLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).commit()

    // ---- Smart DNS (switch to Automatic on networks that block the filter) ----
    const val SMART_DNS_OFF = "smartDnsOff"      // default ON (flag is "off")
    const val DNS_FALLBACK = "dnsFallback"       // true while Private DNS is on Automatic because this network blocks the filter
    fun dnsBlockedNets(c: Context) = set(c, K_DNS_BLOCKED_NETS)

    /** Private DNS host chosen in the app (enforced/auto-reverted by GuardService), or null. */
    fun privateDnsHost(c: Context): String? = p(c).getString("privateDnsHost", null)
    fun setPrivateDnsHost(c: Context, v: String?) = p(c).edit().putString("privateDnsHost", v).commit()
    fun familyDns(c: Context) = flag(c, "familyDns")
    fun setFamilyDns(c: Context, v: Boolean) = putFlag(c, "familyDns", v)

    // ---- Chrome policies ----
    const val CHROME_ON = "chromeOn"
    const val CHROME_SAFESITES = "chromeSafeSites"
    const val CHROME_SAFESEARCH = "chromeSafeSearch"
    const val CHROME_YOUTUBE = "chromeYoutube"
    const val CHROME_NO_INCOGNITO = "chromeNoIncognito"
    const val CHROME_ONLY = "chromeOnly"
    fun hiddenBrowsers(c: Context) = Holds.get(c, Hold.HIDE_CHROME_ONLY) // changed only through Holds
    fun getFlag(c: Context, k: String) = flag(c, k)
    fun setFlag(c: Context, k: String, v: Boolean) = putFlag(c, k, v)

    // ---- Approved-apps mode ----
    fun approvalMode(c: Context) = flag(c, "approvalMode")
    fun setApprovalMode(c: Context, v: Boolean) = putFlag(c, "approvalMode", v)
    fun approved(c: Context) = set(c, K_APPROVED)
    fun autoSuspended(c: Context) = Holds.get(c, Hold.SUSPEND_APPROVAL) // changed only through Holds

    // ---- Always-blocked apps ----
    fun blockedPkgs(c: Context) = set(c, K_BLOCKED_PKGS)

    // ---- Kiosk ----
    fun kioskOn(c: Context) = flag(c, "kioskOn")
    fun setKioskOn(c: Context, v: Boolean) = putFlag(c, "kioskOn", v)
    fun kioskPkgs(c: Context) = set(c, K_KIOSK_PKGS)
}

/**
 * Admin session: unlocked by PIN (+ two-factor), expires after 5 minutes of inactivity.
 * Times use the clock since boot, so changing the date/time can't extend or skip anything (S-04).
 */
object Session {
    private const val TIMEOUT_MS = 5 * 60_000L
    private const val FREE_TRIES = 5                  // wrong answers before the first wait
    private const val BASE_LOCK_MS = 30_000L          // first wait; doubles with each further wrong answer
    private const val MAX_LOCK_MS = 60 * 60_000L      // longest wait: 1 hour
    private const val K_FAILS = "authFails"
    private const val K_LOCK_UNTIL = "authLockUntil"  // elapsedRealtime
    private const val K_LOCK_BOOT = "authLockBoot"    // boot the lock belongs to (elapsedRealtime restarts at boot)

    /** Prefs keys that must never be exported in a profile. */
    val STATE_KEYS = setOf(K_FAILS, K_LOCK_UNTIL, K_LOCK_BOOT)

    @Volatile private var last = 0L // elapsedRealtime of the last activity; 0 = locked

    private fun now() = SystemClock.elapsedRealtime()
    fun valid() = last > 0 && now() - last < TIMEOUT_MS
    fun touch() { if (valid()) last = now() }
    fun lock() { last = 0 }
    fun unlockNow() { last = now() }

    /** PIN check with lockout. Unlocks the session on success. Returns null on success, or an error message. */
    fun tryUnlock(c: Context, pin: String): String? =
        attempt(c, "Wrong PIN") { Prefs.checkPin(c, pin) }.also { if (it == null) unlockNow() }

    /** Recovery code check, same lockout. Returns null on success, or an error message. */
    fun tryRecovery(c: Context, code: String): String? = attempt(c, "Wrong recovery code") { Prefs.checkRecovery(c, code) }

    /** Two-factor code check, same lockout (so the 6-digit code can't be guessed without limit). */
    fun tryTotp(c: Context, code: String): String? = attempt(c, "Wrong code") { Totp.verify(c, code) }

    /**
     * Shared lockout for PIN, recovery code and two-factor code (D-04). Stored, so closing the app doesn't reset
     * it: after [FREE_TRIES] wrong answers each further one waits 30 s, 1 min, 2 min … up to 1 hour.
     */
    @Synchronized
    private fun attempt(c: Context, wrong: String, check: () -> Boolean): String? {
        val boot = bootCount(c)
        val until = if (Prefs.getInt(c, K_LOCK_BOOT, -1) == boot) Prefs.getLong(c, K_LOCK_UNTIL) else 0L
        val n = now()
        if (n < until) return "Too many wrong attempts. Try again in ${wait(until - n)}."
        if (check()) {
            Prefs.setInt(c, K_FAILS, 0); Prefs.setLong(c, K_LOCK_UNTIL, 0L)
            return null
        }
        val fails = Prefs.getInt(c, K_FAILS, 0) + 1
        Prefs.setInt(c, K_FAILS, fails)
        if (fails < FREE_TRIES) return "$wrong (${FREE_TRIES - fails} more before a wait)"
        val lock = minOf(BASE_LOCK_MS shl minOf(fails - FREE_TRIES, 7), MAX_LOCK_MS)
        Prefs.setLong(c, K_LOCK_UNTIL, n + lock); Prefs.setInt(c, K_LOCK_BOOT, boot)
        return "$wrong. Locked for ${wait(lock)}."
    }

    /** Forgotten PIN (recovery code or PC reset): PIN and two-factor removed, lockout cleared, session locked. */
    fun resetCredentials(c: Context) {
        Prefs.clearPin(c)
        Totp.disable(c)
        Prefs.setInt(c, K_FAILS, 0); Prefs.setLong(c, K_LOCK_UNTIL, 0L)
        lock()
    }

    private fun bootCount(c: Context) =
        runCatching { android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(0)

    private fun wait(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${(s + 59) / 60} min" else "$s s"
    }
}
