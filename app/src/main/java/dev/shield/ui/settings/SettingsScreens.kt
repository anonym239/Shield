@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.BuildConfig
import dev.shield.R
import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.filter.ListCategory
import dev.shield.data.BlockResponse
import dev.shield.data.DohUpstream
import dev.shield.data.TunnelMode
import dev.shield.ui.common.EmptyHint
import dev.shield.ui.common.Format
import dev.shield.ui.common.SectionTitle
import dev.shield.ui.common.SubScreenBar
import dev.shield.ui.common.SwitchRow
import dev.shield.ui.nav.Routes
import dev.shield.ui.theme.LocalStatusColors

/** Shows view model messages as snackbars. */
@Composable
fun MessageHost(vm: SettingsViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { host.showSnackbar(it) } }
    return host
}

@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, vm: SettingsViewModel, content: @Composable () -> Unit) {
    val host = MessageHost(vm)
    androidx.compose.material3.Scaffold(
        topBar = { SubScreenBar(title, onBack) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
fun RadioRow(text: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
fun SettingsScreen(onOpen: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        Entry(Icons.Filled.Dns, R.string.set_protection, R.string.set_protection_sub) { onOpen(Routes.PROTECTION) }
        Entry(Icons.Filled.Rule, R.string.set_rules, R.string.set_rules_sub) { onOpen(Routes.RULES) }
        Entry(Icons.Filled.VpnKey, R.string.set_vpn, R.string.set_vpn_sub) { onOpen(Routes.VPN) }
        Entry(Icons.Filled.FactCheck, R.string.set_checks, R.string.set_checks_sub) { onOpen(Routes.CHECKS) }
        Entry(Icons.Filled.PrivacyTip, R.string.set_privacy, R.string.set_privacy_sub) { onOpen(Routes.PRIVACY) }
        Entry(Icons.Filled.Save, R.string.set_backup, R.string.set_backup_sub) { onOpen(Routes.BACKUP) }
        Entry(Icons.Filled.Extension, R.string.set_extras, R.string.set_extras_sub) { onOpen(Routes.EXTRAS) }
        Entry(Icons.Filled.BugReport, R.string.set_debug, R.string.set_debug_sub) { onOpen(Routes.DEBUG) }
        SectionTitle(stringResource(R.string.appearance))
        SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_sub), s.dynamicColor, { v -> vm.update { it.copy(dynamicColor = v) } })
        SectionTitle(stringResource(R.string.about))
        Text(
            stringResource(R.string.about_text, BuildConfig.VERSION_NAME, shieldcore.Shieldcore.version()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Entry(icon: ImageVector, title: Int, sub: Int, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(sub)) },
        modifier = Modifier.heightIn(min = 56.dp).clickable(onClick = onClick),
    )
}

@Composable
fun ProtectionScreen(onBack: () -> Unit, onOpenRules: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    SettingsScaffold(stringResource(R.string.set_protection), onBack, vm) {
        SectionTitle(stringResource(R.string.tunnel_mode))
        RadioRow(stringResource(R.string.mode_dns), stringResource(R.string.mode_dns_sub), s.tunnelMode == TunnelMode.DNS_ONLY) {
            vm.update { it.copy(tunnelMode = TunnelMode.DNS_ONLY) }
        }
        RadioRow(stringResource(R.string.mode_full), stringResource(R.string.mode_full_sub), s.tunnelMode == TunnelMode.FULL) {
            vm.update { it.copy(tunnelMode = TunnelMode.FULL) }
        }

        SectionTitle(stringResource(R.string.doh_upstream))
        DohUpstream.entries.forEach { u ->
            RadioRow(
                if (u == DohUpstream.CUSTOM) stringResource(R.string.doh_custom) else u.label,
                if (u == DohUpstream.CUSTOM) null else u.url,
                s.upstream == u,
            ) { vm.update { it.copy(upstream = u) } }
        }
        if (s.upstream == DohUpstream.CUSTOM) {
            var url by rememberSaveable { mutableStateOf(s.customDohUrl) }
            var boot by rememberSaveable { mutableStateOf(s.customDohBootstrap) }
            OutlinedTextField(
                url, { url = it }, label = { Text(stringResource(R.string.doh_url)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                boot, { boot = it }, label = { Text(stringResource(R.string.doh_bootstrap)) }, singleLine = true,
                supportingText = { Text(stringResource(R.string.doh_bootstrap_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Button(
                onClick = {
                    if (url.startsWith("https://")) {
                        vm.update { it.copy(customDohUrl = url.trim(), customDohBootstrap = boot.trim()) }
                    } else {
                        vm.message(ctx.getString(R.string.doh_https_only))
                    }
                },
                modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.save)) }
        }

        SectionTitle(stringResource(R.string.block_response))
        RadioRow(stringResource(R.string.block_zero), null, s.blockResponse == BlockResponse.ZERO_IP) {
            vm.update { it.copy(blockResponse = BlockResponse.ZERO_IP) }
        }
        RadioRow(stringResource(R.string.block_nx), null, s.blockResponse == BlockResponse.NXDOMAIN) {
            vm.update { it.copy(blockResponse = BlockResponse.NXDOMAIN) }
        }

        SectionTitle(stringResource(R.string.blocklists))
        Text(
            stringResource(R.string.lists_total, Format.number(lists.totalRules)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BlocklistRepository.SOURCES.forEach { src ->
            val st = lists.lists[src.id]
            val cat = stringResource(
                when (src.category) {
                    ListCategory.ADS -> R.string.cat_ads
                    ListCategory.TRACKING -> R.string.cat_tracking
                    ListCategory.MALWARE -> R.string.cat_malware
                    ListCategory.PHISHING -> R.string.cat_phishing
                    ListCategory.CUSTOM -> R.string.cat_custom
                },
            )
            val sub = buildString {
                append(cat)
                if (st != null && st.rules > 0) append(" · ").append(stringResource(R.string.list_rules, Format.number(st.rules)))
                if (st != null && st.updatedAt > 0) append(" · ").append(Format.ago(ctx, st.updatedAt))
                if (st?.error != null) append(" · ⚠ ").append(st.error)
            }
            SwitchRow(src.name, sub, src.id in s.enabledLists, { on ->
                vm.update { it.copy(enabledLists = if (on) it.enabledLists + src.id else it.enabledLists - src.id) }
            })
        }
        SwitchRow(stringResource(R.string.lists_auto), stringResource(R.string.lists_auto_sub), s.listsAutoUpdate, { v -> vm.update { it.copy(listsAutoUpdate = v) } })
        SwitchRow(stringResource(R.string.lists_wifi_only), null, s.listsWifiOnly, { v -> vm.update { it.copy(listsWifiOnly = v) } }, enabled = s.listsAutoUpdate)
        if (lists.updating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::updateLists, enabled = !lists.updating, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_now))
            }
            OutlinedButton(onClick = onOpenRules, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.set_rules)) }
        }
    }
}

@Composable
fun RulesScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val rules by vm.customRules.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val colors = LocalStatusColors.current
    SettingsScaffold(stringResource(R.string.set_rules), onBack, vm) {
        Text(
            stringResource(R.string.rules_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        OutlinedTextField(
            input, { input = it }, label = { Text(stringResource(R.string.rule_pattern)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                vm.addRule(input, allow = false)
                input = ""
            }, enabled = input.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_block)) }
            OutlinedButton(onClick = {
                vm.addRule(input, allow = true)
                input = ""
            }, enabled = input.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_allow)) }
        }
        if (rules.isEmpty()) EmptyHint(stringResource(R.string.no_rules))
        rules.forEach { r ->
            val allow = r.action == "ALLOW"
            ListItem(
                headlineContent = { Text(r.pattern) },
                supportingContent = {
                    Text(
                        stringResource(if (allow) R.string.rule_allowed else R.string.rule_blocked),
                        color = if (allow) colors.green else colors.red,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { vm.removeRule(r.pattern) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.set_privacy), onBack, vm) {
        Text(
            stringResource(R.string.privacy_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        SwitchRow(stringResource(R.string.no_history), stringResource(R.string.no_history_sub), s.noHistory, { v -> vm.update { it.copy(noHistory = v) } })
        SectionTitle(stringResource(R.string.retention))
        DayChoice(stringResource(R.string.retention_queries), s.retentionQueriesDays, listOf(1, 3, 7, 14, 30), !s.noHistory) { d ->
            vm.update { it.copy(retentionQueriesDays = d) }
        }
        DayChoice(stringResource(R.string.retention_stats), s.retentionStatsDays, listOf(30, 90, 180, 365), true) { d ->
            vm.update { it.copy(retentionStatsDays = d) }
        }
        DayChoice(stringResource(R.string.retention_events), s.retentionEventsDays, listOf(7, 30, 90), true) { d ->
            vm.update { it.copy(retentionEventsDays = d) }
        }
        OutlinedButton(onClick = vm::clearHistory, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.clear_history))
        }
    }
}

@Composable
private fun DayChoice(title: String, value: Int, options: List<Int>, enabled: Boolean, onPick: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEach { d ->
                androidx.compose.material3.FilterChip(
                    selected = value == d,
                    enabled = enabled,
                    onClick = { onPick(d) },
                    label = { Text(stringResource(R.string.days, d)) },
                )
            }
        }
    }
}
