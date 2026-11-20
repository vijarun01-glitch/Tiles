package com.matte.launcher

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
