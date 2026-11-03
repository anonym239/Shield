@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.shield.core.checks.StatusLevel

@Immutable
data class StatusColors(val green: Color, val yellow: Color, val red: Color, val onStatus: Color) {
    fun of(level: StatusLevel): Color = when (level) {
        StatusLevel.PROTECTED -> green
        StatusLevel.LIMITED -> yellow
        StatusLevel.UNPROTECTED -> red
    }
}

private val LightStatus = StatusColors(Color(0xFF1E8E5A), Color(0xFFC98A00), Color(0xFFC62828), Color.White)
private val DarkStatus = StatusColors(Color(0xFF5BD59A), Color(0xFFFFC94D), Color(0xFFFF7A70), Color(0xFF101412))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2C8),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4E6356),
    surface = Color(0xFFF8FAF8),
    background = Color(0xFFF8FAF8),
    surfaceContainer = Color(0xFFECEFEC),
    surfaceContainerHigh = Color(0xFFE6E9E6),
)

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AD6AD),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA6F2C8),
    secondary = Color(0xFFB5CCBC),
    surface = Color(0xFF101412),
    background = Color(0xFF101412),
    surfaceContainer = Color(0xFF1C201E),
    surfaceContainerHigh = Color(0xFF262B28),
)

private val ShieldTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Big numbers on the dashboard. */
val BigNumber = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp)

@Composable
fun ShieldTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = ShieldTypography, content = content)
    }
}
