package dev.shield.data.repo

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AppInfo(
    val packageName: String,
    val uid: Int,
    val label: String,
    val isSystem: Boolean,
    val hasInternet: Boolean,
)

/** Installed apps, uid -> app resolution and icons (cached). */
@Singleton
class AppRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val names = ConcurrentHashMap<Int, Pair<String, String>>() // uid -> (package, label)
    private val icons = ConcurrentHashMap<String, Bitmap>()

    val ownUid: Int = Process.myUid()

    @Volatile
    private var cache: List<AppInfo>? = null

    suspend fun installedApps(refresh: Boolean = false): List<AppInfo> = withContext(Dispatchers.IO) {
        cache?.takeIf { !refresh }?.let { return@withContext it }
        @Suppress("DEPRECATION")
        val pkgs: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val list = pkgs.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            AppInfo(
                packageName = p.packageName,
                uid = ai.uid,
                label = ai.loadLabel(pm).toString(),
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                hasInternet = p.requestedPermissions?.contains(Manifest.permission.INTERNET) == true,
            )
        }.sortedBy { it.label.lowercase() }
        list.forEach { names.putIfAbsent(it.uid, it.packageName to it.label) }
        cache = list
        list
    }

    /** (packageName, label) for a uid. Fast after the first call. */
    fun resolve(uid: Int): Pair<String, String> {
        names[uid]?.let { return it }
        val result = when {
            uid < 0 -> "unknown" to "Unbekannt"
            uid == 0 -> "root" to "System (root)"
            uid == Process.SYSTEM_UID -> "android" to "Android-System"
            uid == DNS_UID -> "dns" to "Android-DNS"
            else -> {
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
                if (pkg == null) {
                    "uid:$uid" to "UID $uid"
                } else {
                    val label = runCatching {
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    }.getOrDefault(pkg)
                    pkg to label
                }
            }
        }
        names[uid] = result
        return result
    }

    fun uidOf(packageName: String): Int? =
        runCatching { pm.getApplicationInfo(packageName, 0).uid }.getOrNull()

    fun icon(packageName: String): Bitmap? {
        icons[packageName]?.let { return it }
        val d: Drawable = runCatching { pm.getApplicationIcon(packageName) }.getOrNull() ?: return null
        val bmp = runCatching { d.toBitmap(96, 96) }.getOrNull() ?: return null
        icons[packageName] = bmp
        return bmp
    }

    companion object {
        const val DNS_UID = 1051
    }
}
