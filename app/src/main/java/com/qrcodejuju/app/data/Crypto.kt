package com.qrcodejuju.app.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The AES-256 key used to encrypt data before it's uploaded to Google Drive, plus the
 * AES-GCM helpers to use it. The key lives in EncryptedSharedPreferences, which wraps it
 * with an Android Keystore-backed master key at rest on this device -- but unlike a
 * Keystore key, the raw bytes can be read back out, because they need to travel to your
 * other phone (as a QR code or recovery text). Google never receives this key; only the
 * ciphertext it protects reaches Drive.
 */
object SyncCrypto {
    private const val PREFS_NAME = "jujucodes_sync_secure"
    private const val KEY_PREF = "drive_sync_key"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hasKey(context: Context): Boolean = prefs(context).contains(KEY_PREF)

    /** Returns the current key, generating a new random one the first time this is called. */
    fun getOrCreateKey(context: Context): ByteArray {
        val p = prefs(context)
        p.getString(KEY_PREF, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        p.edit().putString(KEY_PREF, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        return key
    }

    /** Replaces the key, e.g. after scanning a "link this device" QR code from another phone. */
    fun setKey(context: Context, key: ByteArray) {
        prefs(context).edit().putString(KEY_PREF, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    fun clearKey(context: Context) {
        prefs(context).edit().remove(KEY_PREF).apply()
    }

    /** Output is `iv || ciphertext+tag`, ready to upload as-is. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "Encrypted data is too short" }
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size))
    }
}

/**
 * Encodes/decodes the sync key as text so it can travel as a QR code (scanned with this
 * app's existing Scan tab) or be shown/typed as a recovery string.
 */
object DeviceLink {
    private const val PREFIX = "jujucodes-link:v1:"

    fun encode(key: ByteArray): String = PREFIX + Base64.encodeToString(key, Base64.NO_WRAP)

    fun isLinkCode(text: String): Boolean = text.startsWith(PREFIX)

    fun decode(text: String): ByteArray? =
        if (isLinkCode(text)) runCatching { Base64.decode(text.removePrefix(PREFIX), Base64.NO_WRAP) }.getOrNull()
        else null
}
