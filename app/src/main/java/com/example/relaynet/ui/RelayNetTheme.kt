package com.example.relaynet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00FF66), // Signal-green
    onPrimary = Color(0xFF001A06),
    primaryContainer = Color(0xFF003D16), // Dark green bubble container
    onPrimaryContainer = Color(0xFFCCFFDD),
    
    secondary = Color(0xFFFFA726), // Warm amber
    onSecondary = Color(0xFF2E1A00),
    secondaryContainer = Color(0xFF4A3200),
    onSecondaryContainer = Color(0xFFFFE0B2),
    
    tertiary = Color(0xFFFF3B30), // Alert red for SOS only
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF5F0000),
    onTertiaryContainer = Color(0xFFFFDAD4),
    
    background = Color(0xFF05070A), // Near-black base
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F1218), // Charcoal surface
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E232B), // Highlight surface
    onSurfaceVariant = Color(0xFF94A3B8),
    
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF4A1212),
    onErrorContainer = Color(0xFFFFDAD4)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF008F35), // Darker green for light mode contrast
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2FFD9),
    onPrimaryContainer = Color(0xFF00330D),
    
    secondary = Color(0xFFE65100), // Darker amber for contrast
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF4A1C00),
    
    tertiary = Color(0xFFFF3B30), // Alert red
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD4),
    onTertiaryContainer = Color(0xFF410000),
    
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569)
)

val RelayTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Monospace, // Monospace for terminal headings
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, // Humanist sans for body text
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, // Humanist sans for message contents
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace, // Monospace for buttons/status text
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 1.25.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace, // Monospace for status metadata
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace, // Monospace for badges
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp
    )
)

@Composable
fun RelayNetTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RelayTypography,
        content = content
    )
}
