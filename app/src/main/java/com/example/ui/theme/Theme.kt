package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class HRConnectExtendedColors(
    val navy: Color,
    val navyElevated: Color,
    val gold: Color,
    val goldContainer: Color,
    val onGoldContainer: Color,
    val verified: Color,
    val verifiedBg: Color,
    val pending: Color,
    val pendingBg: Color,
    val cardBorder: Color,
    val mutedText: Color
)

private val LightExtendedColors = HRConnectExtendedColors(
    navy = MidnightNavy,
    navyElevated = MidnightNavyElevated,
    gold = ChampagneGold,
    goldContainer = ChampagneGoldContainer,
    onGoldContainer = ChampagneGoldOnContainer,
    verified = EmeraldVerified,
    verifiedBg = EmeraldVerifiedBg,
    pending = AmberPending,
    pendingBg = AmberPendingBg,
    cardBorder = SoftBorderGrey,
    mutedText = MutedSlate
)

private val DarkExtendedColors = HRConnectExtendedColors(
    navy = MidnightNavy,
    navyElevated = MidnightNavyElevated,
    gold = ChampagneGoldBright,
    goldContainer = Color(0xFF2E2414),
    onGoldContainer = Color(0xFFF3DFAF),
    verified = Color(0xFF4ADE80),
    verifiedBg = Color(0xFF132E1E),
    pending = Color(0xFFFBBF24),
    pendingBg = Color(0xFF33240C),
    cardBorder = DarkOutline,
    mutedText = Color(0xFFB3BDD1)
)

val LocalHRConnectColors = staticCompositionLocalOf { LightExtendedColors }

private val LightColorScheme = lightColorScheme(
    primary = MidnightNavy,
    onPrimary = PureWhite,
    primaryContainer = ChampagneGoldContainer,
    onPrimaryContainer = MidnightNavy,
    secondary = ChampagneGold,
    onSecondary = MidnightNavy,
    secondaryContainer = SoftNeutralGrey,
    onSecondaryContainer = MidnightNavy,
    tertiary = ChampagneGold,
    onTertiary = MidnightNavy,
    tertiaryContainer = ChampagneGoldContainer,
    onTertiaryContainer = ChampagneGoldOnContainer,
    background = WarmOffWhite,
    onBackground = MidnightNavy,
    surface = PureWhite,
    onSurface = MidnightNavy,
    surfaceVariant = SoftNeutralGrey,
    onSurfaceVariant = MutedSlate,
    outline = SoftBorderGrey,
    error = CrimsonError,
    onError = PureWhite,
    errorContainer = CrimsonErrorBg,
    onErrorContainer = CrimsonError
)

private val DarkColorScheme = darkColorScheme(
    primary = ChampagneGoldBright,
    onPrimary = MidnightNavy,
    primaryContainer = MidnightNavyElevated,
    onPrimaryContainer = ChampagneGoldBright,
    secondary = ChampagneGold,
    onSecondary = MidnightNavy,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = PureWhite,
    tertiary = ChampagneGoldBright,
    onTertiary = MidnightNavy,
    tertiaryContainer = Color(0xFF2E2414),
    onTertiaryContainer = Color(0xFFF3DFAF),
    background = DarkSurface,
    onBackground = PureWhite,
    surface = MidnightNavy,
    onSurface = PureWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB3BDD1),
    outline = DarkOutline,
    error = Color(0xFFF87171),
    onError = MidnightNavy,
    errorContainer = Color(0xFF451212),
    onErrorContainer = Color(0xFFFECACA)
)

val HRConnectShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object HRConnectThemeExtras {
    val colors: HRConnectExtendedColors
        @Composable get() = LocalHRConnectColors.current
}

@Composable
fun HRConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalHRConnectColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HRConnectTypography,
            shapes = HRConnectShapes,
            content = content
        )
    }
}
