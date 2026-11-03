package dev.shield.data

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun roundTrip() {
        val data = "{\"rules\":[]}".toByteArray()
        val enc = BackupCrypto.encrypt(data, "geheim123".toCharArray())
        assertArrayEquals(data, BackupCrypto.decrypt(enc, "geheim123".toCharArray()))
    }

    @Test(expected = BackupException::class)
    fun wrongPasswordFails() {
        val enc = BackupCrypto.encrypt("x".toByteArray(), "geheim123".toCharArray())
        BackupCrypto.decrypt(enc, "falsch123".toCharArray())
    }

    @Test(expected = BackupException::class)
    fun shortPasswordRejected() {
        BackupCrypto.encrypt("x".toByteArray(), "kurz".toCharArray())
    }

    @Test(expected = BackupException::class)
    fun garbageRejected() {
        BackupCrypto.decrypt(ByteArray(100), "geheim123".toCharArray())
    }
}
