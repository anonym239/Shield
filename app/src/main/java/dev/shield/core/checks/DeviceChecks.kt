package dev.shield.core.checks

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.core.vpn.AutoVpnPolicy
import dev.shield.core.vpn.NetworkMonitor
import dev.shield.core.vpn.WifiSecurity
import dev.shield.core.firewall.NetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceReport(
    val patchDate: LocalDate?,
    val patchAgeDays: Long?,
    val screenLock: Boolean,
    val devOptions: Boolean,
    val adb: Boolean,
    val installerApps: List<String>,
) {
    val patchOutdated get() = (patchAgeDays ?: 0) > 90
}

data class WifiReport(
    val connected: Boolean,
    val ssid: String?,
    val security: WifiSecurity,
    val open: Boolean,
    val captivePortal: Boolean,
)

data class AppPermissions(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val permissions: Set<SensitivePermission>,
    val unusual: Boolean,
    val reason: String?,
)

/** Device, WLAN and permission checks (SPEC §2.2, §2.3). All local, no network access. */
@Singleton
class DeviceChecks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: NetworkMonitor,
) {
    fun device(): DeviceReport {
        val patch = runCatching { LocalDate.parse(Build.VERSION.SECURITY_PATCH) }.getOrNull()
        val age = patch?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) }
        val km = context.getSystemService(KeyguardManager::class.java)
        val cr = context.contentResolver
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        return DeviceReport(patch, age, km.isDeviceSecure, dev, adb, installerApps())
    }

    /** Apps that are allowed to install other apps ("unbekannte Quellen"). */
    private fun installerApps(): List<String> {
        val pm = context.packageManager
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val pkgs = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())
        return pkgs.filter { p ->
            p.requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true &&
                p.applicationInfo?.let { ai ->
                    runCatching {
                        appOps.unsafeCheckOpNoThrow("android:request_install_packages", ai.uid, p.packageName) ==
                            android.app.AppOpsManager.MODE_ALLOWED
                    }.getOrDefault(false)
                } == true
        }.mapNotNull { it.applicationInfo?.loadLabel(pm)?.toString() }
    }

    fun wifi(): WifiReport {
        val s = network.state.value
        return WifiReport(
            connected = s.type == NetType.WIFI,
            ssid = s.ssid,
            security = s.security,
            open = s.type == NetType.WIFI && AutoVpnPolicy.isOpen(s.security),
            captivePortal = s.captivePortal,
        )
    }

    suspend fun permissions(): List<AppPermissions> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val a11y = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotEmpty() } }?.toSet().orEmpty()
        val admins = context.getSystemService(DevicePolicyManager::class.java).activeAdmins
            ?.map { it.packageName }?.toSet().orEmpty()
        @Suppress("DEPRECATION")
        val pkgs: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        pkgs.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val req = p.requestedPermissions ?: emptyArray()
            val flags = p.requestedPermissionsFlags ?: IntArray(0)
            val granted = req.filterIndexed { i, _ -> i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.toSet()
            val set = mutableSetOf<SensitivePermission>()
            if (Manifest.permission.RECORD_AUDIO in granted) set += SensitivePermission.MICROPHONE
            if (Manifest.permission.CAMERA in granted) set += SensitivePermission.CAMERA
            if (Manifest.permission.ACCESS_FINE_LOCATION in granted || Manifest.permission.ACCESS_COARSE_LOCATION in granted) {
                set += SensitivePermission.LOCATION
            }
            if (Manifest.permission.READ_CONTACTS in granted) set += SensitivePermission.CONTACTS
            if (Manifest.permission.READ_SMS in granted || Manifest.permission.RECEIVE_SMS in granted || Manifest.permission.SEND_SMS in granted) {
                set += SensitivePermission.SMS
            }
            if (p.packageName in a11y) set += SensitivePermission.ACCESSIBILITY
            if (p.packageName in admins) set += SensitivePermission.DEVICE_ADMIN
            if (set.isEmpty()) return@mapNotNull null
            val system = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            val reason = PermissionRules.unusualReason(set, system)
            AppPermissions(p.packageName, ai.loadLabel(pm).toString(), ai.uid, system, set, reason != null, reason)
        }.sortedWith(compareByDescending<AppPermissions> { it.unusual }.thenByDescending { it.permissions.size }.thenBy { it.label.lowercase() })
    }
}

