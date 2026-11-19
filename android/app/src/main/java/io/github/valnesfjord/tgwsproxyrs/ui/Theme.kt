package io.github.valnesfjord.tgwsproxyrs.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Accent(val swatch: Color) {
    DYNAMIC(Color(0xFF7D8590)),
    BLUE(Color(0xFF1F6FB2)),
    GREEN(Color(0xFF2E6A44)),
    PURPLE(Color(0xFF6750A4)),
    ORANGE(Color(0xFF8B5000)),
}

data class Appearance(val mode: ThemeMode, val accent: Accent, val amoled: Boolean)

val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= 31

@Composable
fun TgWsTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    var scheme = if (appearance.accent == Accent.DYNAMIC && dynamicColorAvailable) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        staticScheme(appearance.accent, dark)
    }
    if (dark && appearance.amoled) scheme = scheme.pureBlack()
    MaterialTheme(colorScheme = scheme, content = content)
}

private class Tones(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
)

private fun tones(accent: Accent, dark: Boolean): Tones = when (accent) {
    Accent.GREEN -> if (dark) {
        Tones(Color(0xFF95D5A6), Color(0xFF00391C), Color(0xFF13512E), Color(0xFFB1F1C1))
    } else {
        Tones(Color(0xFF2E6A44), Color.White, Color(0xFFB1F1C1), Color(0xFF00210E))
    }
    Accent.PURPLE -> if (dark) {
        Tones(Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B), Color(0xFFEADDFF))
    } else {
        Tones(Color(0xFF6750A4), Color.White, Color(0xFFEADDFF), Color(0xFF21005D))
    }
    Accent.ORANGE -> if (dark) {
        Tones(Color(0xFFFFB870), Color(0xFF4A2800), Color(0xFF693C00), Color(0xFFFFDCBE))
    } else {
        Tones(Color(0xFF8B5000), Color.White, Color(0xFFFFDCBE), Color(0xFF2C1600))
    }
    // DYNAMIC falls back here below Android 12.
    Accent.BLUE, Accent.DYNAMIC -> if (dark) {
        Tones(Color(0xFF9FCAFF), Color(0xFF003258), Color(0xFF00497D), Color(0xFFD3E4FF))
    } else {
        Tones(Color(0xFF1F6FB2), Color.White, Color(0xFFD3E4FF), Color(0xFF001C38))
    }
}

private fun staticScheme(accent: Accent, dark: Boolean): ColorScheme {
    val t = tones(accent, dark)
    return if (dark) {
        darkColorScheme(
            primary = t.primary,
            onPrimary = t.onPrimary,
            primaryContainer = t.container,
            onPrimaryContainer = t.onContainer,
            secondaryContainer = t.container,
            onSecondaryContainer = t.onContainer,
        )
    } else {
        lightColorScheme(
            primary = t.primary,
            onPrimary = t.onPrimary,
            primaryContainer = t.container,
            onPrimaryContainer = t.onContainer,
            secondaryContainer = t.container,
            onSecondaryContainer = t.onContainer,
        )
    }
}

private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
)
