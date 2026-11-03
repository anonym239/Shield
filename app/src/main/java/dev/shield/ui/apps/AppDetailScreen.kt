@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.apps

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.R
import dev.shield.core.firewall.AppPolicy
import dev.shield.data.repo.AppInfo
import dev.shield.ui.common.AppIcon
import dev.shield.ui.common.EmptyHint
import dev.shield.ui.common.Format
import dev.shield.ui.common.KeyValueRow
import dev.shield.ui.common.SectionTitle
import dev.shield.ui.common.SubScreenBar
import dev.shield.ui.common.SwitchRow
import dev.shield.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.flowOf

@Composable
fun AppDetailScreen(pkg: String, onBack: () -> Unit, vm: AppsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val apps by vm.installed.collectAsStateWithLifecycle()
    val rules by vm.rulesByPkg.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val app: AppInfo = apps.firstOrNull { it.packageName == pkg }
        ?: run {
            val uid = vm.apps.uidOf(pkg) ?: -1
            AppInfo(pkg, uid, vm.apps.resolve(uid).second, false, true)
        }
    val rule = rules[pkg]
    val policy = rule?.policy ?: AppPolicy.ALLOW
    val domainsFlow = remember(app.uid) { if (app.uid >= 0) vm.topDomains(app.uid) else flowOf(emptyList()) }
    val blockedFlow = remember(app.uid) { if (app.uid >= 0) vm.topBlocked(app.uid) else flowOf(emptyList()) }
    val domains by domainsFlow.collectAsState(initial = emptyList())
    val blocked by blockedFlow.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        SubScreenBar(app.label, onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(vm.apps, pkg, app.label, 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (app.isSystem) {
                Text(
                    "⚠ " + stringResource(R.string.system_app_warning),
                    color = LocalStatusColors.current.yellow,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            SectionTitle(stringResource(R.string.internet_access))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AppPolicy.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = policy == p,
                        onClick = { vm.setPolicy(app, p) },
                        shape = SegmentedButtonDefaults.itemShape(i, AppPolicy.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(policyLabel(p), maxLines = 1) }
                }
            }
            if (settings.tunnelMode != dev.shield.data.TunnelMode.FULL && settings.activeProfileId == null) {
                InfoBanner(stringResource(R.string.firewall_dns_limit))
            }
            if (settings.nightModeEnabled) {
                SwitchRow(stringResource(R.string.night_allowed), null, rule?.nightAllowed == true, { vm.setNightAllowed(app, it) })
            }
            if (settings.dataSaverEnabled) {
                SwitchRow(stringResource(R.string.data_saver_app), null, rule?.dataSaver == true, { vm.setDataSaver(app, it) })
            }
            SectionTitle(stringResource(R.string.today))
            val c = today[app.uid]
            KeyValueRow(stringResource(R.string.queries), Format.number(c?.queries ?: 0L))
            KeyValueRow(stringResource(R.string.blocked), Format.number(c?.blocked ?: 0L))

            SectionTitle(stringResource(R.string.top_domains_7d))
            if (domains.isEmpty()) EmptyHint(stringResource(if (settings.noHistory) R.string.no_history_hint else R.string.no_data_yet))
            domains.forEach { KeyValueRow(it.domain, Format.number(it.count)) }
            if (blocked.isNotEmpty()) {
                SectionTitle(stringResource(R.string.top_blocked_7d))
                blocked.forEach { KeyValueRow(it.domain, Format.number(it.count)) }
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    }
                },
                modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.open_app_settings)) }
        }
    }
}
