package dev.shield

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.core.wireguard.VpnQuickSetup
import dev.shield.core.wireguard.WgConfigDetector
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import dev.shield.ui.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Receives WireGuard configs opened from the download notification, a file manager or "Share", sets them up
 * with one tap ([VpnQuickSetup]) and then shows the VPN screen. Has no UI of its own.
 */
@AndroidEntryPoint
class ImportActivity : ComponentActivity() {
    @Inject lateinit var quickSetup: VpnQuickSetup
    @Inject lateinit var events: EventLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()
        lifecycleScope.launch {
            val message = try {
                val (name, text) = withContext(Dispatchers.IO) { readIntent(intent) }
                val r = quickSetup.importAndActivate(name, text)
                when {
                    r.needsVpnPermission -> getString(R.string.quick_setup_done_needs_on, r.profile.name)
                    else -> getString(R.string.quick_setup_done, r.profile.name)
                }
            } catch (e: Exception) {
                ShieldLog.e("Import", "quick setup failed", e)
                events.warn(EventType.ERROR, "VPN-Einrichtung fehlgeschlagen: ${e.message}")
                getString(R.string.profile_import_failed, e.message ?: "")
            }
            Toast.makeText(this@ImportActivity, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@ImportActivity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, Routes.VPN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }

    /** Returns (profile name, config text) from VIEW (file) or SEND (file or text). */
    private fun readIntent(i: Intent): Pair<String, String> {
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> streamExtra(i)
            else -> i.data
        }
        if (uri == null) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: throw IllegalArgumentException("Keine Datei erhalten")
            return "" to text
        }
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null }
        if ((size?.first ?: 0L) > WgConfigDetector.MAX_SIZE) throw IllegalArgumentException("Datei ist zu groß")
        val text = contentResolver.openInputStream(uri)?.use { s ->
            String(s.readNBytesCompat(WgConfigDetector.MAX_SIZE + 1))
        } ?: throw IllegalArgumentException("Datei nicht lesbar")
        val name = WgConfigDetector.nameFromFile(size?.second ?: uri.lastPathSegment)
        return name to text
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
