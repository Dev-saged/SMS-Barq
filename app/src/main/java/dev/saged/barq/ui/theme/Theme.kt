package dev.saged.barq.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.R
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.ThemeMode

val Readex = FontFamily(
    Font(R.font.readex_regular, FontWeight.Normal),
    Font(R.font.readex_medium, FontWeight.Medium),
    Font(R.font.readex_semibold, FontWeight.SemiBold),
    Font(R.font.readex_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4355B9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E0F9), onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF77536D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F1), onTertiaryContainer = Color(0xFF2D1228),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFEFEFF6), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFEFEFF6), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680), outlineVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF6), surfaceContainerHigh = Color(0xFFE9E7F0),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    inverseSurface = Color(0xFF303036), inverseOnSurface = Color(0xFFF2EFF7), inversePrimary = Color(0xFFBAC3FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C4DD), onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559), onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFE6BAD7), onTertiary = Color(0xFF44263D),
    tertiaryContainer = Color(0xFF5D3C55), onTertiaryContainer = Color(0xFFFFD7F1),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    inverseSurface = Color(0xFFE4E1E9), inverseOnSurface = Color(0xFF303036), inversePrimary = Color(0xFF4355B9),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.r() = copy(fontFamily = Readex)
    return Typography(
        displayLarge = base.displayLarge.r(), displayMedium = base.displayMedium.r(), displaySmall = base.displaySmall.r(),
        headlineLarge = base.headlineLarge.r().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.r().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.r().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.r().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.r().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.r().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.r().copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.r().copy(lineHeight = 21.sp),
        // Arabic letters need a little more height than Latin ones to read comfortably.
        bodySmall = base.bodySmall.r().copy(lineHeight = 18.sp),
        labelLarge = base.labelLarge.r().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.r().copy(lineHeight = 18.sp), labelSmall = base.labelSmall.r(),
    )
}

/** Category accent colours that sit beside the Material scheme. */
@Immutable
data class CategoryColors(val container: Color, val content: Color)

@Immutable
data class BarqExtraColors(val dark: Boolean) {
    fun category(category: Category, scheme: ColorScheme): CategoryColors = when (category) {
        Category.OTP -> CategoryColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        Category.BANK -> if (dark) CategoryColors(Color(0xFF1F4D36), Color(0xFFB2F1C9))
        else CategoryColors(Color(0xFFCDEEDD), Color(0xFF00210F))
        Category.OFFERS -> if (dark) CategoryColors(Color(0xFF5C4300), Color(0xFFFFDEA6))
        else CategoryColors(Color(0xFFFFDEA6), Color(0xFF261A00))
        Category.SPAM -> CategoryColors(scheme.errorContainer, scheme.onErrorContainer)
        Category.PERSONAL -> CategoryColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    }

    /** Pleasant, stable avatar colours derived from the conversation id. */
    fun avatar(seed: Long): CategoryColors {
        val light = listOf(
            0xFFDEE0FF to 0xFF00105C, 0xFFFFD9E2 to 0xFF3E001D, 0xFFCDEEDD to 0xFF00210F,
            0xFFFFDEA6 to 0xFF261A00, 0xFFD3E4FF to 0xFF001C38, 0xFFF3DAFF to 0xFF2C0051,
        )
        val darkList = listOf(
            0xFF293CA0 to 0xFFDEE0FF, 0xFF6F2B44 to 0xFFFFD9E2, 0xFF1F4D36 to 0xFFB2F1C9,
            0xFF5C4300 to 0xFFFFDEA6, 0xFF0F4A7E to 0xFFD3E4FF, 0xFF5A2E7E to 0xFFF3DAFF,
        )
        val list = if (dark) darkList else light
        val (bg, fg) = list[((seed % list.size + list.size) % list.size).toInt()]
        return CategoryColors(Color(bg), Color(fg))
    }
}

val LocalBarqColors = staticCompositionLocalOf { BarqExtraColors(false) }

/** Corner radii shared by every grouped container ("island") in the One UI style. */
object BarqShape {
    val Island = 28.dp
}

/** Fill of an island: white on the gray light background, one step above the dark background. */
@Composable
fun islandColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalBarqColors.current.dark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest
}

/** Only the first and last item of a group get the large corners; the rest stay square. */
fun islandShape(index: Int, count: Int): Shape {
    val top = if (index == 0) BarqShape.Island else 0.dp
    val bottom = if (index == count - 1) BarqShape.Island else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private val BarqShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(BarqShape.Island),
)

@Composable
fun BarqTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        // Arabic-first: right-to-left whenever the Arabic texts are used (every language except
        // English, which has its own texts and reads left-to-right).
        LocalLayoutDirection provides if (context.resources.getBoolean(dev.saged.barq.R.bool.barq_rtl)) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        },
        LocalDensity provides Density(density.density, density.fontScale * settings.fontScale),
        LocalBarqColors provides BarqExtraColors(dark),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(), shapes = BarqShapes, content = content)
    }
}
