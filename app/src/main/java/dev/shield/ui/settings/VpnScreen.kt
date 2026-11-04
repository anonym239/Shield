@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.shield.R
import dev.shield.core.firewall.NetType
import dev.shield.ui.apps.InfoBanner
import dev.shield.ui.common.EmptyHint
import dev.shield.ui.common.Recommendations
import dev.shield.ui.common.SectionTitle
import dev.shield.ui.common.SwitchRow
import dev.shield.ui.theme.LocalStatusColors

@Composable
fun VpnScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val profiles by vm.profileList.collectAsStateWithLifecycle()
    val tunnel by vm.tunnel.collectAsStateWithLifecycle()
    val net by vm.network.collectAsStateWithLifecycle()
    var pasteDialog by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProfileFile(uri)
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { vm.importProfile("", it) }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SettingsScaffold(stringResource(R.string.set_vpn), onBack, vm) {
        InfoBanner(stringResource(R.string.vpn_single_app_hint))
        ClipboardConfigOffer(onImport = { vm.importProfile("", it) })
        if (profiles.isEmpty()) {
            ProtonQuickSetup(
                onOpenProton = { openProton(ctx) },
                onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
            )
        } else {
            OutlinedButton(
                onClick = { openProton(ctx) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.proton_add_another)) }
        }
        SectionTitle(stringResource(R.string.wg_profiles))
        ListItem(
            headlineContent = { Text(stringResource(R.string.wg_none)) },
            supportingContent = { Text(stringResource(R.string.wg_none_sub)) },
            leadingContent = { RadioButton(selected = s.activeProfileId == null, onClick = { vm.setActiveProfile(null) }) },
        )
        if (profiles.isEmpty()) EmptyHint(stringResource(R.string.wg_empty))
        profiles.forEach { p ->
            val active = s.activeProfileId == p.id
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = {
                    Text(
                        p.endpoint + if (active && tunnel.wgConnected) " · " + stringResource(R.string.vpn_connected) else "",
                        color = if (active && tunnel.wgConnected) LocalStatusColors.current.green else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = { RadioButton(selected = active, onClick = { vm.setActiveProfile(p.id) }) },
                trailingContent = {
                    IconButton(onClick = { deleteId = p.id }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { fileLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.wg_import_file))
            }
            OutlinedButton(onClick = {
                qrLauncher.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(ctx.getString(R.string.wg_scan_prompt))
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wg_import_qr)) }
        }
        TextButton(onClick = { pasteDialog = true }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.wg_import_paste))
        }
        Text(
            stringResource(R.string.wg_proton_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionTitle(stringResource(R.string.kill_switch))
        SwitchRow(stringResource(R.string.kill_switch), stringResource(R.string.kill_switch_sub), s.killSwitch, { v -> vm.update { it.copy(killSwitch = v) } })
        Text(
            stringResource(R.string.always_on_hint) + if (tunnel.alwaysOn) " ✓ " + stringResource(R.string.always_on_active) else "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(
            onClick = { Recommendations.open(ctx, Intent(Settings.ACTION_VPN_SETTINGS)) },
            modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.open_vpn_settings)) }

        SectionTitle(stringResource(R.string.auto_vpn))
        SwitchRow(stringResource(R.string.auto_vpn), stringResource(R.string.auto_vpn_sub), s.autoVpnEnabled, { v ->
            if (v) locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            vm.update { it.copy(autoVpnEnabled = v) }
        })
        if (s.autoVpnEnabled) {
            Text(stringResource(R.string.home_ssids), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.homeSsids.forEach { ssid ->
                    InputChip(
                        selected = false,
                        onClick = { vm.update { it.copy(homeSsids = it.homeSsids - ssid) } },
                        label = { Text(ssid) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.delete)) },
                    )
                }
            }
            val current = net.ssid
            OutlinedButton(
                onClick = {
                    if (net.type == NetType.WIFI && current != null) {
                        vm.update { it.copy(homeSsids = it.homeSsids + current) }
                    } else {
                        locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        vm.message(ctx.getString(R.string.ssid_unknown))
                    }
                },
                modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_current_wifi, current ?: "–")) }
            SwitchRow(stringResource(R.string.vpn_open_wifi), null, s.vpnOnOpenWifi, { v -> vm.update { it.copy(vpnOnOpenWifi = v) } })
            SwitchRow(stringResource(R.string.vpn_unknown_wifi), null, s.vpnOnUnknownWifi, { v -> vm.update { it.copy(vpnOnUnknownWifi = v) } })
            SwitchRow(stringResource(R.string.vpn_mobile), null, s.vpnOnMobile, { v -> vm.update { it.copy(vpnOnMobile = v) } })
            if (s.activeProfileId == null) InfoBanner(stringResource(R.string.auto_vpn_needs_profile))
        }
    }

    if (pasteDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text(stringResource(R.string.wg_import_paste)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.wg_config)) }, minLines = 6, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pasteDialog = false
                    vm.importProfile(name, text)
                }) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.wg_delete_title)) },
            text = { Text(stringResource(R.string.wg_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(id)
                    deleteId = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Proton account page where WireGuard configurations are created (opened only on tap). */
private const val PROTON_DOWNLOADS = "https://account.protonvpn.com/downloads"

private fun openProton(ctx: android.content.Context) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PROTON_DOWNLOADS)))
    } catch (e: android.content.ActivityNotFoundException) {
        dev.shield.logging.ShieldLog.e("Vpn", "no browser for Proton page", e)
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.adfree_no_browser, PROTON_DOWNLOADS), android.widget.Toast.LENGTH_LONG).show()
    }
}
