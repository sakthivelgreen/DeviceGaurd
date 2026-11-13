// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Persistent policy state that DevicePolicyManager does not store for us. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("policy", Context.MODE_PRIVATE)

    // ---- Admin PIN (salted, iterated SHA-256) ----
    fun hasPin(c: Context) = p(c).contains("pinHash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = Base64.encodeToString(salt, Base64.NO_WRAP)
        p(c).edit().putString("pinSalt", s).putString("pinHash", hash(s, pin)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = p(c).getString("pinSalt", null) ?: return false
        val stored = p(c).getString("pinHash", "") ?: ""
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), stored.toByteArray())
    }

    // ---- Recovery code (backup for a forgotten PIN) ----
    fun hasRecovery(c: Context) = p(c).contains("recHash")

    /** Creates a new random recovery code (e.g. K7QM-3PXD-W9TA-6HZN), stores only its hash, returns it to show once. */
    fun newRecoveryCode(c: Context): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val raw = (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        val code = raw.chunked(4).joinToString("-")
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val s = Base64.encodeToString(salt, Base64.NO_WRAP)
        p(c).edit().putString("recSalt", s).putString("recHash", hash(s, normalizeCode(code))).commit()
        return code
    }

    fun checkRecovery(c: Context, code: String): Boolean {
        val salt = p(c).getString("recSalt", null) ?: return false
        val stored = p(c).getString("recHash", "") ?: ""
        return MessageDigest.isEqual(hash(salt, normalizeCode(code)).toByteArray(), stored.toByteArray())
    }

    private fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    /** Removes the PIN; the app then asks to create a new one (used by recovery code and PC reset). */
    fun clearPin(c: Context) = p(c).edit().remove("pinHash").remove("pinSalt").commit()

    private fun hash(salt: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var d = (salt + pin).toByteArray()
        repeat(10_000) { d = md.digest(d) }
        return Base64.encodeToString(d, Base64.NO_WRAP)
    }

    // ---- Generic helpers ----
    private fun set(c: Context, k: String): Set<String> = p(c).getStringSet(k, emptySet())!!.toSet()
    private fun putSet(c: Context, k: String, v: Set<String>) = p(c).edit().putStringSet(k, v).commit()
    private fun flag(c: Context, k: String) = p(c).getBoolean(k, false)
    private fun putFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).commit()

    // ---- Website blocking ----
    fun domains(c: Context) = set(c, "domains")
    fun setDomains(c: Context, v: Set<String>) = putSet(c, "domains", v)
    fun webBlock(c: Context) = flag(c, "webBlock")
    fun setWebBlock(c: Context, v: Boolean) = putFlag(c, "webBlock", v)
    // ---- Filter guard (self-control lock) ----
    const val GUARD_ON = "guardOn"
    const val GUARD_DELAY = "guardDelayMin"
    const val GUARD_WAIT_START = "guardWaitStart"
    fun getInt(c: Context, k: String, def: Int) = p(c).getInt(k, def)
    fun setInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).commit()
    fun getLong(c: Context, k: String) = p(c).getLong(k, 0L)
    fun setLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).commit()

    // ---- Smart DNS (office-network backup) ----
    const val SMART_DNS_OFF = "smartDnsOff"      // default ON (flag is "off")
    const val DNS_FALLBACK = "dnsFallback"       // true while the backup VPN filter is in use
    fun officeDomains(c: Context) = set(c, "officeDomains")
    fun setOfficeDomains(c: Context, v: Set<String>) = putSet(c, "officeDomains", v)
    fun dnsBlockedNets(c: Context) = set(c, "dnsBlockedNets")
    fun setDnsBlockedNets(c: Context, v: Set<String>) = putSet(c, "dnsBlockedNets", v)

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
    fun hiddenBrowsers(c: Context) = set(c, "hiddenBrowsers")
    fun setHiddenBrowsers(c: Context, v: Set<String>) = putSet(c, "hiddenBrowsers", v)
    fun getFlag(c: Context, k: String) = flag(c, k)
    fun setFlag(c: Context, k: String, v: Boolean) = putFlag(c, k, v)

    // ---- Approved-apps mode ----
    fun approvalMode(c: Context) = flag(c, "approvalMode")
    fun setApprovalMode(c: Context, v: Boolean) = putFlag(c, "approvalMode", v)
    fun approved(c: Context) = set(c, "approved")
    fun setApproved(c: Context, v: Set<String>) = putSet(c, "approved", v)
    fun autoSuspended(c: Context) = set(c, "autoSuspended")
    fun setAutoSuspended(c: Context, v: Set<String>) = putSet(c, "autoSuspended", v)

    // ---- Always-blocked apps ----
    fun blockedPkgs(c: Context) = set(c, "blockedPkgs")
    fun setBlockedPkgs(c: Context, v: Set<String>) = putSet(c, "blockedPkgs", v)

    // ---- Kiosk ----
    fun kioskOn(c: Context) = flag(c, "kioskOn")
    fun setKioskOn(c: Context, v: Boolean) = putFlag(c, "kioskOn", v)
    fun kioskPkgs(c: Context) = set(c, "kioskPkgs")
    fun setKioskPkgs(c: Context, v: Set<String>) = putSet(c, "kioskPkgs", v)
}

/** Admin session: unlocked by PIN, expires after 5 minutes of inactivity. */
object Session {
    private const val TIMEOUT_MS = 5 * 60_000L
    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 30_000L

    @Volatile private var last = 0L
    @Volatile private var fails = 0
    @Volatile private var lockedUntil = 0L

    fun valid() = System.currentTimeMillis() - last < TIMEOUT_MS
    fun touch() { if (valid()) last = System.currentTimeMillis() }
    fun lock() { last = 0 }

    /** Returns null on success, or an error message. */
    fun tryUnlock(c: Context, pin: String): String? {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return "Too many attempts. Wait ${(lockedUntil - now) / 1000 + 1}s."
        return if (Prefs.checkPin(c, pin)) {
            fails = 0; last = now; null
        } else {
            fails++
            if (fails >= MAX_FAILS) { fails = 0; lockedUntil = now + LOCKOUT_MS }
            "Wrong PIN"
        }
    }

    fun unlockNow() { last = System.currentTimeMillis() }

    /** Recovery code check with the same lockout as the PIN. Returns null on success, or an error message. */
    fun tryRecovery(c: Context, code: String): String? {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return "Too many attempts. Wait ${(lockedUntil - now) / 1000 + 1}s."
        return if (Prefs.checkRecovery(c, code)) {
            fails = 0; null
        } else {
            fails++
            if (fails >= MAX_FAILS) { fails = 0; lockedUntil = now + LOCKOUT_MS }
            "Wrong recovery code"
        }
    }
}
