/**package com.matte.launcher

import kotlin.math.pow

internal enum class TileTone { PAPER, CHARCOAL, DARK, SECONDARY, ACCENT, SIGNAL }

internal data class TileColors(val background: Int, val foreground: Int) {
    companion object {
        fun solid(background: Int): TileColors {
            fun linear(channel: Int): Double {
                val srgb = channel / 255.0
                return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
            }
            val luminance = 0.2126 * linear(background ushr 16 and 0xFF) +
                0.7152 * linear(background ushr 8 and 0xFF) + 0.0722 * linear(background and 0xFF)
            val foreground = if (luminance > 0.179) 0xFF000000 else 0xFFFFFFFF
            return TileColors(background, foreground.toInt())
        }
    }
}

internal class ThemeColors(
    background: Long, panel: Long, text: Long, mutedText: Long, ink: Long,
    paper: Long, charcoal: Long, dark: Long, secondary: Long, accent: Long, signal: Long
) {
    val background = background.toInt()
    val panel = panel.toInt()
    val text = text.toInt()
    val mutedText = mutedText.toInt()
    private val tiles = mapOf(
        TileTone.PAPER to TileColors(paper.toInt(), ink.toInt()),
        TileTone.CHARCOAL to TileColors(charcoal.toInt(), this.text),
        TileTone.DARK to TileColors(dark.toInt(), this.text),
        TileTone.SECONDARY to TileColors(secondary.toInt(), this.text),
        TileTone.ACCENT to TileColors(accent.toInt(), 0xFFFFFFFF.toInt()),
        TileTone.SIGNAL to TileColors(signal.toInt(), 0xFFFFFFFF.toInt())
    )

    operator fun get(tone: TileTone): TileColors = tiles.getValue(tone)
}

internal enum class LauncherTheme(val label: String, val colors: ThemeColors) {
    MATTE("Matte", ThemeColors(
        background = 0xFF1C1D1A, panel = 0xFF2A2C27, text = 0xFFDCDCDA, mutedText = 0xFF8E9184, ink = 0xFF171815,
        paper = 0xFFDCDCDA, charcoal = 0xFF34362F, dark = 0xFF0C0C0E, secondary = 0xFF4B4E44, accent = 0xFF6D8764,
        signal = 0xFFD6392A
    )),
    GRAY("Gray", ThemeColors(
        background = 0xFF1B1D21, panel = 0xFF292D34, text = 0xFFE8EBEF, mutedText = 0xFFADB4BE, ink = 0xFF181B21,
        paper = 0xFFDFE3EA, charcoal = 0xFF363B44, dark = 0xFF161920, secondary = 0xFF464D57, accent = 0xFF535D6A,
        signal = 0xFF626C79
    )),
    BLUE("Blue", ThemeColors(
        background = 0xFF111D2F, panel = 0xFF1A2A42, text = 0xFFDFEDFF, mutedText = 0xFFA7BCD8, ink = 0xFF142740,
        paper = 0xFFB8D8F8, charcoal = 0xFF223B60, dark = 0xFF142742, secondary = 0xFF284D82, accent = 0xFF245CA2,
        signal = 0xFF175A77
    )),
    RED("Red", ThemeColors(
        background = 0xFF21171B, panel = 0xFF322127, text = 0xFFFFE8E7, mutedText = 0xFFE2B3B4, ink = 0xFF372023,
        paper = 0xFFF8CDCA, charcoal = 0xFF4A2C33, dark = 0xFF2B191F, secondary = 0xFF682D37, accent = 0xFF903943,
        signal = 0xFFAE3A43
    )),
    MULTICOLOR("Multicolor", ThemeColors(
        background = 0xFF1A1823, panel = 0xFF282532, text = 0xFFF4EDF8, mutedText = 0xFFC2B7CA, ink = 0xFF302515,
        paper = 0xFFF0D79C, charcoal = 0xFF4E366C, dark = 0xFF223D66, secondary = 0xFF236354, accent = 0xFF465D9E,
        signal = 0xFFA1394D
    ));

    companion object {
        fun fromPreference(name: String?): LauncherTheme = entries.firstOrNull { it.name == name } ?: MATTE
    }
}
*/

package com.matte.launcher

import android.graphics.Color
import kotlin.math.pow

internal enum class TileTone { PAPER, CHARCOAL, DARK, SECONDARY, ACCENT, SIGNAL }

internal data class TileColors(val background: Int, val foreground: Int) {
    companion object {
        private const val LUMINANCE_THRESHOLD = 0.179

        fun luminance(color: Int): Double {
            fun linear(channel: Int): Double {
                val srgb = channel / 255.0
                return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(Color.red(color)) +
                0.7152 * linear(Color.green(color)) +
                0.0722 * linear(Color.blue(color))
        }

        fun contrastingForeground(
            background: Int,
            lightCandidate: Int = 0xFFFFFFFF.toInt(),
            darkCandidate: Int = 0xFF000000.toInt()
        ): Int = if (luminance(background) > LUMINANCE_THRESHOLD) darkCandidate else lightCandidate

        fun solid(background: Int): TileColors =
            TileColors(background, contrastingForeground(background))
    }
}

/**
 * ColorBlendr-inspired fine-tuning options:
 * - [accentSaturation]: 0f = grayscale, 1f = untouched, >1f = boosted saturation.
 * - [backgroundSaturation]: 0f = neutral gray, 1f = untouched, >1f = richer tinted surfaces.
 * - [backgroundLightnessDelta]: shifts surface lightness (-0.15f..0.15f).
 * - [surfaceBlendRatio]: blends [accent] into neutral surfaces (0f..1f).
 * - [textTintRatio]: subtly tints primary/muted text toward [accent] while preserving contrast.
 * - [pitchBlack]: forces true #000000 background for OLED panels/tiles where appropriate.
 */
internal data class ThemeBlendOptions(
    val accentSaturation: Float = 1f,
    val backgroundSaturation: Float = 1f,
    val backgroundLightnessDelta: Float = 0f,
    val surfaceBlendRatio: Float = 0.12f,
    val textTintRatio: Float = 0.14f,
    val pitchBlack: Boolean = false
)

internal object ColorBlender {

    fun lerpColor(start: Int, end: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(start) + ((Color.alpha(end) - Color.alpha(start)) * t)).toInt()
        val r = (Color.red(start) + ((Color.red(end) - Color.red(start)) * t)).toInt()
        val g = (Color.green(start) + ((Color.green(end) - Color.green(start)) * t)).toInt()
        val b = (Color.blue(start) + ((Color.blue(end) - Color.blue(start)) * t)).toInt()
        return Color.argb(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    fun scaleSaturationAndValue(color: Int, saturationScale: Float, lightnessDelta: Float = 0f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * saturationScale).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0f, 1f)
        return Color.rgb(
            Color.red(Color.HSVToColor(Color.alpha(color), hsv)),
            Color.green(Color.HSVToColor(Color.alpha(color), hsv)),
            Color.blue(Color.HSVToColor(Color.alpha(color), hsv))
        ).let { rgb ->
            Color.argb(Color.alpha(color), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        }
    }

    fun blendTheme(base: ThemeColors, options: ThemeBlendOptions): ThemeColors {
        val rawAccent = scaleSaturationAndValue(base.accent, options.accentSaturation)
        val rawSignal = scaleSaturationAndValue(base.signal, options.accentSaturation)

        val blendedBg = base.background
            .let { lerpColor(it, rawAccent, options.surfaceBlendRatio * 0.5f) }
            .let { scaleSaturationAndValue(it, options.backgroundSaturation, options.backgroundLightnessDelta) }
            .let { if (options.pitchBlack) 0xFF000000.toInt() else it }

        val blendedPanel = base.panel
            .let { lerpColor(it, rawAccent, options.surfaceBlendRatio) }
            .let { scaleSaturationAndValue(it, options.backgroundSaturation, options.backgroundLightnessDelta) }

        val blendedText = lerpColor(base.text, rawAccent, options.textTintRatio)
        val blendedMuted = lerpColor(base.mutedText, rawAccent, options.textTintRatio * 1.25f)

        val paperBg = scaleSaturationAndValue(base.paper, options.backgroundSaturation)
        val charcoalBg = base.charcoal
            .let { lerpColor(it, rawAccent, options.surfaceBlendRatio * 0.8f) }
            .let { scaleSaturationAndValue(it, options.backgroundSaturation, options.backgroundLightnessDelta) }
        val darkBg = if (options.pitchBlack) {
            0xFF000000.toInt()
        } else {
            base.dark
                .let { lerpColor(it, rawAccent, options.surfaceBlendRatio * 0.4f) }
                .let { scaleSaturationAndValue(it, options.backgroundSaturation, options.backgroundLightnessDelta) }
        }
        val secondaryBg = base.secondary
            .let { lerpColor(it, rawAccent, options.surfaceBlendRatio * 1.1f) }
            .let { scaleSaturationAndValue(it, options.backgroundSaturation) }

        return ThemeColors(
            background = blendedBg.toLong() and 0xFFFFFFFFL,
            panel = blendedPanel.toLong() and 0xFFFFFFFFL,
            text = blendedText.toLong() and 0xFFFFFFFFL,
            mutedText = blendedMuted.toLong() and 0xFFFFFFFFL,
            ink = base.ink.toLong() and 0xFFFFFFFFL,
            paper = paperBg.toLong() and 0xFFFFFFFFL,
            charcoal = charcoalBg.toLong() and 0xFFFFFFFFL,
            dark = darkBg.toLong() and 0xFFFFFFFFL,
            secondary = secondaryBg.toLong() and 0xFFFFFFFFL,
            accent = rawAccent.toLong() and 0xFFFFFFFFL,
            signal = rawSignal.toLong() and 0xFFFFFFFFL
        )
    }
}

internal class ThemeColors(
    background: Long, panel: Long, text: Long, mutedText: Long, ink: Long,
    paper: Long, charcoal: Long, dark: Long, secondary: Long, accent: Long, signal: Long
) {
    val background: Int = background.toInt()
    val panel: Int = panel.toInt()
    val text: Int = text.toInt()
    val mutedText: Int = mutedText.toInt()
    val ink: Int = ink.toInt()
    val paper: Int = paper.toInt()
    val charcoal: Int = charcoal.toInt()
    val dark: Int = dark.toInt()
    val secondary: Int = secondary.toInt()
    val accent: Int = accent.toInt()
    val signal: Int = signal.toInt()

    private val tiles: Map<TileTone, TileColors> = mapOf(
        TileTone.PAPER to TileColors(this.paper, this.ink),
        TileTone.CHARCOAL to TileColors(this.charcoal, this.text),
        TileTone.DARK to TileColors(this.dark, this.text),
        TileTone.SECONDARY to TileColors(this.secondary, this.text),
        TileTone.ACCENT to TileColors(
            this.accent,
            TileColors.contrastingForeground(this.accent)
        ),
        TileTone.SIGNAL to TileColors(
            this.signal,
            TileColors.contrastingForeground(this.signal)
        )
    )

    operator fun get(tone: TileTone): TileColors = tiles.getValue(tone)

    fun blended(options: ThemeBlendOptions): ThemeColors = ColorBlender.blendTheme(this, options)

    companion object {
        /** Builds a cohesive dark palette from a single seed color (ColorBlendr seed style). */
        fun fromSeed(seedColor: Int, pitchBlack: Boolean = false): ThemeColors {
            val hsv = FloatArray(3)
            Color.colorToHSV(seedColor, hsv)
            val hue = hsv[0]

            val bg = if (pitchBlack) 0xFF000000.toInt() else Color.HSVToColor(floatArrayOf(hue, 0.28f, 0.12f))
            val panel = Color.HSVToColor(floatArrayOf(hue, 0.30f, 0.19f))
            val text = Color.HSVToColor(floatArrayOf(hue, 0.08f, 0.94f))
            val muted = Color.HSVToColor(floatArrayOf(hue, 0.16f, 0.72f))
            val ink = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.11f))

            val paper = Color.HSVToColor(floatArrayOf(hue, 0.18f, 0.90f))
            val charcoal = Color.HSVToColor(floatArrayOf(hue, 0.32f, 0.24f))
            val dark = if (pitchBlack) 0xFF000000.toInt() else Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.09f))
            val secondary = Color.HSVToColor(floatArrayOf((hue + 28f) % 360f, 0.42f, 0.34f))
            val accent = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.68f))
            val signal = Color.HSVToColor(floatArrayOf((hue + 150f) % 360f, 0.72f, 0.78f))

            fun Int.asUnsignedLong(): Long = this.toLong() and 0xFFFFFFFFL

            return ThemeColors(
                background = bg.asUnsignedLong(),
                panel = panel.asUnsignedLong(),
                text = text.asUnsignedLong(),
                mutedText = muted.asUnsignedLong(),
                ink = ink.asUnsignedLong(),
                paper = paper.asUnsignedLong(),
                charcoal = charcoal.asUnsignedLong(),
                dark = dark.asUnsignedLong(),
                secondary = secondary.asUnsignedLong(),
                accent = accent.asUnsignedLong(),
                signal = signal.asUnsignedLong()
            )
        }
    }
}

internal enum class LauncherTheme(
    val label: String,
    val baseColors: ThemeColors,
    val defaultBlend: ThemeBlendOptions = ThemeBlendOptions()
) {
    MATTE(
        label = "Matte",
        baseColors = ThemeColors(
            background = 0xFF1C1D1A, panel = 0xFF2A2C27, text = 0xFFDCDCDA, mutedText = 0xFF8E9184, ink = 0xFF171815,
            paper = 0xFFDCDCDA, charcoal = 0xFF34362F, dark = 0xFF0C0C0E, secondary = 0xFF4B4E44, accent = 0xFF6D8764,
            signal = 0xFFD6392A
        )
    ),
    GRAY(
        label = "Gray",
        baseColors = ThemeColors(
            background = 0xFF1B1D21, panel = 0xFF292D34, text = 0xFFE8EBEF, mutedText = 0xFFADB4BE, ink = 0xFF181B21,
            paper = 0xFFDFE3EA, charcoal = 0xFF363B44, dark = 0xFF161920, secondary = 0xFF464D57, accent = 0xFF535D6A,
            signal = 0xFF626C79
        ),
        defaultBlend = ThemeBlendOptions(accentSaturation = 0.25f, backgroundSaturation = 0.15f, surfaceBlendRatio = 0f)
    ),
    BLUE(
        label = "Blue",
        baseColors = ThemeColors(
            background = 0xFF111D2F, panel = 0xFF1A2A42, text = 0xFFDFEDFF, mutedText = 0xFFA7BCD8, ink = 0xFF142740,
            paper = 0xFFB8D8F8, charcoal = 0xFF223B60, dark = 0xFF142742, secondary = 0xFF284D82, accent = 0xFF245CA2,
            signal = 0xFF175A77
        )
    ),
    RED(
        label = "Red",
        baseColors = ThemeColors(
            background = 0xFF21171B, panel = 0xFF322127, text = 0xFFFFE8E7, mutedText = 0xFFE2B3B4, ink = 0xFF372023,
            paper = 0xFFF8CDCA, charcoal = 0xFF4A2C33, dark = 0xFF2B191F, secondary = 0xFF682D37, accent = 0xFF903943,
            signal = 0xFFAE3A43
        )
    ),
    MULTICOLOR(
        label = "Multicolor",
        baseColors = ThemeColors(
            background = 0xFF1A1823, panel = 0xFF282532, text = 0xFFF4EDF8, mutedText = 0xFFC2B7CA, ink = 0xFF302515,
            paper = 0xFFF0D79C, charcoal = 0xFF4E366C, dark = 0xFF223D66, secondary = 0xFF236354, accent = 0xFF465D9E,
            signal = 0xFFA1394D
        ),
        defaultBlend = ThemeBlendOptions(accentSaturation = 1.25f, backgroundSaturation = 1.15f, surfaceBlendRatio = 0.18f)
    ),
    OLED_NOIR(
        label = "OLED Noir",
        baseColors = ThemeColors(
            background = 0xFF000000, panel = 0xFF141518, text = 0xFFF0F2F5, mutedText = 0xFF9AA1AC, ink = 0xFF0E1013,
            paper = 0xFFE6E9EF, charcoal = 0xFF202227, dark = 0xFF000000, secondary = 0xFF2E323B, accent = 0xFF7FA882,
            signal = 0xFFE05244
        ),
        defaultBlend = ThemeBlendOptions(pitchBlack = true, surfaceBlendRatio = 0.14f, textTintRatio = 0.10f)
    ),
    VIBRANT_BLEND(
        label = "Vibrant Blend",
        baseColors = ThemeColors(
            background = 0xFF151924, panel = 0xFF202738, text = 0xFFF3F6FF, mutedText = 0xFFB5C2DC, ink = 0xFF161C2B,
            paper = 0xFFE2ECFF, charcoal = 0xFF2B354D, dark = 0xFF0E121B, secondary = 0xFF3A4C73, accent = 0xFF5B8DEF,
            signal = 0xFFFF6B57
        ),
        defaultBlend = ThemeBlendOptions(accentSaturation = 1.35f, backgroundSaturation = 1.25f, surfaceBlendRatio = 0.22f, textTintRatio = 0.18f)
    ),
    AMBER_DUSK(
        label = "Amber Dusk",
        baseColors = ThemeColors(
            background = 0xFF1E1914, panel = 0xFF2D251E, text = 0xFFF8EFE4, mutedText = 0xFFCBBBA8, ink = 0xFF241B12,
            paper = 0xFFF4E1C6, charcoal = 0xFF3E3227, dark = 0xFF14100C, secondary = 0xFF594534, accent = 0xFFD4964A,
            signal = 0xFFD95338
        ),
        defaultBlend = ThemeBlendOptions(surfaceBlendRatio = 0.16f, textTintRatio = 0.16f)
    ),
    FOREST_MOSS(
        label = "Forest Moss",
        baseColors = ThemeColors(
            background = 0xFF131D19, panel = 0xFF1D2C26, text = 0xFFE5F2EC, mutedText = 0xFFA2BDB2, ink = 0xFF121E19,
            paper = 0xFFD5E9E0, charcoal = 0xFF263A32, dark = 0xFF0B120F, secondary = 0xFF345045, accent = 0xFF559E7E,
            signal = 0xFFE06D53
        ),
        defaultBlend = ThemeBlendOptions(surfaceBlendRatio = 0.18f, textTintRatio = 0.15f)
    );

    val colors: ThemeColors
        get() = baseColors.blended(defaultBlend)

    fun colorsWith(options: ThemeBlendOptions): ThemeColors = baseColors.blended(options)

    companion object {
        fun fromPreference(name: String?): LauncherTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MATTE
    }
}
