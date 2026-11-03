@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shield.R
import dev.shield.core.checks.StatusLevel
import dev.shield.data.repo.AppRepository
import dev.shield.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.selection.toggleable

@Composable
fun statusIcon(level: StatusLevel): ImageVector = when (level) {
    StatusLevel.PROTECTED -> Icons.Filled.CheckCircle
    StatusLevel.LIMITED -> Icons.Filled.Warning
    StatusLevel.UNPROTECTED -> Icons.Filled.Error
}

@Composable
fun statusText(level: StatusLevel): String = stringResource(
    when (level) {
        StatusLevel.PROTECTED -> R.string.status_protected
        StatusLevel.LIMITED -> R.string.status_limited
        StatusLevel.UNPROTECTED -> R.string.status_unprotected
    },
)

/** Status dot + icon (never color alone). */
@Composable
fun StatusDot(level: StatusLevel, modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current.of(level)
    Icon(
        statusIcon(level),
        contentDescription = statusText(level),
        tint = c,
        modifier = modifier.size(18.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SubScreenBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
        },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
fun AppIcon(apps: AppRepository, packageName: String, label: String, size: Int = 40) {
    val bmp = produceState<Bitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { apps.icon(packageName) }
    }.value
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_app_icon, label),
            modifier = Modifier.size(size.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyHint(text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
