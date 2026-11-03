@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.R
import dev.shield.core.dns.DnsCodec
import dev.shield.data.db.DnsQueryEntity
import dev.shield.ui.common.AppIcon
import dev.shield.ui.common.EmptyHint
import dev.shield.ui.common.Format
import dev.shield.ui.common.KeyValueRow
import dev.shield.ui.theme.LocalStatusColors

@Composable
fun LogScreen(vm: LogViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.log_tab_queries)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.log_tab_events)) })
        }
        if (tab == 0) QueryList(vm) else EventList(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueryList(vm: LogViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val queries by vm.queries.collectAsStateWithLifecycle()
    val appsInLog by vm.appsInLog.collectAsStateWithLifecycle()
    val noHistory by vm.noHistory.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<DnsQueryEntity?>(null) }
    var appMenu by remember { mutableStateOf(false) }
    val colors = LocalStatusColors.current

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = filter.search,
                onValueChange = { vm.filter.value = filter.copy(search = it) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_domains)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResultFilter.entries.forEach { r ->
                    FilterChip(
                        selected = filter.result == r,
                        onClick = { vm.filter.value = filter.copy(result = r) },
                        label = {
                            Text(
                                stringResource(
                                    when (r) {
                                        ResultFilter.ALL -> R.string.filter_all
                                        ResultFilter.BLOCKED -> R.string.filter_blocked
                                        ResultFilter.ALLOWED -> R.string.filter_allowed
                                    },
                                ),
                            )
                        },
                    )
                }
                Column {
                    FilterChip(
                        selected = filter.uid != null,
                        onClick = { if (filter.uid != null) vm.filter.value = filter.copy(uid = null) else appMenu = true },
                        label = {
                            Text(
                                filter.uid?.let { uid -> appsInLog.firstOrNull { it.first == uid }?.second ?: vm.apps.resolve(uid).second }
                                    ?: stringResource(R.string.filter_per_app),
                            )
                        },
                    )
                    DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                        appsInLog.forEach { (uid, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                appMenu = false
                                vm.filter.value = filter.copy(uid = uid)
                            })
                        }
                    }
                }
            }
            if (noHistory) EmptyHint(stringResource(R.string.no_history_hint))
        }
        if (queries.isEmpty() && !noHistory) item { EmptyHint(stringResource(R.string.no_queries)) }
        items(queries, key = { it.id }) { q ->
            val blocked = q.result == "BLOCKED"
            val pkg = vm.apps.resolve(q.uid).first
            ListItem(
                leadingContent = { AppIcon(vm.apps, pkg, q.app, 36) },
                overlineContent = { Text("${Format.time(q.time)} · ${q.app}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                headlineContent = { Text(q.domain, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        (if (blocked) stringResource(R.string.result_blocked) else if (q.result == "ERROR") stringResource(R.string.result_error) else stringResource(R.string.result_allowed)) +
                            (q.rule?.let { " · $it" } ?: ""),
                        color = when (q.result) {
                            "BLOCKED" -> colors.red
                            "ERROR" -> colors.yellow
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Icon(
                        if (blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                        contentDescription = stringResource(if (blocked) R.string.result_blocked else R.string.result_allowed),
                        tint = if (blocked) colors.red else colors.green,
                    )
                },
                modifier = Modifier.clickable { selected = q },
            )
        }
    }

    selected?.let { q ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(q.domain, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                KeyValueRow(stringResource(R.string.detail_time), Format.dateTime(q.time) + " (" + Format.time(q.time) + ")")
                KeyValueRow(stringResource(R.string.detail_app), q.app)
                KeyValueRow(stringResource(R.string.detail_type), DnsCodec.typeName(q.type))
                KeyValueRow(stringResource(R.string.detail_result), q.result)
                KeyValueRow(stringResource(R.string.detail_rule), q.rule ?: "–")
                KeyValueRow(stringResource(R.string.detail_latency), "${q.latencyMs} ms")
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        vm.block(q.domain)
                        selected = null
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_block)) }
                    OutlinedButton(onClick = {
                        vm.allow(q.domain)
                        selected = null
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_allow)) }
                }
            }
        }
    }
}

@Composable
private fun EventList(vm: LogViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    val colors = LocalStatusColors.current
    LazyColumn(Modifier.fillMaxSize()) {
        if (events.isEmpty()) item { EmptyHint(stringResource(R.string.no_events)) }
        items(events, key = { it.id }) { e ->
            val (icon, tint, desc) = when (e.severity) {
                "CRITICAL" -> Triple(Icons.Filled.Error, colors.red, R.string.severity_critical)
                "WARNING" -> Triple(Icons.Filled.Warning, colors.yellow, R.string.severity_warning)
                else -> Triple(Icons.Filled.Info, MaterialTheme.colorScheme.primary, R.string.severity_info)
            }
            ListItem(
                leadingContent = { Icon(icon, contentDescription = stringResource(desc), tint = tint) },
                overlineContent = { Text("${Format.dateTime(e.time)} · ${e.type}") },
                headlineContent = { Text(e.message) },
            )
        }
    }
}
