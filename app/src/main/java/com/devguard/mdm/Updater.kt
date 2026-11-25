// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Auto-update from GitHub Releases. Convention: release tag `build-<versionCode>` (e.g. build-3) with one
 * `.apk` asset named DeviceGuard.apk (the RELEASE build, signed with the same key). The repo is public, so no
 * token is needed, and no rate-limited API is used. As Device Owner the install is silent.
 * Only APKs signed with this app's key are installed (see [check]); publishing needs the write token on the PC.
 */
object Updater {
    const val DEFAULT_REPO = "sakthivelgreen/DeviceGaurd"

    data class Release(val code: Int, val tag: String, val downloadUrl: String, val notes: String)

    fun repo(c: Context) = Prefs.getStr(c, Prefs.UPD_REPO)?.takeIf { it.contains('/') } ?: DEFAULT_REPO

    /** Direct link to the newest release APK (for the setup QR code). */
    fun latestApkUrl(c: Context) = "https://github.com/${repo(c)}/releases/latest/download/DeviceGuard.apk"

    /** The read-only token older versions stored for the private repo isn't used any more: delete it. */
    fun removeLegacyToken(c: Context) { if (Secrets.has(c, Prefs.UPD_TOKEN)) Secrets.put(c, Prefs.UPD_TOKEN, null) }

    fun currentCode(c: Context): Int = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "DeviceGuard-MDM")
        }

    /**
     * Latest release, or throws with a readable message. Blocking.
     * Uses github.com's "releases/latest" redirect (→ …/releases/tag/build-3) instead of the REST API: the API allows
     * only 60 unauthenticated calls an hour per public IP, which a shared office or home network can use up.
     */
    fun latest(c: Context): Release {
        val conn = open("https://github.com/${repo(c)}/releases/latest", "text/html").apply { instanceFollowRedirects = false }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location").orEmpty()
        conn.disconnect()
        if (code == 404) throw IllegalStateException("Repository ${repo(c)} not found (is it public?)")
        if (code !in 300..399) throw IllegalStateException("GitHub error HTTP $code")
        val tag = location.substringAfter("/releases/tag/", "").substringBefore('?')
        if (tag.isEmpty()) throw IllegalStateException("No release published in ${repo(c)} yet")
        val vc = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalStateException("Release tag \"$tag\" must look like build-3")
        return Release(vc, tag, "https://github.com/${repo(c)}/releases/download/$tag/DeviceGuard.apk", "")
    }

    /** Downloads and silently installs [r]. Returns null when the install was handed to Android, or an error. */
    fun install(c: Context, r: Release): String? = runCatching {
        // The debug build (testOnly, ADB escape hatch) stays on the development phone: never replace it with a release.
        if (BuildConfig.DEBUG) return "This is the debug build: releases are for other phones. Update it with adb install."
        val file = File(c.cacheDir, "update.apk")
        // Public release asset: github.com redirects (https → https) to its download server; followed automatically.
        val conn = open(r.downloadUrl, "application/octet-stream")
        if (conn.responseCode != 200) return "Download failed (HTTP ${conn.responseCode})"
        conn.inputStream.use { inp -> file.outputStream().use { inp.copyTo(it) } }
        check(c, file, r)?.let { file.delete(); return it } // B-09: right app, newer version, same signing key

        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            // A failed install shows a notification (InstallResultReceiver); success restarts the app.
            s.commit(InstallResultReceiver.pendingIntent(c, InstallResultReceiver.UPDATE, c.packageName).intentSender)
        }
        null
    }.getOrElse { it.message ?: "Update failed" }

    /**
     * Checks the downloaded APK before handing it to Android (B-09): it must be DeviceGuard, newer than the
     * installed version, match the release tag, and be signed with the same key. Returns an error or null.
     */
    private fun check(c: Context, file: File, r: Release): String? {
        val pm = c.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "The downloaded file isn't a valid APK"
        if (apk.packageName != c.packageName) return "The release APK is a different app (${apk.packageName})"
        val code = apk.longVersionCode
        if (code <= currentCode(c)) return "The release APK isn't newer (version $code)"
        if (code != r.code.toLong()) return "The APK's version ($code) doesn't match the tag ${r.tag}"
        val mine = signers(pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo)
        if (mine.isEmpty() || signers(apk.signingInfo) != mine) return "The release APK is signed with a different key"
        return null
    }

    private fun signers(info: SigningInfo?): Set<String> = info?.apkContentsSigners.orEmpty()
        .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()

    /** Failed checks retry at most hourly (in memory), instead of waiting a whole day (B-09). */
    @Volatile private var lastTry = 0L

    /** Called on screen on: at most once a day, if auto-update is on. The day only counts once GitHub answered. */
    fun autoCheck(c: Context) {
        if (BuildConfig.DEBUG || !Prefs.getFlag(c, Prefs.UPD_AUTO) || !Mdm(c).isOwner) return // never on the debug build
        val now = System.currentTimeMillis()
        if (now - Prefs.getLong(c, Prefs.UPD_LAST) < 24 * 3600_000L) return
        if (now - lastTry < 3600_000L) return
        lastTry = now
        val r = runCatching { latest(c) }.getOrNull() ?: return
        Prefs.setLong(c, Prefs.UPD_LAST, now)
        if (r.code > currentCode(c)) install(c, r)
    }
}
