package dev.shield.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountriesTest {
    @Test
    fun detectsCountryNames() {
        assertEquals("NL", Countries.detect("Niederlande #1")?.code)
        assertEquals("NL", Countries.detect("netherlands 3")?.code)
        assertEquals("KR", Countries.detect("Südkorea")?.code)
        assertEquals("CH", Countries.detect("Schweiz Zürich")?.code)
        assertEquals("US", Countries.detect("USA New York")?.code)
        assertEquals("GB", Countries.detect("UK London")?.code)
    }

    @Test
    fun detectsCodesOnlyWhereTheyAreCodes() {
        assertEquals("NL", Countries.detect("wg-NL-FREE-12")?.code)
        assertEquals("DE", Countries.detect("de-1.conf")?.code)
        assertEquals("NL", Countries.detect("NL")?.code)
        assertNull(Countries.detect("mein vpn")) // "de" inside words is no country
        assertNull(Countries.detect("Dokument"))
        assertNull(Countries.detect(null))
    }

    @Test
    fun readsProtonServerComment() {
        val conf = "[Interface]\nPrivateKey = x\n\n[Peer]\n# NL-FREE#12\nPublicKey = y\n"
        assertEquals(ServerGuess(Countries.byCode("NL"), "Niederlande #12 (Free)"), Countries.fromProtonComment(conf))
        assertEquals("Niederlande #3", Countries.fromProtonComment("# NL#3")?.name)
        val sc = Countries.fromProtonComment("# CH-NL#1")
        assertEquals("NL", sc?.country?.code)
        assertEquals("Niederlande #1 (Secure Core)", sc?.name)
        // US state servers: "US-CA" is California, not Canada
        assertEquals("US", Countries.fromProtonComment("# US-CA#40")?.country?.code)
        assertNull(Countries.fromProtonComment("# Key for Shield"))
    }

    @Test
    fun neverUsesMeaninglessFileNames() {
        assertEquals("Niederlande #12 (Free)", Countries.guess("Dokument", "# NL-FREE#12", "1.2.3.4:51820").name)
        assertEquals("Deutschland", Countries.guess("wg-DE-1", "", "1.2.3.4:51820").let { it.country?.nameDe })
        assertEquals("1.2.3.4", Countries.guess("Dokument", "", "1.2.3.4:51820").name)
        assertEquals("Mein Server", Countries.guess("Mein Server", "", "1.2.3.4:51820").name)
    }

    @Test
    fun flagEmoji() {
        assertEquals("🇳🇱", Countries.flag("NL"))
        assertEquals("🇩🇪", Countries.flag("de"))
        assertEquals("🏳", Countries.flag("X1"))
    }
}
