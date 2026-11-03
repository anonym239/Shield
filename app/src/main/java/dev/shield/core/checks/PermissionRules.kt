package dev.shield.core.checks

enum class SensitivePermission { MICROPHONE, CAMERA, LOCATION, CONTACTS, SMS, ACCESSIBILITY, DEVICE_ADMIN }

/** Which permission combinations are unusual (pure logic). */
object PermissionRules {
    fun unusualReason(p: Set<SensitivePermission>, system: Boolean): String? {
        if (system) return null
        return when {
            SensitivePermission.ACCESSIBILITY in p && SensitivePermission.SMS in p -> "Bedienungshilfe + SMS (typisch für Banking-Trojaner)"
            SensitivePermission.DEVICE_ADMIN in p && SensitivePermission.ACCESSIBILITY in p -> "Geräteadmin + Bedienungshilfe"
            SensitivePermission.ACCESSIBILITY in p -> "Bedienungshilfe aktiv (kann Bildschirm lesen)"
            SensitivePermission.DEVICE_ADMIN in p -> "Geräteadministrator"
            p.containsAll(listOf(SensitivePermission.MICROPHONE, SensitivePermission.CAMERA, SensitivePermission.LOCATION, SensitivePermission.CONTACTS)) ->
                "Mikrofon, Kamera, Standort und Kontakte"
            SensitivePermission.SMS in p && SensitivePermission.CONTACTS in p && SensitivePermission.MICROPHONE in p -> "SMS, Kontakte und Mikrofon"
            else -> null
        }
    }
}
