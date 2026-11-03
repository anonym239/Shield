@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.debug

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.R
import dev.shield.core.checks.SelfTestRunner
import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.filter.FilterEngine
import dev.shield.core.vpn.TunnelState
import dev.shield.core.vpn.VpnController
import dev.shield.logging.LogExporter
import dev.shield.logging.LogLevel
import dev.shield.logging.LogLine
import dev.shield.logging.ShieldLog
import dev.shield.ui.common.Format
import dev.shield.ui.common.KeyValueRow
import dev.shield.ui.common.SectionTitle
import dev.shield.ui.common.SubScreenBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProcessInfo(val pssKb: Long, val heapKb: Long, val cpuMs: Long, val uptimeMs: Long)

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val controller: VpnController,
    private val filter: FilterEngine,
    private val blocklists: BlocklistRepository,
    private val selfTest: SelfTestRunner,
    private val exporter: LogExporter,
) : ViewModel() {
    val tunnel: StateFlow<TunnelState> = controller.state
    val lines: StateFlow<List<LogLine>> = ShieldLog.lines
    val latency = MutableStateFlow<String>("–")
    val process = MutableStateFlow(ProcessInfo(0, 0, 0, 0))

    val ruleCount: Int get() = filter.ruleCount

    init {
        viewModelScope.launch {
            while (true) {
                val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val rt = Runtime.getRuntime()
                process.value = ProcessInfo(
                    mi.totalPss.toLong(),
                    (rt.totalMemory() - rt.freeMemory()) / 1024,
                    Process.getElapsedCpuTime(),
                    SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
                )
                delay(3000)
            }
        }
    }

    fun measureLatency() = viewModelScope.launch {
        latency.value = controller.probeUpstream().fold({ "$it ms" }, { "Fehler: ${it.message}" })
    }

    fun runSelfTest() = viewModelScope.launch { selfTest.run(full = false) }
    fun reloadLists() = viewModelScope.launch { blocklists.update(force = true) }
    fun restartTunnel() = controller.restart()
    fun clearLogs() = viewModelScope.launch { exporter.clearAll() }

    fun export(includeDomains: Boolean, ctx: Context) = viewModelScope.launch {
        val intent = exporter.buildShareIntent(includeDomains)
        ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.export_log)))
    }
}

@Composable
fun DebugScreen(onBack: () -> Unit, vm: DebugViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val t by vm.tunnel.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val proc by vm.process.collectAsStateWithLifecycle()
    var level by rememberSaveable { mutableStateOf(LogLevel.DEBUG) }
    var includeDomains by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.measureLatency() }

    Scaffold(topBar = { SubScreenBar(stringResource(R.string.set_debug), onBack) }) { pad ->
        val shown = lines.filter { it.level.priority >= level.priority }.asReversed()
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                SectionTitle(stringResource(R.string.debug_status))
                KeyValueRow(stringResource(R.string.debug_phase), t.phase.name + if (t.panic) " (PANIK)" else "")
                KeyValueRow(stringResource(R.string.debug_mode), t.mode.name)
                KeyValueRow(stringResource(R.string.debug_interface), "tun · MTU ${t.mtu}")
                KeyValueRow(stringResource(R.string.debug_addresses), t.addresses.joinToString().ifEmpty { "–" })
                KeyValueRow(stringResource(R.string.debug_routes), t.routes.joinToString().ifEmpty { "–" })
                KeyValueRow(stringResource(R.string.debug_dns), t.dnsServers.joinToString().ifEmpty { "–" })
                KeyValueRow(stringResource(R.string.debug_upstream), t.upstream.ifEmpty { "–" })
                KeyValueRow(stringResource(R.string.debug_latency), latency)
                KeyValueRow(stringResource(R.string.debug_rules), Format.number(vm.ruleCount))
                KeyValueRow(stringResource(R.string.debug_wg), if (t.wgWanted) "${t.wgProfileName} · ${if (t.wgConnected) "verbunden" else "getrennt"} · Handshake ${t.handshakeAgeSec}s" else "–")
                KeyValueRow(stringResource(R.string.debug_kill_switch), if (t.killSwitch) "an" else "aus")
                KeyValueRow(stringResource(R.string.debug_always_on), "${t.alwaysOn} / Lockdown ${t.lockdown}")
                KeyValueRow(stringResource(R.string.debug_core), shieldcore.Shieldcore.version())
                if (t.coreStats.isNotEmpty()) {
                    Text(t.coreStats, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
                KeyValueRow(stringResource(R.string.debug_memory), "PSS ${proc.pssKb / 1024} MB · Heap ${proc.heapKb / 1024} MB")
                KeyValueRow(stringResource(R.string.debug_cpu), "${proc.cpuMs / 1000} s CPU in ${proc.uptimeMs / 60000} min")
                Text(
                    stringResource(R.string.debug_battery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = vm::runSelfTest, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.run_selftest)) }
                    OutlinedButton(onClick = vm::reloadLists, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reload_lists)) }
                    OutlinedButton(onClick = vm::restartTunnel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.restart_tunnel)) }
                }
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDomains, onCheckedChange = { includeDomains = it })
                    Text(stringResource(R.string.export_include_domains), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.export(includeDomains, ctx) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.export_log)) }
                    OutlinedButton(onClick = vm::clearLogs, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.clear_logs)) }
                }
                SectionTitle(stringResource(R.string.live_log))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LogLevel.entries.forEach { l ->
                        FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.name) })
                    }
                }
            }
            items(shown.take(1000)) { l ->
                Text(
                    "${Format.time(l.time)} ${l.level.short}/${l.tag}: ${l.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when (l.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                )
            }
        }
    }
}
