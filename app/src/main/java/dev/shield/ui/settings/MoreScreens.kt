@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.R
import dev.shield.core.checks.StatusLevel
import dev.shield.ui.apps.InfoBanner
import dev.shield.ui.common.AppIcon
import dev.shield.ui.common.EmptyHint
import dev.shield.ui.common.KeyValueRow
import dev.shield.ui.common.SectionTitle
import dev.shield.ui.common.StatusDot
import dev.shield.ui.common.SwitchRow

@Composable
private fun CheckRow(label: String, value: String, ok: Boolean?) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        trailingContent = {
            if (ok != null) StatusDot(if (ok) StatusLevel.PROTECTED else StatusLevel.LIMITED)
        },
    )
}

@Composable
fun ChecksScreen(onBack: () -> Unit, onOpenApp: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val device by vm.device.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val unusual by vm.unusualApps.collectAsStateWithLifecycle()
    val leak by vm.leak.collectAsStateWithLifecycle()
    val leakRunning by vm.leakRunning.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshChecks() }
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)

    SettingsScaffold(stringResource(R.string.set_checks), onBack, vm) {
        SectionTitle(stringResource(R.string.wifi_check))
        val w = wifi
        if (w == null || !w.connected) {
            EmptyHint(stringResource(R.string.wifi_not_connected))
        } else {
            CheckRow(stringResource(R.string.wifi_name), w.ssid ?: stringResource(R.string.ssid_hidden), null)
            CheckRow(stringResource(R.string.wifi_encryption), w.security.name, !w.open)
            if (w.open) InfoBanner(stringResource(R.string.wifi_open_warning))
            CheckRow(stringResource(R.string.captive_portal), if (w.captivePortal) yes else no, !w.captivePortal)
        }

        SectionTitle(stringResource(R.string.device_check))
        device?.let { d ->
            CheckRow(
                stringResource(R.string.patch_level),
                (d.patchDate?.toString() ?: "?") + (d.patchAgeDays?.let { " · " + stringResource(R.string.days, it.toInt()) } ?: ""),
                !d.patchOutdated,
            )
            CheckRow(stringResource(R.string.screen_lock), if (d.screenLock) yes else no, d.screenLock)
            CheckRow(stringResource(R.string.dev_options), if (d.devOptions) yes else no, !d.devOptions)
            CheckRow(stringResource(R.string.usb_debugging), if (d.adb) yes else no, !d.adb)
            CheckRow(
                stringResource(R.string.unknown_sources),
                if (d.installerApps.isEmpty()) stringResource(R.string.none) else d.installerApps.joinToString(),
                d.installerApps.isEmpty(),
            )
        } ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))

        SectionTitle(stringResource(R.string.permission_check))
        val u = unusual
        when {
            u == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            u.isEmpty() -> EmptyHint(stringResource(R.string.no_unusual_apps))
            else -> u.forEach { a ->
                ListItem(
                    leadingContent = { AppIcon(vm.apps, a.packageName, a.label) },
                    headlineContent = { Text(a.label) },
                    supportingContent = { Text(a.reason ?: "") },
                    modifier = Modifier.clickable { onOpenApp(a.packageName) },
                )
            }
        }

        SectionTitle(stringResource(R.string.leak_test))
        Text(
            stringResource(R.string.leak_test_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::runLeakTest, enabled = !leakRunning, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.leak_test_run))
            }
            if (leakRunning) CircularProgressIndicator()
        }
        leak?.let { r ->
            KeyValueRow(stringResource(R.string.public_ip), r.ipv4 ?: "–")
            KeyValueRow(stringResource(R.string.country), r.country ?: "–")
            KeyValueRow(stringResource(R.string.ipv6), r.ipv6 ?: stringResource(R.string.ipv6_none))
            CheckRow(stringResource(R.string.dns_leak), if (r.dnsLeak) stringResource(R.string.leak_found) else stringResource(R.string.no_leak), !r.dnsLeak)
            CheckRow(stringResource(R.string.ipv6_leak), if (r.ipv6Leak) stringResource(R.string.leak_found) else stringResource(R.string.no_leak), !r.ipv6Leak)
            if (r.homeIpMatch) InfoBanner(stringResource(R.string.home_ip_visible))
            Text(stringResource(R.string.resolvers_seen), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            r.resolvers.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            InfoBanner(stringResource(R.string.webrtc_hint))
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var password by rememberSaveable { mutableStateOf("") }
    var withProfiles by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportBackup(uri, password, withProfiles)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri, password)
    }
    SettingsScaffold(stringResource(R.string.set_backup), onBack, vm) {
        Text(
            stringResource(R.string.backup_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        OutlinedTextField(
            password, { password = it },
            label = { Text(stringResource(R.string.backup_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text(stringResource(R.string.backup_password_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        SwitchRow(stringResource(R.string.backup_with_profiles), stringResource(R.string.backup_with_profiles_sub), withProfiles, { withProfiles = it })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { exportLauncher.launch("shield-backup.shieldbak") },
                enabled = password.length >= 8 && !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = password.length >= 8 && !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.backup_import)) }
        }
    }
}

@Composable
fun ExtrasScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val reach by vm.reach.collectAsStateWithLifecycle()
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var mac by rememberSaveable { mutableStateOf("") }
    var broadcast by rememberSaveable { mutableStateOf("") }

    SettingsScaffold(stringResource(R.string.set_extras), onBack, vm) {
        SectionTitle(stringResource(R.string.homelab))
        if (tiles.isEmpty()) EmptyHint(stringResource(R.string.homelab_empty))
        tiles.forEach { t ->
            val r = reach[t.id]
            ListItem(
                headlineContent = { Text(t.name) },
                supportingContent = {
                    Text(
                        t.url + when {
                            r == null -> ""
                            r.ok -> " · " + stringResource(R.string.reachable, r.latencyMs ?: 0)
                            else -> " · " + stringResource(R.string.unreachable)
                        },
                    )
                },
                leadingContent = { if (r != null) StatusDot(if (r.ok) StatusLevel.PROTECTED else StatusLevel.UNPROTECTED) },
                trailingContent = {
                    IconButton(onClick = { vm.deleteTile(t.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { addDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.homelab_add)) }
            OutlinedButton(onClick = vm::checkTiles, enabled = tiles.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.homelab_check))
            }
        }

        SectionTitle(stringResource(R.string.wol))
        OutlinedTextField(mac, { mac = it }, label = { Text(stringResource(R.string.wol_mac)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        OutlinedTextField(
            broadcast, { broadcast = it }, label = { Text(stringResource(R.string.wol_broadcast)) }, singleLine = true,
            supportingText = { Text(stringResource(R.string.wol_broadcast_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Button(onClick = { vm.wake(mac, broadcast) }, enabled = mac.isNotBlank(), modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.wol_send))
        }

        SectionTitle(stringResource(R.string.night_mode))
        SwitchRow(stringResource(R.string.night_mode), stringResource(R.string.night_mode_sub), s.nightModeEnabled, { v -> vm.update { it.copy(nightModeEnabled = v) } })
        if (s.nightModeEnabled) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HourPicker(stringResource(R.string.from), s.nightStartMinutes) { m -> vm.update { it.copy(nightStartMinutes = m) } }
                HourPicker(stringResource(R.string.to), s.nightEndMinutes) { m -> vm.update { it.copy(nightEndMinutes = m) } }
            }
        }
        SectionTitle(stringResource(R.string.data_saver))
        SwitchRow(stringResource(R.string.data_saver), stringResource(R.string.data_saver_sub), s.dataSaverEnabled, { v -> vm.update { it.copy(dataSaverEnabled = v) } })
        SectionTitle(stringResource(R.string.weekly_report))
        SwitchRow(stringResource(R.string.weekly_report), stringResource(R.string.weekly_report_sub), s.weeklyReportEnabled, { v -> vm.update { it.copy(weeklyReportEnabled = v) } })
        if (s.nightModeEnabled || s.dataSaverEnabled) InfoBanner(stringResource(R.string.extras_per_app_hint))
    }

    if (addDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text(stringResource(R.string.homelab_add)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    OutlinedTextField(
                        url, { url = it }, label = { Text(stringResource(R.string.url)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    addDialog = false
                    vm.addTile(name, url)
                }, enabled = url.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun HourPicker(label: String, minutes: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("%02d:%02d".format(minutes / 60, minutes % 60))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0 until 24).forEach { h ->
                DropdownMenuItem(text = { Text("%02d:00".format(h)) }, onClick = {
                    open = false
                    onPick(h * 60)
                })
            }
        }
    }
}
