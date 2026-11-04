@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shield.R
import dev.shield.core.checks.AiAssistant
import dev.shield.core.checks.AiStatus
import dev.shield.core.checks.AiVerdict
import dev.shield.core.checks.StatusLevel
import dev.shield.data.Settings
import dev.shield.data.repo.SettingsRepository
import dev.shield.ui.common.Format
import dev.shield.ui.common.SectionTitle
import dev.shield.ui.common.StatusDot
import dev.shield.ui.common.SwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiViewModel @Inject constructor(
    val ai: AiAssistant,
    private val settings: SettingsRepository,
) : ViewModel() {
    val hasKey = MutableStateFlow(ai.hasKey())
    val error = MutableStateFlow<String?>(null)
    val s = settings.settings

    fun saveKey(k: String) {
        if (k.isBlank()) return
        ai.saveKey(k)
        hasKey.value = true
        viewModelScope.launch { settings.update { it.copy(aiEnabled = true) } }
    }

    fun deleteKey() {
        ai.deleteKey()
        hasKey.value = false
        viewModelScope.launch { settings.update { it.copy(aiEnabled = false) } }
    }

    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.update { it.copy(aiEnabled = on) } }
    fun setModel(m: String) = viewModelScope.launch { settings.update { it.copy(aiModel = m.trim().ifBlank { Settings.DEFAULT_AI_MODEL }) } }

    fun checkNow() = viewModelScope.launch {
        error.value = ai.check().exceptionOrNull()?.message
    }
}

@Composable
fun AiVerdictCard(v: AiVerdict) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(
                    when (v.status) {
                        AiStatus.OK -> StatusLevel.PROTECTED
                        AiStatus.WARNING -> StatusLevel.LIMITED
                        AiStatus.PROBLEM -> StatusLevel.UNPROTECTED
                    },
                )
                Text(stringResource(R.string.ai_last_check, Format.dateTime(v.time)), style = MaterialTheme.typography.titleSmall)
            }
            Text(v.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            if (v.actions.isNotEmpty()) {
                Text(
                    stringResource(R.string.ai_actions_done, v.actions.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun AiScreen(onBack: () -> Unit, vm: AiViewModel = hiltViewModel(), svm: SettingsViewModel = hiltViewModel()) {
    val s by vm.s.collectAsState(initial = Settings())
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val running by vm.ai.running.collectAsStateWithLifecycle()
    val verdict by vm.ai.lastVerdict.collectAsState(initial = null)
    val error by vm.error.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(s.aiModel) }

    SettingsScaffold(stringResource(R.string.ai_title), onBack, svm) {
        Text(
            stringResource(R.string.ai_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        SectionTitle(stringResource(R.string.ai_key))
        if (hasKey) {
            Text(stringResource(R.string.ai_key_saved), modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedButton(onClick = vm::deleteKey, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ai_key_delete))
            }
        } else {
            OutlinedTextField(
                key, { key = it },
                label = { Text(stringResource(R.string.ai_key_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.ai_key_where),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            Button(onClick = {
                vm.saveKey(key)
                key = ""
            }, enabled = key.length > 20, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.save))
            }
        }
        SwitchRow(stringResource(R.string.ai_daily), stringResource(R.string.ai_daily_sub), s.aiEnabled && hasKey, vm::setEnabled, enabled = hasKey)
        OutlinedTextField(
            model, { model = it },
            label = { Text(stringResource(R.string.ai_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.setModel(model) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
            Button(onClick = vm::checkNow, enabled = hasKey && !running, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ai_check_now))
            }
            if (running) CircularProgressIndicator()
        }
        error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        verdict?.let { AiVerdictCard(it) }
    }
}
