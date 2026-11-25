// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * S-03: secrets (NextDNS API key, GitHub token, two-factor secret) are stored ENCRYPTED in the prefs file.
 *
 * The AES-256-GCM key is created inside the phone's secure hardware (Android Keystore) and can't be exported,
 * so a copy of the prefs file (ADB `run-as`, a backup, a rooted read) only shows `enc1:…` gibberish.
 * Stored format: "enc1:" + Base64(12-byte IV + ciphertext + 16-byte tag).
 *
 * Old plain-text values (versions before 2026-11-25) are encrypted the first time they're read, or by [migrate].
 * If the app's data is wiped the Keystore key goes with it, so secrets must then be entered again.
 */
object Secrets {
    private const val ALIAS = "deviceguard_secrets_v1"
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Prefs keys whose values are secrets. */
    val KEYS = listOf(Prefs.NEXTDNS_KEY, Prefs.UPD_TOKEN, Prefs.TOTP_SECRET)

    /** True if a value is stored for [key] (even if it can't be decrypted). */
    fun has(c: Context, key: String) = !Prefs.getStr(c, key).isNullOrEmpty()

    /** Saves [value] encrypted, or removes it when null/blank. */
    @Synchronized
    fun put(c: Context, key: String, value: String?) {
        Prefs.setStr(c, key, value?.takeIf { it.isNotBlank() }?.let { PREFIX + encrypt(it) })
    }

    /** The decrypted value, or null if not set or it can't be decrypted. */
    @Synchronized
    fun get(c: Context, key: String): String? {
        val raw = Prefs.getStr(c, key)?.takeIf { it.isNotEmpty() } ?: return null
        if (!raw.startsWith(PREFIX)) {
            // Plain text from an older version: encrypt it now (the value itself is still returned).
            runCatching { put(c, key, raw) }
            return raw
        }
        return runCatching { decrypt(raw.removePrefix(PREFIX)) }.getOrNull()
    }

    /** Encrypts every plain-text secret left by older versions. Safe to call often. */
    fun migrate(c: Context) = KEYS.forEach { k ->
        if (Prefs.getStr(c, k)?.let { it.isNotEmpty() && !it.startsWith(PREFIX) } == true) get(c, k)
    }

    /** True if every stored secret is encrypted (for checks and tests). */
    fun allEncrypted(c: Context) = KEYS.all { k -> Prefs.getStr(c, k).let { it.isNullOrEmpty() || it.startsWith(PREFIX) } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(data: String): String {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_LEN))
        return String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
    }
}
