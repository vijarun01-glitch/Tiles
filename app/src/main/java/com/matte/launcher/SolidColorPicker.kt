package com.matte.launcher

import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal class SolidColorPicker(context: Context, initialColor: Int, previewLabel: String = "Tile preview") : LinearLayout(context) {
    private val hsv = FloatArray(3)
    private val hex = EditText(context).apply {
        hint = "#RRGGBB"
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(7))
    }
    private val preview = TextView(context).apply {
        text = previewLabel
        gravity = Gravity.CENTER
    }
    private val sliders = arrayOf(SeekBar(context), SeekBar(context), SeekBar(context))
    private var syncing = false

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(10), dp(24), dp(12))
        addView(preview, LayoutParams(-1, dp(56)))
        for ((index, label) in listOf("Hue", "Saturation", "Brightness").withIndex()) {
            addView(TextView(context).apply {
                text = label
                setPadding(0, dp(10), 0, 0)
            })
            val slider = sliders[index]
            slider.max = if (index == 0) 359 else 100
            slider.contentDescription = label
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    hsv[index] = if (index == 0) progress.toFloat() else progress / 100f
                    val color = Color.HSVToColor(hsv)
                    setHex(color)
                    renderPreview(color)
                }
            })
            addView(slider, LayoutParams(-1, -2))
        }
        addView(hex, LayoutParams(-1, -2))
        hex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!syncing) parseHex()?.let(::showColor)
            }
        })
        setHex(initialColor)
        showColor(initialColor)
        isFocusableInTouchMode = true
        requestFocus()
    }

    fun selectedColor(): Int? = parseHex().also {
        if (it == null) hex.error = "Enter six hex digits"
    }

    private fun parseHex(): Int? {
        val digits = hex.text.toString().trim().removePrefix("#")
        if (digits.length != 6) return null
        val rgb = digits.toIntOrNull(16) ?: return null
        if (rgb < 0 || digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
        return rgb or 0xFF000000.toInt()
    }

    private fun setHex(color: Int) {
        syncing = true
        hex.setText(String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF))
        syncing = false
    }

    private fun showColor(color: Int) {
        Color.colorToHSV(color, hsv)
        sliders[0].progress = hsv[0].roundToInt().coerceAtMost(359)
        sliders[1].progress = (hsv[1] * 100).roundToInt()
        sliders[2].progress = (hsv[2] * 100).roundToInt()
        renderPreview(color)
    }

    private fun renderPreview(color: Int) {
        preview.setBackgroundColor(color)
        preview.setTextColor(TileColors.solid(color).foreground)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
