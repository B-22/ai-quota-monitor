package com.tokenmon.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Set every surface role: Material defaults otherwise bring purple back into
// cards, app bars and elevated surfaces, even when primary is green.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6F4E), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EFE8), onPrimaryContainer = Color(0xFF205537),
    inversePrimary = Color(0xFF8FD5AC),
    secondary = Color(0xFF555B56), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EBE9), onSecondaryContainer = Color(0xFF343835),
    tertiary = Color(0xFF856207), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8EDCD), onTertiaryContainer = Color(0xFF634900),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9E5E3), onErrorContainer = Color(0xFF852019),
    background = Color(0xFFF5F6F5), onBackground = Color(0xFF202421),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF202421),
    surfaceVariant = Color(0xFFECEEEB), onSurfaceVariant = Color(0xFF545B55),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFE2E5E2), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9F8),
    surfaceContainer = Color(0xFFF0F2F0), surfaceContainerHigh = Color(0xFFECEEEB),
    surfaceContainerHighest = Color(0xFFE5E8E5),
    outline = Color(0xFF737B74), outlineVariant = Color(0xFFD8DDD8),
    inverseSurface = Color(0xFF303531), inverseOnSurface = Color(0xFFF2F4F2),
    scrim = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD5AC), onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF243D2D), onPrimaryContainer = Color(0xFFB8E5C9),
    inversePrimary = Color(0xFF2F6F4E),
    secondary = Color(0xFFBEC5BF), onSecondary = Color(0xFF292E2A),
    secondaryContainer = Color(0xFF343A35), onSecondaryContainer = Color(0xFFDAE0DB),
    tertiary = Color(0xFFE0B84C), onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF443A20), onTertiaryContainer = Color(0xFFF1D58A),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF4C2927), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141615), onBackground = Color(0xFFE6E9E6),
    surface = Color(0xFF1C1F1D), onSurface = Color(0xFFE6E9E6),
    surfaceVariant = Color(0xFF303531), onSurfaceVariant = Color(0xFFB8C0B9),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF141615), surfaceBright = Color(0xFF363B37),
    surfaceContainerLowest = Color(0xFF101211), surfaceContainerLow = Color(0xFF1C1F1D),
    surfaceContainer = Color(0xFF222623), surfaceContainerHigh = Color(0xFF292E2A),
    surfaceContainerHighest = Color(0xFF303531),
    outline = Color(0xFF89928A), outlineVariant = Color(0xFF3D453E),
    inverseSurface = Color(0xFFE6E9E6), inverseOnSurface = Color(0xFF292E2A),
    scrim = Color.Black
)

/** Shared remaining-quota ramp, including the widget's bitmap renderer. */
object QuotaColors {
    val healthyLight = Color(0xFF2E7D4F)
    val warningLight = Color(0xFFB8860B)
    val criticalLight = Color(0xFFB3261E)
    val healthyDark = Color(0xFF7FD3A2)
    val warningDark = Color(0xFFE0B84C)
    val criticalDark = Color(0xFFF2B8B5)
    val trackLight = Color(0xFFE1E6E2)
    val trackDark = Color(0xFF3A423E)
}

private val LocalQuotaDarkTheme = staticCompositionLocalOf { false }

@Composable
fun quotaColor(remainingPercent: Double?): Color {
    // Follow the applied app theme, including previews with an explicit override.
    val dark = LocalQuotaDarkTheme.current
    return when {
        remainingPercent == null -> if (dark) QuotaColors.trackDark else QuotaColors.trackLight
        remainingPercent <= LOW_THRESHOLD -> if (dark) QuotaColors.criticalDark else QuotaColors.criticalLight
        remainingPercent <= WARNING_THRESHOLD -> if (dark) QuotaColors.warningDark else QuotaColors.warningLight
        else -> if (dark) QuotaColors.healthyDark else QuotaColors.healthyLight
    }
}

const val LOW_THRESHOLD = 20.0
const val WARNING_THRESHOLD = 40.0

@Composable
fun TokenMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalQuotaDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(10.dp),
                extraLarge = RoundedCornerShape(12.dp)
            ),
            content = content
        )
    }
}
