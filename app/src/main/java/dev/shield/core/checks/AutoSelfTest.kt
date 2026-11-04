package dev.shield.core.checks

import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.vpn.VpnController
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the self-test automatically whenever the tunnel (re)starts or the VPN connects, so the
 * dashboard only shows "Geschützt" after every layer was actually verified.
 */
@Singleton
class AutoSelfTest @Inject constructor(
    private val controller: VpnController,
    private val runner: SelfTestRunner,
    private val blocklists: BlocklistRepository,
    private val settings: SettingsRepository,
    private val events: EventLogger,
) {
    private val healer = HealPolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            controller.state
                .map { st -> if (st.running && !st.panic) Triple(st.startedAt, st.mode, st.wgConnected) else null }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) return@collectLatest
                    // Android applies the VPN's DNS servers asynchronously
                    delay(SETTLE_MS)
                    // first start: wait for the blocklist download
                    withTimeoutOrNull(LISTS_WAIT_MS) { blocklists.state.first { !it.updating } }
                    try {
                        heal(runner.run(full = false))
                    } catch (e: Exception) {
                        ShieldLog.e("AutoSelfTest", "self-test failed to run", e)
                    }
                }
        }
    }

    /**
     * Self-healing: repairs what the self-test found broken (at most [HealPolicy.MAX_PER_HOUR]
     * times per hour so a permanent problem cannot cause a restart loop).
     */
    suspend fun heal(report: SelfTestReport) {
        if (report.allPassed) {
            healer.reset()
            return
        }
        val s = settings.current()
        if (!s.autoHeal || !s.protectionEnabled || s.panic) return
        val failed = report.results.filter { it.passed == false }.map { it.id }.toSet()
        val action = HealPolicy.actionFor(failed) ?: return
        if (!healer.allow(System.currentTimeMillis())) {
            events.critical(EventType.SELFTEST_FAILED, "Selbstheilung pausiert: Problem besteht weiter (${failed.joinToString()})")
            return
        }
        when (action) {
            HealAction.UPDATE_LISTS -> {
                events.warn(EventType.SELFTEST_FAILED, "Selbstheilung: Blocklisten werden neu geladen")
                blocklists.update(force = true)
                heal(runner.run(full = false))
            }
            HealAction.RESTART_TUNNEL -> {
                events.warn(EventType.SELFTEST_FAILED, "Selbstheilung: Tunnel wird neu verbunden (${failed.joinToString()})")
                controller.restart()
                delay(RESTART_WAIT_MS)
                withTimeoutOrNull(30_000) { controller.state.first { it.running } }
                delay(SETTLE_MS)
                heal(runner.run(full = false))
            }
        }
    }

    companion object {
        const val SETTLE_MS = 2500L
        const val LISTS_WAIT_MS = 90_000L
        const val RESTART_WAIT_MS = 1500L
    }
}
