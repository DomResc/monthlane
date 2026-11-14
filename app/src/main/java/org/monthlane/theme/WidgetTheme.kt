package org.monthlane.theme

import android.content.Context
import androidx.core.content.edit
import android.content.res.Configuration
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

// Layout, brightness and color are independent settings.
enum class WidgetPreset(val title: String, val radius: Int, val gap: Int, val padding: Int) {
    MINIMAL("Transparent", 20, 8, 16), MATERIAL("Material", 26, 10, 16)
}
enum class ThemeMode(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class Palette(val title: String) { DYNAMIC("Android dynamic"), SAGE("Sage"), CLAY("Clay"), IRIS("Iris") }
data class WidgetAppearance(val preset: WidgetPreset = WidgetPreset.MINIMAL, val mode: ThemeMode = ThemeMode.SYSTEM, val palette: Palette = Palette.DYNAMIC)
data class WidgetColors(val background: Color, val surface: Color, val secondarySurface: Color, val text: Color, val muted: Color, val faint: Color, val accent: Color, val onAccent: Color, val markers: List<Color>)

object AppearanceStore {
    fun load(context: Context): WidgetAppearance {
        val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        fun <T : Enum<T>> read(key: String, values: Array<T>, fallback: T) = values.firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
        return WidgetAppearance(read("preset", WidgetPreset.entries.toTypedArray(), WidgetPreset.MINIMAL), read("mode", ThemeMode.entries.toTypedArray(), ThemeMode.SYSTEM), read("palette", Palette.entries.toTypedArray(), Palette.DYNAMIC))
    }
    fun save(context: Context, appearance: WidgetAppearance) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit {
            putString("preset", appearance.preset.name)
            putString("mode", appearance.mode.name)
            putString("palette", appearance.palette.name)
        }
    }
}

fun isDark(context: Context, mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

fun widgetColorScheme(context: Context, appearance: WidgetAppearance): ColorScheme {
    val dark = isDark(context, appearance.mode)
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

fun widgetColors(context: Context, appearance: WidgetAppearance): WidgetColors {
    val scheme = widgetColorScheme(context, appearance)
    val material = appearance.preset == WidgetPreset.MATERIAL
    return WidgetColors(
        background = if (material) scheme.surfaceContainerLow else scheme.surfaceContainerLow.copy(alpha = .82f),
        surface = if (material) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = .65f),
        secondarySurface = if (material) scheme.secondaryContainer else scheme.secondaryContainer.copy(alpha = .65f),
        text = scheme.onSurface, muted = scheme.onSurface.copy(alpha = .68f), faint = scheme.onSurface.copy(alpha = .35f),
        accent = scheme.primary, onAccent = scheme.onPrimary,
        markers = listOf(scheme.primary, scheme.secondary, scheme.tertiary),
    )
}
