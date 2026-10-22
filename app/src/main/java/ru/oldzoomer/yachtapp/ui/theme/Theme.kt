package ru.oldzoomer.yachtapp.ui.theme

import android.annotation.SuppressLint
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val HighVisDarkColorScheme = darkColorScheme(
    primary = HighVisYellow,
    onPrimary = OnHighVisYellow,
    secondary = HighVisYellow,
    onSecondary = OnHighVisYellow,
    tertiary = HighVisYellow,
    onTertiary = OnHighVisYellow,
    background = DeepNavy,
    onBackground = OnDeepNavy,
    surface = DeepNavy,
    onSurface = OnDeepNavy,
    error = HighVisError,
    onError = OnHighVisError,
)

private val HighVisLightColorScheme = lightColorScheme(
    primary = HighVisYellow,
    onPrimary = OnHighVisYellow,
    secondary = HighVisYellow,
    onSecondary = OnHighVisYellow,
    tertiary = HighVisYellow,
    onTertiary = OnHighVisYellow,
    background = PureWhite,
    onBackground = OnPureWhite,
    surface = PureWhite,
    onSurface = OnPureWhite,
    error = HighVisError,
    onError = OnHighVisError,
)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
)

@SuppressLint("ObsoleteSdkInt")
@Composable
fun YachtAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highVisMode: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        highVisMode -> if (darkTheme) HighVisDarkColorScheme else HighVisLightColorScheme
        (dynamicColor && VERSION.SDK_INT >= VERSION_CODES.S) -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}