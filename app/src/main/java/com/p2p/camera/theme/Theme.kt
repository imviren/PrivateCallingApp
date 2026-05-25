package com.p2p.camera.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PremiumDarkColorScheme = darkColorScheme(
    primary = PrimaryNeonBlue,
    secondary = SecondaryNeonPurple,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = OnBackground,
    onSurface = OnSurface,
    onPrimary = OnPrimary,
    onSecondary = OnSecondary,
    error = ErrorRed
)

@Composable
fun CommunicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PremiumDarkColorScheme,
        content = content
    )
}
