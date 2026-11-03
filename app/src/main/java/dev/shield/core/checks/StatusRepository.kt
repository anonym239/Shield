package dev.shield.core.checks

import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.vpn.AutoVpnPolicy
import dev.shield.core.vpn.NetworkMonitor
import dev.shield.core.vpn.TunnelPhase
import dev.shield.core.vpn.VpnController
import dev.shield.core.firewall.NetType
import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class Overview(
    val status: StatusResult,
    val score: ScoreResult,
    val findings: List<Finding>,
    val selfTest: SelfTestReport?,
)

/** Combines tunnel, self-test, lists, network and device checks into status + score. */
@Singleton
class StatusRepository @Inject constructor(
    private val controller: VpnController,
    private val settings: SettingsRepository,
    private val blocklists: BlocklistRepository,
    private val network: NetworkMonitor,
    private val checks: DeviceChecks,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = MutableStateFlow<DeviceReport?>(null)
    private val unusualApps = MutableStateFlow(0)
    val lastLeak = MutableStateFlow<LeakResult?>(null)

    val selfTest: Flow<SelfTestReport?> = settings.selfTestJson.map { SelfTestReport.fromJson(it) }

    fun refreshDeviceChecks() {
        scope.launch {
            device.value = runCatching { checks.device() }.getOrNull()
            unusualApps.value = runCatching { checks.permissions().count { it.unusual } }.getOrDefault(0)
        }
    }

    private val statusInput: Flow<Pair<StatusInput, SelfTestReport?>> =
        combine(controller.state, selfTest, blocklists.state, network.state) { t, st, lists, net ->
            val passed = st?.takeIf { t.running && it.time >= t.startedAt }?.allPassed
            val age = if (lists.oldestUpdate > 0) (System.currentTimeMillis() - lists.oldestUpdate) / 3_600_000 else Long.MAX_VALUE
            StatusInput(
                tunnelRunning = t.running,
                tunnelError = t.phase == TunnelPhase.ERROR,
                panic = t.panic,
                lastSelfTestPassed = passed,
                listsLoaded = lists.totalRules > 0,
                listsAgeHours = age,
                wgWanted = t.wgWanted,
                wgConnected = t.wgConnected,
                openWifi = net.type == NetType.WIFI && AutoVpnPolicy.isOpen(net.security),
            ) to st
        }

    val overview: StateFlow<Overview?> = combine(
        statusInput, device, unusualApps, settings.settings, lastLeak,
    ) { (input, st), dev, unusual, s, leak ->
        val t = controller.state.value
        val findings = ProtectionStatus.findings(
            FindingInput(
                status = input,
                screenLock = dev?.screenLock ?: true,
                patchAgeDays = dev?.patchAgeDays,
                adb = dev?.adb ?: false,
                devOptions = dev?.devOptions ?: false,
                installerApps = dev?.installerApps?.size ?: 0,
                unusualApps = unusual,
                alwaysOn = t.alwaysOn,
                phishingEnabled = s.enabledLists.any { it in BlocklistRepository.PHISHING_IDS },
                killSwitch = s.killSwitch,
                dnsLeak = leak?.dnsLeak ?: false,
                captivePortal = network.state.value.captivePortal,
            ),
        )
        Overview(ProtectionStatus.evaluate(input), SecurityScore.compute(findings), findings, st)
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
