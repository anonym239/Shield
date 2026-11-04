@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.dashboard

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import dev.shield.data.ProtectionLevel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.R
import dev.shield.core.checks.Finding
import dev.shield.core.checks.SelfTestReport
import dev.shield.core.checks.StatusLevel
import dev.shield.core.checks.StatusReason
import dev.shield.core.checks.TestId
import dev.shield.core.firewall.NetType
import dev.shield.core.vpn.AutoVpnPolicy
import dev.shield.core.vpn.NetworkState
import dev.shield.core.vpn.TunnelPhase
import dev.shield.core.vpn.TunnelState
import dev.shield.data.TunnelMode
import dev.shield.ui.common.AppIcon
import dev.shield.ui.common.Format
import dev.shield.ui.common.Recommendations
import dev.shield.ui.common.StatusDot
import dev.shield.ui.common.statusIcon
import dev.shield.ui.common.statusText
import dev.shield.ui.nav.Routes
import dev.shield.ui.theme.BigNumber
import dev.shield.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenApp: (String) -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: (String) -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val tunnel by vm.tunnel.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val net by vm.network.collectAsStateWithLifecycle()
    val testing by vm.selfTestRunning.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showTests by rememberSaveable { mutableStateOf(false) }
    var confirmPanic by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.setProtection(true)
    }
    val toggle: (Boolean) -> Unit = { on ->
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 && !settings.quietMode) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            val intent = vm.controller.permissionIntent()
            if (intent != null) vpnPermission.launch(intent) else vm.setProtection(true)
        } else {
            vm.setProtection(false)
        }
    }

    val level = overview?.status?.level ?: if (tunnel.running) StatusLevel.LIMITED else StatusLevel.UNPROTECTED
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        val on = settings.protectionEnabled && tunnel.phase != TunnelPhase.STOPPED
        val reasons = overview?.status?.reasons.orEmpty()
        val checking = on && (tunnel.phase == TunnelPhase.STARTING || testing || StatusReason.SELFTEST_PENDING in reasons)
        PowerRing(level = level, on = on, checking = checking, panic = tunnel.panic, onToggle = { toggle(!on) })
        Spacer(Modifier.height(12.dp))
        val hint = when {
            !on -> stringResource(R.string.tap_to_enable)
            checking -> stringResource(R.string.reason_selftest_pending)
            else -> reasons.firstOrNull()?.let { stringResource(reasonText(it)) } ?: stringResource(R.string.tap_to_disable)
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(levelName(settings.protectionLevel)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp).clickable { onOpenSettings(Routes.PROTECTION) }.padding(8.dp),
        )
        Spacer(Modifier.height(20.dp))

        // 2x2 protection layer cards
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LayerCard(
                Modifier.weight(1f), Icons.Filled.Block, stringResource(R.string.layer_blocker),
                if (tunnel.running) StatusLevel.PROTECTED else StatusLevel.UNPROTECTED,
                Format.number(data.blockedToday), stringResource(R.string.blocked_today),
            ) { onOpenLog() }
            LayerCard(
                Modifier.weight(1f), Icons.Filled.VpnKey, stringResource(R.string.layer_vpn),
                vpnLevel(tunnel), vpnTitle(tunnel), vpnDetail(tunnel),
            ) { onOpenSettings(Routes.VPN) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LayerCard(
                Modifier.weight(1f), Icons.Filled.Apps, stringResource(R.string.layer_firewall),
                if (tunnel.running) StatusLevel.PROTECTED else StatusLevel.UNPROTECTED,
                Format.number(data.firewallBlocked),
                stringResource(if (tunnel.mode == TunnelMode.FULL) R.string.apps_blocked_packet else R.string.apps_blocked_dns),
            ) { onOpenSettings(Routes.APPS) }
            LayerCard(
                Modifier.weight(1f), Icons.Filled.Wifi, stringResource(R.string.layer_network),
                networkLevel(net, tunnel), networkTitle(net), networkDetail(net, tunnel),
            ) { onOpenSettings(Routes.CHECKS) }
        }
        if (data.phishingToday > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.phishing_blocked, data.phishingToday),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        SelfTestRow(overview?.selfTest, testing, onRun = vm::runSelfTest, onOpen = { showTests = true })

        Spacer(Modifier.height(28.dp))
        SectionHeader(stringResource(R.string.chart_title))
        HourChart(data.hours)
        Text(
            stringResource(R.string.chart_summary, Format.number(data.queriesToday), Format.number(data.blockedToday)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        Spacer(Modifier.height(28.dp))
        SectionHeader(stringResource(R.string.top_apps))
        if (data.topApps.isEmpty()) {
            Hint(stringResource(R.string.no_data_yet))
        }
        data.topApps.forEach { a ->
            val (pkg, label) = vm.apps.resolve(a.uid)
            TopRow(
                leading = { AppIcon(vm.apps, pkg, label, 36) },
                title = label,
                value = Format.number(a.blocked),
                onClick = { onOpenApp(pkg) },
            )
        }
        Spacer(Modifier.height(20.dp))
        SectionHeader(stringResource(R.string.top_domains))
        if (data.topDomains.isEmpty()) {
            Hint(stringResource(if (settings.noHistory) R.string.no_history_hint else R.string.no_data_yet))
        }
        data.topDomains.forEach { d ->
            TopRow(leading = null, title = d.domain, value = Format.number(d.count), onClick = onOpenLog)
        }

        Spacer(Modifier.height(28.dp))
        overview?.let { o ->
            SectionHeader(stringResource(R.string.recommendations))
            Text(
                stringResource(R.string.score_value, o.score.score),
                style = BigNumber,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.score_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (o.score.topFindings.isEmpty()) Hint(stringResource(R.string.no_recommendations))
            o.score.topFindings.forEach { f -> RecommendationRow(f, vm, onOpenSettings) }
        }

        val ai by vm.aiVerdict.collectAsStateWithLifecycle()
        if (settings.aiEnabled) ai?.let {
            Spacer(Modifier.height(12.dp))
            dev.shield.ui.settings.AiVerdictCard(it)
        }
        Spacer(Modifier.height(28.dp))
        if (tunnel.running) {
            OutlinedButton(
                onClick = { if (tunnel.panic) vm.setPanic(false) else confirmPanic = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.PanTool, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (tunnel.panic) R.string.panic_off else R.string.panic_button))
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showTests) {
        ModalBottomSheet(onDismissRequest = { showTests = false }) {
            SelfTestList(overview?.selfTest)
        }
    }
    if (confirmPanic) {
        AlertDialog(
            onDismissRequest = { confirmPanic = false },
            title = { Text(stringResource(R.string.panic_button)) },
            text = { Text(stringResource(R.string.panic_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPanic = false
                    vm.setPanic(true)
                }) { Text(stringResource(R.string.panic_do)) }
            },
            dismissButton = { TextButton(onClick = { confirmPanic = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PowerRing(level: StatusLevel, on: Boolean, checking: Boolean, panic: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val colors = LocalStatusColors.current
    val target = when {
        !on -> MaterialTheme.colorScheme.outline
        checking -> MaterialTheme.colorScheme.primary
        else -> colors.of(level)
    }
    val ringColor by animateColorAsState(target, tween(600), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val inf = rememberInfiniteTransition(label = "power")
    val sweep by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    val breathe by inf.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val glow = if (on && !checking) breathe else 1f
    val stateText = when {
        panic -> stringResource(R.string.status_panic)
        !on -> stringResource(R.string.off)
        checking -> stringResource(R.string.status_checking)
        else -> statusText(level)
    }
    Box(
        Modifier
            .size(236.dp)
            .clip(CircleShape)
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics {
                contentDescription = ctx.getString(R.string.cd_protection_switch)
                stateDescription = stateText
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val w = 16.dp.toPx()
            drawCircle(track, style = Stroke(w))
            if (checking) {
                drawArc(ringColor, sweep - 90f, 100f, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
            } else if (on) {
                drawCircle(ringColor.copy(alpha = 0.12f * glow), radius = size.minDimension / 2 - w)
                drawArc(ringColor, -90f, 360f, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size((96 * glow).dp)
                    .clip(CircleShape)
                    .background(if (on) ringColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        !on -> Icons.Filled.PowerSettingsNew
                        checking -> Icons.Filled.Shield
                        level == StatusLevel.PROTECTED -> Icons.Filled.Shield
                        else -> statusIcon(level)
                    },
                    contentDescription = null,
                    tint = if (on) ringColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(stateText, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

fun levelName(l: ProtectionLevel): Int = when (l) {
    ProtectionLevel.STANDARD -> R.string.level_standard
    ProtectionLevel.STRICT -> R.string.level_strict
    ProtectionLevel.MAXIMUM -> R.string.level_max
}

@Composable
private fun LayerCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    level: StatusLevel?,
    big: String,
    detail: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.heightIn(min = 132.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (level != null) {
                    StatusDot(level)
                } else {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = stringResource(R.string.not_configured),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(big, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelfTestRow(report: SelfTestReport?, running: Boolean, onRun: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.selftest), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (report == null) {
                        stringResource(R.string.selftest_never)
                    } else {
                        stringResource(
                            R.string.selftest_summary,
                            Format.ago(ctx, report.time),
                            report.passedCount,
                            report.applicable.size,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                FilledTonalButton(onClick = onRun, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.check_now))
                }
            }
        }
    }
}

@Composable
fun SelfTestList(report: SelfTestReport?) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        Text(stringResource(R.string.selftest), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (report == null) {
            Text(stringResource(R.string.selftest_never))
            return
        }
        report.results.forEach { r ->
            val level = when (r.passed) {
                true -> StatusLevel.PROTECTED
                false -> StatusLevel.UNPROTECTED
                null -> StatusLevel.LIMITED
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (r.passed) { true -> "✓"; false -> "✗"; null -> "–" },
                    style = MaterialTheme.typography.titleLarge,
                    color = LocalStatusColors.current.of(level),
                    modifier = Modifier.width(32.dp),
                )
                Column {
                    Text(stringResource(testName(r.id)), style = MaterialTheme.typography.bodyLarge)
                    Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun testName(id: TestId): Int = when (id) {
    TestId.TUNNEL -> R.string.test_tunnel
    TestId.SYSTEM_DNS -> R.string.test_system_dns
    TestId.TEST_DOMAIN -> R.string.test_domain
    TestId.UPSTREAM -> R.string.test_upstream
    TestId.LISTS -> R.string.test_lists
    TestId.VPN -> R.string.test_vpn
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun TopRow(leading: (@Composable () -> Unit)?, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RecommendationRow(f: Finding, vm: DashboardViewModel, onOpenSettings: (String) -> Unit) {
    val ctx = LocalContext.current
    val sys = Recommendations.systemIntent(f)
    val action: (() -> Unit)? = when {
        sys != null -> { { Recommendations.open(ctx, sys) } }
        f == Finding.LISTS_OUTDATED -> { { vm.updateLists() } }
        f == Finding.OPEN_WIFI_WITHOUT_VPN || f == Finding.KILL_SWITCH_OFF -> { { onOpenSettings(Routes.VPN) } }
        f == Finding.RISKY_APPS || f == Finding.CAPTIVE_PORTAL || f == Finding.DNS_LEAK -> { { onOpenSettings(Routes.CHECKS) } }
        f == Finding.PHISHING_OFF -> { { onOpenSettings(Routes.PROTECTION) } }
        f == Finding.SELFTEST_FAILED -> { { vm.runSelfTest() } }
        else -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { if (action != null) it.clickable(onClick = action) else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Recommendations.text(f)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun reasonText(r: StatusReason): Int = when (r) {
    StatusReason.TUNNEL_OFF -> R.string.reason_tunnel_off
    StatusReason.TUNNEL_ERROR -> R.string.reason_tunnel_error
    StatusReason.SELFTEST_FAILED -> R.string.reason_selftest_failed
    StatusReason.PANIC -> R.string.reason_panic
    StatusReason.LISTS_MISSING -> R.string.reason_lists_missing
    StatusReason.LISTS_OUTDATED -> R.string.reason_lists_outdated
    StatusReason.VPN_DISCONNECTED -> R.string.reason_vpn_disconnected
    StatusReason.OPEN_WIFI_NO_VPN -> R.string.reason_open_wifi
    StatusReason.SELFTEST_PENDING -> R.string.reason_selftest_pending
}

/** null = no VPN profile set up (neutral, not a warning). */
private fun vpnLevel(t: TunnelState): StatusLevel? = when {
    !t.wgWanted -> null
    !t.running -> StatusLevel.UNPROTECTED
    t.wgConnected -> StatusLevel.PROTECTED
    else -> StatusLevel.LIMITED
}

@Composable
private fun vpnTitle(t: TunnelState): String = when {
    t.wgWanted -> t.wgProfileName ?: stringResource(R.string.layer_vpn)
    else -> stringResource(R.string.not_configured)
}

@Composable
private fun vpnDetail(t: TunnelState): String {
    val ctx = LocalContext.current
    return when {
        t.wgWanted && t.wgConnected -> stringResource(R.string.vpn_connected_since, Format.duration(ctx, t.wgSince))
        t.wgWanted -> stringResource(R.string.vpn_connecting)
        else -> stringResource(R.string.vpn_setup_hint)
    }
}

private fun networkLevel(n: NetworkState, t: TunnelState): StatusLevel = when {
    n.type == NetType.NONE -> StatusLevel.LIMITED
    n.type == NetType.WIFI && AutoVpnPolicy.isOpen(n.security) && !t.wgConnected -> StatusLevel.LIMITED
    n.captivePortal -> StatusLevel.LIMITED
    else -> StatusLevel.PROTECTED
}

@Composable
private fun networkTitle(n: NetworkState): String = when (n.type) {
    NetType.WIFI -> stringResource(R.string.net_wifi)
    NetType.MOBILE -> stringResource(R.string.net_mobile)
    NetType.OTHER -> stringResource(R.string.net_other)
    NetType.NONE -> stringResource(R.string.net_none)
}

@Composable
private fun networkDetail(n: NetworkState, t: TunnelState): String = when {
    n.type == NetType.WIFI && AutoVpnPolicy.isOpen(n.security) ->
        stringResource(if (t.wgConnected) R.string.net_open_vpn else R.string.net_open_insecure)
    n.type == NetType.WIFI -> stringResource(R.string.net_wifi_secure, n.security.name)
    n.type == NetType.MOBILE -> stringResource(R.string.net_mobile_detail)
    else -> ""
}
