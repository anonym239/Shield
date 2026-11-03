@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.R
import dev.shield.core.checks.SensitivePermission
import dev.shield.core.firewall.AppPolicy
import dev.shield.data.TunnelMode
import dev.shield.data.repo.AppInfo
import dev.shield.ui.common.AppIcon
import dev.shield.ui.common.EmptyHint
import dev.shield.ui.common.Format
import dev.shield.ui.theme.LocalStatusColors

@Composable
fun AppsScreen(onOpen: (String) -> Unit, vm: AppsViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.apps_tab_firewall)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.apps_tab_ranking)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.apps_tab_permissions)) })
        }
        when (tab) {
            0 -> FirewallList(vm, onOpen)
            1 -> Ranking(vm, onOpen)
            else -> PermissionList(vm, onOpen)
        }
    }
}

@Composable
private fun FirewallList(vm: AppsViewModel, onOpen: (String) -> Unit) {
    val apps by vm.installed.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val rules by vm.rulesByPkg.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val filtered = apps.filter { a ->
        a.hasInternet && (showSystem || !a.isSystem) &&
            (query.isBlank() || a.label.contains(query, true) || a.packageName.contains(query, true))
    }.sortedByDescending { today[it.uid]?.queries ?: 0 }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (settings.tunnelMode == TunnelMode.DNS_ONLY && settings.activeProfileId == null) {
                InfoBanner(stringResource(R.string.firewall_dns_limit))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_apps)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text(stringResource(R.string.show_system_apps)) })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        }
        items(filtered, key = { it.packageName }) { app ->
            val c = today[app.uid]
            val policy = rules[app.packageName]?.policy ?: AppPolicy.ALLOW
            ListItem(
                leadingContent = { AppIcon(vm.apps, app.packageName, app.label) },
                headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        stringResource(R.string.app_counts_today, Format.number(c?.queries ?: 0L), Format.number(c?.blocked ?: 0L)) +
                            if (app.isSystem) " · " + stringResource(R.string.system_app) else "",
                    )
                },
                trailingContent = { PolicyMenu(policy) { vm.setPolicy(app, it) } },
                modifier = Modifier.clickable { onOpen(app.packageName) },
            )
        }
        if (!loading && filtered.isEmpty()) item { EmptyHint(stringResource(R.string.no_apps_found)) }
    }
}

@Composable
fun policyLabel(p: AppPolicy): String = stringResource(
    when (p) {
        AppPolicy.ALLOW -> R.string.policy_allow
        AppPolicy.WIFI_ONLY -> R.string.policy_wifi
        AppPolicy.MOBILE_ONLY -> R.string.policy_mobile
        AppPolicy.BLOCK -> R.string.policy_block
    },
)

@Composable
fun PolicyMenu(policy: AppPolicy, onChange: (AppPolicy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val color = if (policy == AppPolicy.BLOCK) LocalStatusColors.current.red else MaterialTheme.colorScheme.primary
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(policyLabel(policy), color = color)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        AppPolicy.entries.forEach { p ->
            DropdownMenuItem(text = { Text(policyLabel(p)) }, onClick = {
                open = false
                onChange(p)
            })
        }
    }
}

@Composable
fun InfoBanner(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Ranking(vm: AppsViewModel, onOpen: (String) -> Unit) {
    val ranking by vm.ranking.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.ranking_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                stringResource(R.string.ranking_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (ranking.isEmpty()) item { EmptyHint(stringResource(R.string.no_data_yet)) }
        val max = (ranking.maxOfOrNull { it.queries } ?: 1).coerceAtLeast(1)
        items(ranking.size) { i ->
            val a = ranking[i]
            val (pkg, label) = vm.apps.resolve(a.uid)
            ListItem(
                leadingContent = { AppIcon(vm.apps, pkg, label) },
                overlineContent = { Text("#${i + 1}") },
                headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.app_counts_week, Format.number(a.queries), Format.number(a.blocked)))
                        LinearProgressIndicator(
                            progress = { a.queries.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                },
                modifier = Modifier.clickable { onOpen(pkg) },
            )
        }
    }
}

@Composable
fun permissionLabel(p: SensitivePermission): String = stringResource(
    when (p) {
        SensitivePermission.MICROPHONE -> R.string.perm_mic
        SensitivePermission.CAMERA -> R.string.perm_camera
        SensitivePermission.LOCATION -> R.string.perm_location
        SensitivePermission.CONTACTS -> R.string.perm_contacts
        SensitivePermission.SMS -> R.string.perm_sms
        SensitivePermission.ACCESSIBILITY -> R.string.perm_accessibility
        SensitivePermission.DEVICE_ADMIN -> R.string.perm_admin
    },
)

@Composable
fun PermissionList(vm: AppsViewModel, onOpen: (String) -> Unit) {
    val list by vm.permissions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPermissions() }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            InfoBanner(stringResource(R.string.permissions_info))
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text(stringResource(R.string.show_system_apps)) })
            }
        }
        val l = list
        if (l == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        } else {
            val shown = l.filter { showSystem || !it.isSystem }
            items(shown, key = { it.packageName }) { a ->
                ListItem(
                    leadingContent = { AppIcon(vm.apps, a.packageName, a.label) },
                    headlineContent = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(a.permissions.map { permissionLabel(it) }.joinToString(", "))
                            if (a.unusual && a.reason != null) {
                                Text("⚠ " + a.reason, color = LocalStatusColors.current.yellow, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    modifier = Modifier.clickable { onOpen(a.packageName) },
                )
            }
        }
    }
}

