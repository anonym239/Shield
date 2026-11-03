package dev.shield.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun removesWireGuardKeys() {
        val key = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        val out = Redactor.redact("PrivateKey = $key and private_key=c809f3e5317e95c96dd7b5e2b638b7ce530dabe85ddab614220241801ddf0669")
        assertFalse(out.contains(key))
        assertFalse(out.contains("c809f3e5317e95"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun removesBareKeysAndTokens() {
        assertFalse(Redactor.redact("peer xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg= ok").contains("xTIBA5"))
        assertFalse(Redactor.redact("token: abcdef").contains("abcdef"))
    }
}
