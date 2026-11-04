@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shield.R
import dev.shield.core.checks.SpeedMath
import dev.shield.core.vpn.Countries
import dev.shield.data.db.WgProfileEntity
import dev.shield.data.repo.ProfileMeta
import dev.shield.data.repo.SpeedResult
import dev.shield.ui.common.Format
import dev.shield.ui.theme.LocalStatusColors

/** Big flag (emoji) or a neutral placeholder; the country name is always given as text, too. */
@Composable
fun FlagBadge(code: String?, size: Int = 36) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Text(code?.let { Countries.flag(it) } ?: "🌐", fontSize = (size * 0.72).sp)
    }
}

/** One VPN server: tap to switch, edit name/country, delete. */
@Composable
fun ServerCard(
    profile: WgProfileEntity,
    meta: ProfileMeta?,
    active: Boolean,
    connected: Boolean,
    testing: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val country = Countries.byCode(meta?.country)
    val status = when {
        connected -> stringResource(R.string.vpn_connected)
        active -> stringResource(R.string.server_active)
        else -> stringResource(R.string.server_tap_to_use)
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .selectable(selected = active, role = Role.RadioButton, onClick = onSelect),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = null)
            Spacer(Modifier.width(8.dp))
            FlagBadge(country?.code)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(country?.nameDe, status).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) LocalStatusColors.current.green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    testing -> Text(stringResource(R.string.speed_running), style = MaterialTheme.typography.bodySmall)
                    meta?.speed != null -> SpeedLine(meta.speed)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.server_edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun SpeedLine(r: SpeedResult) {
    Text(
        "↓ ${SpeedMath.display(r.downMbps)}  ↑ ${SpeedMath.display(r.upMbps)} Mbit/s · ${r.pingMs} ms",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Which server is this?": name, country with flag and a speed test. Opens right after an import. */
@Composable
fun ServerDialog(
    profile: WgProfileEntity,
    meta: ProfileMeta?,
    testing: Boolean,
    canTest: Boolean,
    onSpeedTest: () -> Unit,
    onSave: (name: String, country: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var country by rememberSaveable(profile.id) { mutableStateOf(meta?.country) }
    var countryTouched by rememberSaveable(profile.id) { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { FlagBadge(country, 48) },
        title = { Text(stringResource(R.string.server_dialog_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v ->
                        name = v.take(40)
                        // typing "Niederlande #1" picks the Netherlands automatically
                        if (!countryTouched) Countries.detect(v)?.let { country = it.code }
                    },
                    label = { Text(stringResource(R.string.server_name)) },
                    placeholder = { Text(stringResource(R.string.server_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    val c = Countries.byCode(country)
                    OutlinedTextField(
                        value = c?.let { "${it.flag}  ${it.nameDe}" } ?: stringResource(R.string.server_country_none),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.server_country)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Countries.all.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text("${opt.flag}  ${opt.nameDe}") },
                                onClick = {
                                    country = opt.code
                                    countryTouched = true
                                    expanded = false
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                SpeedPanel(meta?.speed, testing, canTest, onSpeedTest)
                Text(
                    stringResource(R.string.server_endpoint, profile.endpoint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.ifBlank { profile.name }, country) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SpeedPanel(r: SpeedResult?, testing: Boolean, canTest: Boolean, onTest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(8.dp))
            if (r != null) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Metric(Icons.Filled.ArrowDownward, stringResource(R.string.speed_down), SpeedMath.display(r.downMbps), "Mbit/s")
                    Metric(Icons.Filled.ArrowUpward, stringResource(R.string.speed_up), SpeedMath.display(r.upMbps), "Mbit/s")
                    Metric(Icons.Filled.Speed, stringResource(R.string.speed_ping), r.pingMs.toString(), "ms")
                }
                Text(
                    stringResource(R.string.speed_measured, Format.dateTime(r.testedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.speed_none), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.size(8.dp))
            if (testing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.speed_running), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                OutlinedButton(onClick = onTest, enabled = canTest, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.speed_test_now))
                }
                Text(
                    stringResource(if (canTest) R.string.speed_hint else R.string.speed_needs_protection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("$unit · $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small chip for the dashboard: flag + server name. */
@Composable
fun ServerChip(name: String, country: String?, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(name) }, leadingIcon = { FlagBadge(country, 20) })
}
