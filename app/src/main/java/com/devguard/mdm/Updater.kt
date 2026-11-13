// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-update from GitHub Releases. Convention: release tag `build-<versionCode>` (e.g. build-3) with one
 * `.apk` asset (the RELEASE build, signed with the same key). Private repos need a read-only token.
 * As Device Owner the install is silent.
 */
object Updater {
    const val DEFAULT_REPO = "sakthivelgreen/DeviceGaurd"

    data class Release(val code: Int, val tag: String, val apiAssetUrl: String, val downloadUrl: String, val notes: String)

    fun repo(c: Context) = Prefs.getStr(c, Prefs.UPD_REPO)?.takeIf { it.contains('/') } ?: DEFAULT_REPO
    private fun token(c: Context) = Prefs.getStr(c, Prefs.UPD_TOKEN)?.takeIf { it.isNotBlank() }

    fun currentCode(c: Context): Int = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()

    private fun open(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "DeviceGuard-MDM")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    /** Latest release, or throws with a readable message. Blocking. */
    fun latest(c: Context): Release {
        val conn = open("https://api.github.com/repos/${repo(c)}/releases/latest", token(c), "application/vnd.github+json")
        val code = conn.responseCode
        if (code == 404) throw IllegalStateException(if (token(c) == null) "No release found, or the repo is private (add a token)" else "No release found")
        if (code == 401 || code == 403) throw IllegalStateException("GitHub refused the token (HTTP $code)")
        if (code != 200) throw IllegalStateException("GitHub error HTTP $code")
        val o = JSONObject(conn.inputStream.bufferedReader().readText())
        val tag = o.optString("tag_name")
        val vc = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalStateException("Release tag \"$tag\" must look like build-3")
        val assets = o.optJSONArray("assets") ?: throw IllegalStateException("Release has no files")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
            ?: throw IllegalStateException("Release has no .apk file")
        return Release(vc, tag, apk.optString("url"), apk.optString("browser_download_url"), o.optString("body"))
    }

    /** Downloads and silently installs [r]. Returns null when the install was handed to Android, or an error. */
    fun install(c: Context, r: Release): String? = runCatching {
        val file = File(c.cacheDir, "update.apk")
        val tok = token(c)
        // Private repo: API asset URL + token, then follow the redirect WITHOUT the token (signed storage URL).
        var conn = if (tok != null) open(r.apiAssetUrl, tok, "application/octet-stream") else open(r.downloadUrl, null, "application/octet-stream")
        conn.instanceFollowRedirects = false
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location")
            conn = open(next, null, "application/octet-stream").apply { instanceFollowRedirects = false }
        }
        if (conn.responseCode != 200) return "Download failed (HTTP ${conn.responseCode})"
        conn.inputStream.use { inp -> file.outputStream().use { inp.copyTo(it) } }

        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val result = PendingIntent.getBroadcast(
                c, 7, Intent("com.devguard.mdm.UPDATE_RESULT").setPackage(c.packageName), PendingIntent.FLAG_IMMUTABLE,
            )
            s.commit(result.intentSender)
        }
        null
    }.getOrElse { it.message ?: "Update failed" }

    /** Called on screen on: at most once a day, if auto-update is on. */
    fun autoCheck(c: Context) {
        if (!Prefs.getFlag(c, Prefs.UPD_AUTO) || !Mdm(c).isOwner) return
        val now = System.currentTimeMillis()
        if (now - Prefs.getLong(c, Prefs.UPD_LAST) < 24 * 3600_000L) return
        Prefs.setLong(c, Prefs.UPD_LAST, now)
        val r = runCatching { latest(c) }.getOrNull() ?: return
        if (r.code > currentCode(c)) install(c, r)
    }
}
