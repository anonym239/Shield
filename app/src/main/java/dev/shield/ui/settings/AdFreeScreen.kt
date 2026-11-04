@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shield.R
import dev.shield.core.filter.BlocklistRepository
import dev.shield.data.UBLOCK_LISTS
import dev.shield.logging.ShieldLog
import dev.shield.ui.apps.InfoBanner
import dev.shield.ui.common.SectionTitle

/** Links the guide may open (user-triggered only). */
object AdFreeLinks {
    const val FIREFOX_MARKET = "market://details?id=org.mozilla.firefox"
    const val FIREFOX_WEB = "https://play.google.com/store/apps/details?id=org.mozilla.firefox"
    const val UBLOCK_ADDON = "https://addons.mozilla.org/firefox/android/addon/ublock-origin/"
    const val NEWPIPE = "https://f-droid.org/packages/org.schabi.newpipe/"
}

/** "Ad-free browsing & YouTube": what Shield blocks itself and how to get uBlock Origin in a browser. */
@Composable
fun AdFreeScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val names = BlocklistRepository.SOURCES.filter { it.id in UBLOCK_LISTS }.map { it.name }
    val active = UBLOCK_LISTS.count { it in s.enabledLists }

    SettingsScaffold(stringResource(R.string.adfree_title), onBack, vm) {
        InfoBanner(stringResource(R.string.adfree_honest))

        SectionTitle(stringResource(R.string.adfree_in_shield))
        Para(stringResource(R.string.adfree_in_shield_text))
        Para(stringResource(R.string.adfree_lists_active, active, UBLOCK_LISTS.size))
        names.forEach { Para("• $it") }
        if (active < UBLOCK_LISTS.size) {
            Button(
                onClick = { vm.update { it.copy(enabledLists = it.enabledLists + UBLOCK_LISTS) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.adfree_enable_all)) }
        }

        SectionTitle(stringResource(R.string.adfree_browser))
        Para(stringResource(R.string.adfree_browser_text))
        Para("1. " + stringResource(R.string.adfree_step_firefox))
        Para("2. " + stringResource(R.string.adfree_step_addon))
        Column(Modifier.padding(16.dp)) {
            Button(
                onClick = { open(ctx, AdFreeLinks.FIREFOX_MARKET, AdFreeLinks.FIREFOX_WEB) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.adfree_get_firefox)) }
            OutlinedButton(
                onClick = { open(ctx, AdFreeLinks.UBLOCK_ADDON, null) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.adfree_get_ublock)) }
        }

        SectionTitle(stringResource(R.string.adfree_youtube))
        Para(stringResource(R.string.adfree_youtube_text))
        OutlinedButton(
            onClick = { open(ctx, AdFreeLinks.NEWPIPE, null) },
            modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.adfree_get_newpipe)) }
        Para(stringResource(R.string.adfree_youtube_app_hint))
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
}

private fun open(ctx: Context, url: String, fallback: String?) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        if (fallback != null) return open(ctx, fallback, null)
        ShieldLog.e("AdFree", "no app for $url", e)
        Toast.makeText(ctx, ctx.getString(R.string.adfree_no_browser, url), Toast.LENGTH_LONG).show()
    }
}
