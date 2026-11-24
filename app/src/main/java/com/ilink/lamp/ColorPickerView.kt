package com.ilink.lamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/** Палитра: квадрат насыщенности/яркости слева, полоса оттенка справа. */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val DARK_MAX = 0.86f
    }

    var onColorChange: ((Int, Int, Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val hueW = 26f * dp
    private val gap = 12f * dp

    private var hue = 0f   // 0..1
    private var sat = 0f
    private var value = 1f
    private var drag = 0   // 0 нет, 1 квадрат SV, 2 полоса Hue

    private val svRect = RectF()
    private val hueRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var hueShader: Shader? = null
    private var darkShader: Shader? = null

    init {
        setRgb(59, 130, 246)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        svRect.set(0f, 0f, max(1f, w - hueW - gap), h.toFloat())
        hueRect.set(w - hueW, 0f, w.toFloat(), h.toFloat())
        val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        hueShader = LinearGradient(0f, hueRect.top, 0f, hueRect.bottom, colors, null, Shader.TileMode.CLAMP)
        darkShader = LinearGradient(
            0f, svRect.top, 0f, svRect.bottom,
            Color.argb(0, 0, 0, 0), Color.argb((255 * DARK_MAX).roundToInt(), 0, 0, 0),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val base = Color.HSVToColor(floatArrayOf(hue * 360f, 1f, 1f))
        fill.shader = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, base, Shader.TileMode.CLAMP)
        canvas.drawRect(svRect, fill)
        fill.shader = darkShader
        canvas.drawRect(svRect, fill)
        fill.shader = hueShader
        canvas.drawRect(hueRect, fill)
        fill.shader = null

        val x = svRect.left + sat * max(0f, svRect.width() - 1f)
        val normV = ((1f - value) / DARK_MAX).coerceIn(0f, 1f)
        val y = svRect.top + normV * max(0f, svRect.height() - 1f)
        line.color = Color.BLACK
        line.strokeWidth = dp
        canvas.drawCircle(x, y, 7.5f * dp, line)
        line.color = Color.WHITE
        line.strokeWidth = 2f * dp
        canvas.drawCircle(x, y, 6f * dp, line)

        val yh = hueRect.top + hue * max(0f, hueRect.height() - 1f)
        canvas.drawRoundRect(
            RectF(hueRect.left - 2f * dp, yh - 4f * dp, hueRect.right + 2f * dp, yh + 4f * dp),
            3f * dp, 3f * dp, line
        )
    }

    private fun applyPoint(px: Float, py: Float) {
        if (drag == 1) {
            val x = (px - svRect.left).coerceIn(0f, svRect.width() - 1f)
            val y = (py - svRect.top).coerceIn(0f, svRect.height() - 1f)
            sat = x / max(1f, svRect.width() - 1f)
            value = 1f - (y / max(1f, svRect.height() - 1f)) * DARK_MAX
        } else if (drag == 2) {
            val y = (py - hueRect.top).coerceIn(0f, hueRect.height() - 1f)
            hue = y / max(1f, hueRect.height() - 1f)
        } else return
        invalidate()
        val (r, g, b) = getRgb()
        onColorChange?.invoke(r, g, b)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = when {
                    svRect.contains(e.x, e.y) -> 1
                    hueRect.contains(e.x, e.y) -> 2
                    else -> 0
                }
                if (drag == 0) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                applyPoint(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (drag != 0) applyPoint(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun setRgb(r: Int, g: Int, b: Int) {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        hue = hsv[0] / 360f
        sat = hsv[1]
        value = hsv[2]
        invalidate()
    }

    fun getRgb(): Triple<Int, Int, Int> {
        val c = Color.HSVToColor(floatArrayOf(hue * 360f, sat, value))
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return if (r == 0 && g == 0 && b == 0) Triple(1, 1, 1) else Triple(r, g, b)
    }
}
