package com.speedcards.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E3A8A),
    secondary = Color(0xFFF59E0B),
    tertiary = Color(0xFF0F766E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    secondary = Color(0xFFFBBF24),
    tertiary = Color(0xFF5EEAD4),
)

/** Colors for the answer buttons, shared by the review screen and the preset simulator. */
object RatingColors {
    val again = Color(0xFFD32F2F)
    val hard = Color(0xFFF57C00)
    val medium = Color(0xFF388E3C)
    val easy = Color(0xFF1976D2)
}

@Composable
fun SpeedCardsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
