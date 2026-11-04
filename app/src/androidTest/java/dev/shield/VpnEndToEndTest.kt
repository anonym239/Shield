package dev.shield

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.shield.core.checks.TestId
import dev.shield.core.filter.FilterEngine
import dev.shield.core.vpn.TunnelPhase
import dev.shield.data.ProtectionLevel
import dev.shield.data.TunnelMode
import dev.shield.di.TestHooks
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * End-to-end test on a real (emulated) device: starts the actual VPN service and checks that
 * blocking, DoH resolution, the self-test, the full tunnel (Go core) and panic mode work.
 */
@RunWith(AndroidJUnit4::class)
class VpnEndToEndTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hooks = EntryPointAccessors.fromApplication(ctx, TestHooks::class.java)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    @Before
    fun grantVpn() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("appops set ${ctx.packageName} ACTIVATE_VPN allow").let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals("VPN consent could not be granted", null, VpnService.prepare(ctx))
    }

    @After
    fun stop() = runBlocking {
        hooks.controller().setPanic(false)
        hooks.controller().setProtection(false)
        hooks.settings().update { ProtectionLevel.STANDARD.applyTo(it).copy(tunnelMode = TunnelMode.DNS_ONLY) }
    }

    private suspend fun awaitRunning(mode: TunnelMode) = withTimeout(30_000) {
        while (true) {
            val st = hooks.controller().state.value
            if (st.phase == TunnelPhase.RUNNING && st.mode == mode && hooks.controller().tunnelInterfaceUp()) break
            assertFalse("tunnel error: ${st.error}", st.phase == TunnelPhase.ERROR)
            delay(250)
        }
        delay(1500) // Android applies the new DNS servers asynchronously
    }

    private fun lookup(name: String): List<InetAddress> = try {
        InetAddress.getAllByName(name).toList()
    } catch (e: UnknownHostException) {
        emptyList()
    }

    private fun unique(base: String) = "e2e${System.nanoTime() % 1_000_000_000}.$base"

    private fun isBlockedAnswer(a: List<InetAddress>) = a.isEmpty() || a.all { it.isAnyLocalAddress }

    private suspend fun checkDnsFiltering() {
        // own block rule wins over everything
        assertTrue(hooks.rules().setCustomRule("blocked-by-shield-e2e.com", allow = false))
        withTimeout(5000) { while (!hooks.filter().decide("blocked-by-shield-e2e.com").blocked) delay(100) }
        assertTrue("custom rule not applied", isBlockedAnswer(lookup(unique("blocked-by-shield-e2e.com"))))
        assertTrue("selftest domain not blocked", isBlockedAnswer(lookup(unique(FilterEngine.SELFTEST_DOMAIN))))
        // allowed domain is resolved via DoH
        var ok = lookup("example.com")
        // absorbs network outages of the CI emulator (seen: both DoH servers unreachable for ~1 min)
        for (attempt in 1..12) {
            if (ok.isNotEmpty()) break
            delay(5000)
            ok = lookup("example.com")
        }
        if (ok.isEmpty()) {
            hooks.recorder().flush()
            val recent = hooks.database().dnsQueryDao().since(0, 20).joinToString { "${it.domain}=${it.result}/${it.rule}" }
            val log = dev.shield.logging.ShieldLog.lines.value.takeLast(15).joinToString("\n") { "${it.tag}: ${it.message}" }
            throw AssertionError("example.com not resolved. queries: $recent\nlog:\n$log")
        }
        assertFalse("example.com wrongly blocked", ok.all { it.isAnyLocalAddress })
        hooks.rules().removeCustomRule("blocked-by-shield-e2e.com")
    }

    private fun httpOk(url: String): Boolean = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code in 200..399 }
    } catch (e: IOException) {
        false
    }

    @Test
    fun stageA_dnsOnly() = runBlocking {
        hooks.settings().update {
            ProtectionLevel.STRICT.applyTo(it).copy(tunnelMode = TunnelMode.DNS_ONLY, panic = false, autoHeal = false)
        }
        hooks.controller().setProtection(true)
        awaitRunning(TunnelMode.DNS_ONLY)
        checkDnsFiltering()
        // level "Streng": built-in telemetry list (offline) blocks Firebase logging
        withTimeout(20_000) { while (!hooks.filter().decide("firebaselogging-pa.googleapis.com").blocked) delay(200) }
        assertTrue("telemetry not blocked", isBlockedAnswer(lookup(unique("firebaselogging-pa.googleapis.com"))))

        val report = hooks.selfTest().run(full = false)
        val byId = report.results.associateBy { it.id }
        listOf(TestId.TUNNEL, TestId.SYSTEM_DNS, TestId.TEST_DOMAIN, TestId.UPSTREAM).forEach { id ->
            assertEquals("self-test $id: ${byId[id]?.detail}", true, byId[id]?.passed)
        }
        assertEquals(null, byId[TestId.VPN]?.passed) // no WireGuard profile
        assertTrue("events recorded", hooks.database().securityEventDao().since(0).isNotEmpty())
    }

    @Test
    fun stageB_fullTunnelAndPanic() = runBlocking {
        hooks.settings().update { it.copy(tunnelMode = TunnelMode.FULL, panic = false, autoHeal = false) }
        hooks.controller().setProtection(true)
        awaitRunning(TunnelMode.FULL)
        checkDnsFiltering()
        assertTrue("HTTPS through the Go core failed", httpOk("https://example.com/"))
        assertTrue("upstream probe failed", hooks.controller().probeUpstream().isSuccess)

        hooks.controller().setPanic(true)
        withTimeout(15_000) { while (!hooks.controller().state.value.panic) delay(200) }
        delay(1000)
        assertFalse("traffic passed in panic mode", httpOk("https://example.org/"))
        hooks.controller().setPanic(false)
        awaitRunning(TunnelMode.FULL)
        assertNotEquals(TunnelPhase.ERROR, hooks.controller().state.value.phase)
    }
}
