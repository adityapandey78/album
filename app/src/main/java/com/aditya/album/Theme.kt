package com.aditya.album

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The 3 design options. When you pick the final one, delete the other two here and in specOf().
enum class Look(val label: String, val blurb: String) {
    Clean("Clean", "ColorOS-like · Inter · tight square grid"),
    Bold("Bold", "OriginOS-like · Outfit · rounded tiles, big titles"),
    You("Material You", "Pixel-like · Plus Jakarta Sans · wallpaper colours"),
}

/** Everything that differs between the design options. */
class LookSpec(
    val font: FontFamily,
    val gridGap: Dp,
    val cellCorner: Dp,
    val titleSize: TextUnit,
    val icons: AppIcons,
)

class AppIcons(
    val photos: ImageVector,
    val albums: ImageVector,
    val explore: ImageVector,
    val search: ImageVector,
    val settings: ImageVector,
    val calendar: ImageVector,
)

// Variable fonts (one file per family), bundled so they work offline.
private fun family(res: Int) = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private val inter = family(R.font.inter)
private val outfit = family(R.font.outfit)
private val jakarta = family(R.font.plus_jakarta_sans)

fun specOf(look: Look) = when (look) {
    Look.Clean -> LookSpec(
        inter, gridGap = 2.dp, cellCorner = 0.dp, titleSize = 22.sp,
        AppIcons(
            Icons.Outlined.Photo, Icons.Outlined.PhotoLibrary, Icons.Outlined.Explore, Icons.Outlined.Search, Icons.Outlined.Settings,
            Icons.Outlined.CalendarMonth,
        ),
    )
    Look.Bold -> LookSpec(
        outfit, gridGap = 4.dp, cellCorner = 8.dp, titleSize = 28.sp,
        AppIcons(
            Icons.Rounded.Photo, Icons.Rounded.PhotoLibrary, Icons.Rounded.Explore, Icons.Rounded.Search, Icons.Rounded.Settings,
            Icons.Rounded.CalendarMonth,
        ),
    )
    Look.You -> LookSpec(
        jakarta, gridGap = 3.dp, cellCorner = 4.dp, titleSize = 24.sp,
        AppIcons(
            Icons.Filled.Photo, Icons.Filled.PhotoLibrary, Icons.Filled.Explore, Icons.Filled.Search, Icons.Filled.Settings,
            Icons.Filled.CalendarMonth,
        ),
    )
}

val LocalLook = staticCompositionLocalOf { specOf(Look.Clean) }

@Composable
fun AlbumTheme(look: Look, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val spec = specOf(look)
    val colors = when (look) {
        // Clean: pure white / OLED black with a blue accent
        Look.Clean -> if (dark) {
            darkColorScheme(primary = Color(0xFF7AA7FF), background = Color.Black, surface = Color.Black)
        } else {
            lightColorScheme(primary = Color(0xFF2563EB), background = Color.White, surface = Color.White)
        }
        Look.Bold -> if (dark) darkColorScheme(primary = Color(0xFFB7A6FF)) else lightColorScheme(primary = Color(0xFF6D4AFF))
        Look.You -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    CompositionLocalProvider(LocalLook provides spec) {
        MaterialTheme(colorScheme = colors, typography = Typography().withFont(spec.font)) {
            // Screens drawn over the Scaffold (Settings, Trash, Calendar, ...) aren't inside a Surface, so text with no
            // colour of its own would fall back to black and vanish in dark mode. Default every screen to the theme's.
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)
