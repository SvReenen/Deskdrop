// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * AES-256-GCM payload encryption for sync transport.
 * Format: base64(nonce[12] + ciphertext + tag[16])
 * Compatible with the C# PayloadEncryption implementation.
 */
object PayloadEncryption {

    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_SIZE = 32

    /** Derive a 256-bit encryption key from the bearer token using HKDF. */
    fun deriveKey(token: String): ByteArray {
        val ikm = token.toByteArray(Charsets.UTF_8)
        val info = "deskdrop-sync-v1".toByteArray(Charsets.UTF_8)
        return hkdfSha256(ikm, KEY_SIZE, info)
    }

    fun encrypt(plaintext: String, key: ByteArray): String {
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertextAndTag = cipher.doFinal(plaintextBytes)

        // nonce + ciphertext + tag (Java GCM appends tag to ciphertext)
        val result = ByteArray(NONCE_SIZE + ciphertextAndTag.size)
        nonce.copyInto(result, 0)
        ciphertextAndTag.copyInto(result, NONCE_SIZE)

        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    fun decrypt(encryptedBase64: String, key: ByteArray): String? {
        return try {
            val data = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (data.size < NONCE_SIZE + TAG_BITS / 8) return null

            val nonce = data.sliceArray(0 until NONCE_SIZE)
            val ciphertextAndTag = data.sliceArray(NONCE_SIZE until data.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            val plaintext = cipher.doFinal(ciphertextAndTag)

            String(plaintext, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Simple HKDF-SHA256 expand (no salt). */
    private fun hkdfSha256(ikm: ByteArray, length: Int, info: ByteArray): ByteArray {
        // Extract
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256")) // zero salt
        val prk = mac.doFinal(ikm)

        // Expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i: Byte = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(i))
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            t.copyInto(result, offset, 0, toCopy)
            offset += toCopy
            i++
        }
        return result
    }
}
