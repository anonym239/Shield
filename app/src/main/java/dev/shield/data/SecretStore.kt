package dev.shield.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores secrets (WireGuard configs with private keys) AES-GCM encrypted with a key that never
 * leaves the Android Keystore.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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

    private fun file(name: String) = File(dir, name.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".bin")

    @Synchronized
    fun put(name: String, plain: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
        val f = file(name)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(out)
        tmp.renameTo(f)
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        val data = f.readBytes()
        val ivLen = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivLen))
        return c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }

    @Synchronized
    fun delete(name: String) {
        file(name).delete()
    }

    private companion object {
        const val ALIAS = "shield_secrets_v1"
    }
}
