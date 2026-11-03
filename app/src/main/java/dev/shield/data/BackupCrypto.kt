package dev.shield.data

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Password based encryption for backup files: PBKDF2-HMAC-SHA256 + AES-256-GCM.
 * Format: "SHLDBK1" | salt(16) | iv(12) | ciphertext+tag.
 */
object BackupCrypto {
    private val MAGIC = "SHLDBK1".toByteArray()
    private const val ITERATIONS = 150_000
    private const val SALT = 16
    private const val IV = 12

    fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        if (password.size < 8) throw BackupException("Passwort zu kurz (mindestens 8 Zeichen)")
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (data.size < MAGIC.size + SALT + IV + 16 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("Keine Shield-Sicherung")
        }
        var o = MAGIC.size
        val salt = data.copyOfRange(o, o + SALT); o += SALT
        val iv = data.copyOfRange(o, o + IV); o += IV
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return try {
            c.doFinal(data, o, data.size - o)
        } catch (e: AEADBadTagException) {
            throw BackupException("Falsches Passwort oder beschädigte Datei")
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
