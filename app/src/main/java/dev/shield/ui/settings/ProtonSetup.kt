package dev.shield.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shield.R
import dev.shield.core.wireguard.WgConfigDetector
import dev.shield.logging.ShieldLog

/** "Connect with Proton": one big button and three illustrated steps. */
@Composable
fun ProtonQuickSetup(onOpenProton: () -> Unit, onPickFile: () -> Unit) {
    Card(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.proton_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.proton_quick_intro), style = MaterialTheme.typography.bodyMedium)
            Step(1, Icons.Filled.OpenInBrowser, R.string.proton_quick_step1)
            Step(2, Icons.Filled.Download, R.string.proton_quick_step2)
            Step(3, Icons.Filled.TouchApp, R.string.proton_quick_step3)
            Step(4, Icons.Filled.CheckCircle, R.string.proton_quick_step4)
            Button(onClick = onOpenProton, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.proton_connect))
            }
            TextButton(onClick = onPickFile, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.proton_already_downloaded))
            }
            Text(
                stringResource(R.string.proton_quick_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(n: Int, icon: ImageVector, text: Int) {
    val label = stringResource(text)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$n. $label" },
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("$n. $label", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * When the user returns to Shield with a WireGuard config in the clipboard, offer to take it over.
 * The clipboard is cleared after the import because the config contains the private key.
 */
@Composable
fun ClipboardConfigOffer(onImport: (String) -> Unit) {
    val ctx = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var offered by rememberSaveable { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }

    // Android only lets the focused app read the clipboard
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val text = readClipboard(ctx) ?: return@LaunchedEffect
        if (WgConfigDetector.looksLikeConfig(text) && text.hashCode() != offered) {
            offered = text.hashCode()
            pending = text
        }
    }

    pending?.let { text ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.clipboard_config_title)) },
            text = { Text(stringResource(R.string.clipboard_config_text)) },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    onImport(text)
                    clearClipboard(ctx)
                }) { Text(stringResource(R.string.clipboard_config_use)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun readClipboard(ctx: Context): String? = try {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
} catch (e: SecurityException) {
    ShieldLog.w("Vpn", "clipboard not readable: ${e.message}")
    null
}

private fun clearClipboard(ctx: Context) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        cm.clearPrimaryClip()
    } else {
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    }
}
