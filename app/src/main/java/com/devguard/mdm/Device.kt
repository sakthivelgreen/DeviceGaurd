// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.app.admin.WifiSsidPolicy
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import java.security.MessageDigest

/** Device-level policies: Wi-Fi allowlist, system updates, per-app permissions, provisioning QR. */
object Device {

    // ---------- Wi-Fi allowlist (Android 13+) ----------
    fun wifiAllowSupported() = Build.VERSION.SDK_INT >= 33

    /** Current Wi-Fi name, or null (needs location permission + location on; DeviceGuard grants itself the permission). */
    @Suppress("DEPRECATION")
    fun currentSsid(c: Context): String? {
        val m = Mdm(c)
        if (m.isOwner) runCatching {
            m.dpm.setPermissionGrantState(m.admin, c.packageName, Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        val ssid = runCatching { c.getSystemService(WifiManager::class.java).connectionInfo?.ssid }.getOrNull() ?: return null
        val s = ssid.trim('"')
        return if (s.isBlank() || s == "<unknown ssid>") null else s
    }

    /** Applies the allowlist (or removes it). Returns null on success or an error. */
    fun applyWifiAllow(c: Context): String? {
        if (!wifiAllowSupported()) return "Needs Android 13 or newer"
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        return runCatching {
            val on = Prefs.getFlag(c, Prefs.WIFI_ALLOW_ON)
            val list = Prefs.wifiAllow(c)
            if (on && list.isNotEmpty()) {
                val ssids = list.map { WifiSsid.fromBytes(it.toByteArray(Charsets.UTF_8)) }.toSet()
                m.dpm.wifiSsidPolicy = WifiSsidPolicy(WifiSsidPolicy.WIFI_SSID_POLICY_TYPE_ALLOWLIST, ssids)
            } else m.dpm.wifiSsidPolicy = null
            null
        }.getOrElse { it.message ?: "Couldn't apply the Wi-Fi policy" }
    }

    // ---------- System updates ----------
    val UPDATE_OPTIONS = listOf("Phone default", "Install automatically", "Install at night (02:00–04:00)", "Postpone 30 days")

    fun updateMode(c: Context): Int {
        val p = runCatching { Mdm(c).dpm.systemUpdatePolicy }.getOrNull() ?: return 0
        return when (p.policyType) {
            SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC -> 1
            SystemUpdatePolicy.TYPE_INSTALL_WINDOWED -> 2
            SystemUpdatePolicy.TYPE_POSTPONE -> 3
            else -> 0
        }
    }

    fun setUpdateMode(c: Context, mode: Int) {
        val m = Mdm(c)
        val policy = when (mode) {
            1 -> SystemUpdatePolicy.createAutomaticInstallPolicy()
            2 -> SystemUpdatePolicy.createWindowedInstallPolicy(120, 240)
            3 -> SystemUpdatePolicy.createPostponeInstallPolicy()
            else -> null
        }
        m.dpm.setSystemUpdatePolicy(m.admin, policy)
    }

    // ---------- Per-app permissions ----------
    val PERMISSION_GROUPS: List<Pair<String, List<String>>> = listOf(
        "Location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        "Camera" to listOf(Manifest.permission.CAMERA),
        "Microphone" to listOf(Manifest.permission.RECORD_AUDIO),
        "Contacts" to listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS),
        "Phone" to listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.ANSWER_PHONE_CALLS),
        "Call log" to listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
        "SMS" to listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
        "Photos & media" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", Manifest.permission.READ_EXTERNAL_STORAGE),
        "Calendar" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        "Nearby devices" to listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.NEARBY_WIFI_DEVICES"),
        "Body sensors & activity" to listOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION),
        "Notifications" to listOf("android.permission.POST_NOTIFICATIONS"),
    )
    val GRANT_STATES = listOf("Let the user decide", "Always allow", "Always deny")

    private fun requested(c: Context, pkg: String): Set<String> = runCatching {
        c.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet()
    }.getOrNull() ?: emptySet()

    /** Groups the app actually asks for, with the current policy state (0 default, 1 allow, 2 deny). */
    fun permissionGroups(c: Context, pkg: String): List<Triple<String, List<String>, Int>> {
        val req = requested(c, pkg)
        val m = Mdm(c)
        return PERMISSION_GROUPS.mapNotNull { (name, perms) ->
            val used = perms.filter { it in req }
            if (used.isEmpty()) return@mapNotNull null
            val states = used.map { runCatching { m.dpm.getPermissionGrantState(m.admin, pkg, it) }.getOrDefault(0) }.toSet()
            val s = when {
                states == setOf(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) -> 1
                states == setOf(DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED) -> 2
                else -> 0
            }
            Triple(name, used, s)
        }
    }

    /** Returns the number of permissions that Android refused to change. */
    fun setGroup(c: Context, pkg: String, perms: List<String>, state: Int): Int {
        val m = Mdm(c)
        val dpmState = when (state) {
            1 -> DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            2 -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            else -> DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        }
        return perms.count { !runCatching { m.dpm.setPermissionGrantState(m.admin, pkg, it, dpmState) }.getOrDefault(false) }
    }

    // ---------- QR provisioning ----------
    /** SHA-256 of DeviceGuard's signing certificate, base64url (what the setup wizard checks). */
    fun signatureChecksum(c: Context): String {
        val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo!!.apkContentsSigners[0].toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(cert)
        return android.util.Base64.encodeToString(sha, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    fun provisioningJson(c: Context, apkUrl: String, wifiSsid: String?, wifiPass: String?): String {
        val o = org.json.JSONObject()
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", "com.devguard.mdm/.AdminReceiver")
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", apkUrl)
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", signatureChecksum(c))
            .put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
            .put("android.app.extra.PROVISIONING_SKIP_ENCRYPTION", true)
        if (!wifiSsid.isNullOrBlank()) {
            o.put("android.app.extra.PROVISIONING_WIFI_SSID", wifiSsid)
            if (!wifiPass.isNullOrBlank()) {
                o.put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "WPA")
                o.put("android.app.extra.PROVISIONING_WIFI_PASSWORD", wifiPass)
            }
        }
        return o.toString()
    }
}
