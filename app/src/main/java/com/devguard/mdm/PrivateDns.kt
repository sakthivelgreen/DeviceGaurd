// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Family-safe filtering without a VPN: force the phone's Private DNS (DNS-over-TLS)
 * to a filtering resolver and lock the setting.
 */
object PrivateDns {
    data class Provider(val name: String, val host: String?, val desc: String)

    const val UNAFFILIATED_HELP =
        "Android won't let the app use its normal method because Private Space (or App Clone / Guest) can't be managed by DeviceGuard.\n\n" +
            "One-time fix, Private Space stays: connect the phone to the PC and run the setup command " +
            "(tap Copy setup command). DeviceGuard can then set and lock Private DNS itself. Then choose the filter again.\n\n" +
            "Other option: Chrome rules (they work in Chrome only)."

    /** One-time ADB grant that lets the app write and lock Private DNS itself. */
    const val ADB_GRANT = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS"

    /** Prefs key: the last built-in filter host used (restored when a removed NextDNS host is migrated). */
    const val LAST_FAMILY = "lastFamilyDns"

    private const val MODE = "private_dns_mode"
    private const val SPEC = "private_dns_specifier"

    /** True after the one-time ADB grant: the app can write Private DNS directly and auto-revert edits. */
    fun canWrite(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Writes Private DNS directly. The "Lock Private DNS" restriction makes SettingsProvider silently
     * ignore writes from every app (only the system Settings app is exempt, which is how vivo Settings
     * can still change it), so the restriction is lifted for a moment and restored right after.
     * Returns true only if the new value was actually stored.
     */
    internal fun write(c: Context, host: String?): Boolean {
        val m = Mdm(c)
        val cr = c.contentResolver
        val key = android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        val locked = m.isOwner && m.hasRestriction(key)
        if (locked) runCatching { m.setRestriction(key, false) }
        val ok = runCatching {
            if (host == null) {
                android.provider.Settings.Global.putString(cr, MODE, "opportunistic")
                android.provider.Settings.Global.getString(cr, MODE) == "opportunistic"
            } else {
                android.provider.Settings.Global.putString(cr, SPEC, host)
                android.provider.Settings.Global.putString(cr, MODE, "hostname")
                android.provider.Settings.Global.getString(cr, MODE) == "hostname" &&
                    android.provider.Settings.Global.getString(cr, SPEC) == host
            }
        }.getOrDefault(false)
        if (locked) runCatching { m.setRestriction(key, true) }
        return ok
    }

    /**
     * Checks the host answers DNS-over-TLS on port 853 (blocking). Known filters are tested by their fixed IPs
     * (with the hostname for TLS), because the current filter may block looking up another DNS service's name
     * (e.g. NextDNS "Block bypass methods" answers 127.0.0.1 for family.cloudflare-dns.com).
     */

    /**
     * Lock enforcement: if the chosen filter host was changed (e.g. in vivo Settings, which ignores the
     * "Lock Private DNS" restriction), put it back. Returns true if a change was reverted.
     */
    fun enforce(c: Context): Boolean {
        val want = Prefs.privateDnsHost(c) ?: return false
        if (!canWrite(c) || !Mdm(c).isOwner) return false
        val cr = c.contentResolver
        val mode = android.provider.Settings.Global.getString(cr, MODE)
        val spec = android.provider.Settings.Global.getString(cr, SPEC)
        if (SmartDns.fallbackActive(c)) {
            // Backup mode: this network blocks the filter's DNS, so Private DNS stays on "automatic".
            if (mode == "opportunistic") return false
            write(c, null)
            return true
        }
        if (mode == "hostname" && spec == want) return false
        write(c, want)
        return true
    }

    /** True if users other than the main one exist (they block phone-wide policies like Private DNS). */
    fun hasOtherUsers(c: Context): Boolean = runCatching {
        c.getSystemService(android.os.UserManager::class.java).userProfiles.size > 1 ||
            Mdm(c).dpm.getSecondaryUsers(Mdm(c).admin).isNotEmpty()
    }.getOrDefault(false)

    val PROVIDERS = listOf(
        Provider("Off", null, "Phone default, no filtering"),
        Provider("Cloudflare Family", "family.cloudflare-dns.com", "Blocks adult content and malware"),
        Provider("Cloudflare Security", "security.cloudflare-dns.com", "Blocks malware only"),
        Provider("AdGuard Family", "family.adguard-dns.com", "Blocks adult content, ads and trackers; forces SafeSearch"),
    )

    /** Current Private DNS host (set by the app or from the PC over ADB), or null if not set. */
    fun currentHost(c: Context): String? {
        val m = Mdm(c)
        if (m.isOwner) runCatching {
            if (m.dpm.getGlobalPrivateDnsMode(m.admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)
                return m.dpm.getGlobalPrivateDnsHost(m.admin)
        }
        // Fallback: read the system setting directly (covers a host set with adb "settings put global ...").
        return runCatching {
            val cr = c.contentResolver
            if (android.provider.Settings.Global.getString(cr, "private_dns_mode") == "hostname")
                android.provider.Settings.Global.getString(cr, "private_dns_specifier")?.takeIf { it.isNotBlank() }
            else null
        }.getOrNull()
    }

    const val ADB_OFF = "adb shell settings put global private_dns_mode opportunistic"

    /**
     * Blocking (network check): call off the main thread. Returns a message for the user.
     * Uses the Device Owner API first; if Android refuses (Private Space = unaffiliated user),
     * writes the setting directly when the one-time ADB grant is present.
     * The chosen host is remembered so GuardService can auto-revert edits (vivo Settings ignores the lock).
     */
    fun apply(c: Context, host: String?): String {
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        SmartDns.reset(c) // leave backup mode; the network check runs again for the new choice
        if (host == null) {
            Prefs.setPrivateDnsHost(c, null)
            val ok = runCatching { m.dpm.setGlobalPrivateDnsModeOpportunistic(m.admin); true }.getOrDefault(false) ||
                (canWrite(c) && write(c, null))
            runCatching { m.setRestriction(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) }
            GuardService.refresh(c)
            return if (ok) "Private DNS filter turned off" else "Couldn't turn it off here. From the PC run:\n$ADB_OFF"
        }

        val viaDpm: Int? = try {
            m.dpm.setGlobalPrivateDnsModeSpecifiedHost(m.admin, host)
        } catch (e: SecurityException) {
            null // unaffiliated user (Private Space / App Clone) - try the direct method below
        }
        when (viaDpm) {
            DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> Unit
            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> {
                // Android's check looks the name up through the CURRENT filter, which may block it
                // (NextDNS "Block bypass methods"). Re-test by IP and write directly if it really works.
                if (!(canWrite(c) && SmartDns.dotReachable(host) && write(c, host)))
                    return "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
            }
            null -> {
                if (!canWrite(c)) return UNAFFILIATED_HELP
                if (!SmartDns.dotReachable(host)) return "$host didn't respond as a DNS-over-TLS server. Check the name and the internet connection."
                if (!write(c, host)) return "Couldn't write the Private DNS setting"
            }
            else -> return "Couldn't set Private DNS"
        }
        Prefs.setPrivateDnsHost(c, host)
        if (PROVIDERS.any { it.host == host }) Prefs.setStr(c, LAST_FAMILY, host) // the family filter used last
        runCatching { m.setRestriction(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS, true) }
        GuardService.refresh(c)
        return if (canWrite(c)) "Filter active: $host (locked: any change is reverted automatically)"
        else "Filter active: $host. To stop it being edited in vivo Settings, run the one-time setup command (see Websites)."
    }
}
