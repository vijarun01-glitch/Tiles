package com.matte.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.abs

/** Matches an app's brand to one of the two accent swatches in the Matte palette. */
internal class MatteAppArt private constructor(
    val tone: TileTone,
    private val monochrome: Drawable.ConstantState? = null,
    private val mask: Bitmap? = null
) {
    val glyphScale get() = if (monochrome != null) 2.2f else 1f

    fun glyph(context: Context): Drawable? = when {
        monochrome != null -> monochrome.newDrawable(context.resources).mutate()
        mask != null -> BitmapDrawable(context.resources, mask).mutate()
        else -> null
    }

    companion object {
        private const val SIZE = 48
        fun forApp(icon: Drawable): MatteAppArt? {
            val pixels = sample(icon) ?: return null
            val hsv = FloatArray(3)
            val tones = arrayOfNulls<TileTone>(pixels.size)
            var visible = 0
            var chromatic = 0
            var red = 0
            var green = 0
            pixels.forEachIndexed { i, p ->
                if (Color.alpha(p) < 128) return@forEachIndexed
                visible++
                Color.colorToHSV(p, hsv)
                if (hsv[1] < .35f || hsv[2] < .23f) return@forEachIndexed
                chromatic++
                val hue = hsv[0]
                when {
                    hue <= 20f || hue >= 345f -> { red++; tones[i] = TileTone.SIGNAL }
                    hue in 74f..165f -> { green++; tones[i] = TileTone.ACCENT }
                }
            }
            val matchCount = maxOf(red, green)
            if (visible == 0 || matchCount * 100 < visible * 10 || matchCount * 100 < chromatic * 68) return null
            val tone = if (red > green) TileTone.SIGNAL else TileTone.ACCENT
            val monochrome = if (Build.VERSION.SDK_INT >= 33 && icon is AdaptiveIconDrawable)
                icon.monochrome?.constantState else null
            if (monochrome != null) return MatteAppArt(tone, monochrome = monochrome)
            val coloredBackground = matchCount * 100 > visible * 52
            return MatteAppArt(tone, mask = foregroundMask(pixels, tones, tone, coloredBackground))
        }

        private fun sample(icon: Drawable): IntArray? {
            val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
            val saved = Rect(copy.bounds)
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            return try {
                copy.setBounds(0, 0, SIZE, SIZE)
                copy.draw(Canvas(bitmap))
                IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
            } catch (_: RuntimeException) {
                null
            } finally {
                copy.bounds = saved
                bitmap.recycle()
            }
        }

        private fun foregroundMask(
            pixels: IntArray, tones: Array<TileTone?>, tone: TileTone, coloredBackground: Boolean
        ): Bitmap? {
            fun brightness(pixel: Int) = (Color.red(pixel) * 2 + Color.green(pixel) * 3 + Color.blue(pixel)) / 6
            val brandBrightness = pixels.indices.filter { tones[it] == tone }.map { brightness(pixels[it]) }.average()
            val mask = IntArray(pixels.size)
            var left = SIZE
            var top = SIZE
            var right = -1
            var bottom = -1
            for (i in pixels.indices) {
                val p = pixels[i]
                if (Color.alpha(p) < 128) continue
                val isMark = if (coloredBackground) tones[i] != tone && abs(brightness(p) - brandBrightness) >= 34
                    else tones[i] == tone
                if (!isMark) continue
                left = minOf(left, i % SIZE)
                top = minOf(top, i / SIZE)
                right = maxOf(right, i % SIZE)
                bottom = maxOf(bottom, i / SIZE)
                mask[i] = (Color.alpha(p) shl 24) or 0x00FFFFFF
            }
            if (right <= left || bottom <= top) return null
            val width = right - left + 1
            val height = bottom - top + 1
            val span = maxOf(width, height)
            val square = IntArray(span * span)
            for (y in 0 until height) {
                val start = (top + y) * SIZE + left
                val dest = (y + (span - height) / 2) * span + (span - width) / 2
                mask.copyInto(square, dest, start, start + width)
            }
            return Bitmap.createBitmap(square, span, span, Bitmap.Config.ARGB_8888)
        }
    }
}
