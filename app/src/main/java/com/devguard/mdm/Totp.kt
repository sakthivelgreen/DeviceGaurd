// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Two-factor admin unlock (RFC 6238 TOTP, 30 s, 6 digits, SHA-1) — works with Zoho OneAuth,
 * Google Authenticator, Microsoft Authenticator. No server or internet needed.
 */
object Totp {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun enabled(c: Context) = Secrets.has(c, Prefs.TOTP_SECRET)

    fun newSecret(): String {
        val b = ByteArray(20).also { SecureRandom().nextBytes(it) }
        return base32(b)
    }

    fun enable(c: Context, secret: String) = Secrets.put(c, Prefs.TOTP_SECRET, secret) // encrypted (S-03)
    fun disable(c: Context) = Secrets.put(c, Prefs.TOTP_SECRET, null)

    fun uri(secret: String) =
        "otpauth://totp/DeviceGuard:Admin?secret=$secret&issuer=DeviceGuard&algorithm=SHA1&digits=6&period=30"

    /** Accepts the current code and one step either side (clock drift). */
    fun verify(secret: String, code: String): Boolean {
        val c = code.filter { it.isDigit() }
        if (c.length != 6) return false
        val step = System.currentTimeMillis() / 1000 / 30
        return (-1..1).any { code(secret, step + it) == c }
    }

    /** True if two-factor is off, or [code] is right. Fails closed: an unreadable secret rejects every code
     *  (the recovery code or the PC reset still turn two-factor off). */
    fun verify(ctx: Context, code: String): Boolean {
        if (!enabled(ctx)) return true
        val secret = Secrets.get(ctx, Prefs.TOTP_SECRET) ?: return false
        return verify(secret, code)
    }

    private fun code(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(unbase32(secret), "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        return "%06d".format(bin % 1_000_000)
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(B32[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun unbase32(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in s.uppercase().filter { it in B32 }) {
            buffer = (buffer shl 5) or B32.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}

/** QR code bitmap helper (zxing). */
object Qr {
    fun bitmap(text: String, size: Int = 720): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
