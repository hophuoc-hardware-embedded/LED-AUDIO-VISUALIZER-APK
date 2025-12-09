package com.example.ledfxcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class LEDVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ledCount = 60
    private val ledColors = IntArray(ledCount) { 0xFF000000.toInt() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var ledRadius = 10f
    private var ledSpacing = 2f

    // Pre-allocate gradient để tránh allocate trong onDraw
    private var cachedGradient: RadialGradient? = null
    private var lastGlowColor = 0
    private var lastGlowRadius = 0f
    private var lastX = 0f
    private var lastY = 0f

    init {
        paint.style = Paint.Style.FILL

        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 2f
        borderPaint.color = 0xFF333333.toInt()

        glowPaint.style = Paint.Style.FILL
    }

    fun updateLEDs(colors: IntArray) {
        if (colors.size == ledCount) {
            colors.copyInto(ledColors)
            postInvalidate()
        }
    }

    private fun getOrCreateGradient(x: Float, y: Float, glowRadius: Float, glowColor: Int): RadialGradient {
        // Chỉ tạo mới gradient nếu thông số thay đổi
        if (cachedGradient == null ||
            lastX != x ||
            lastY != y ||
            lastGlowRadius != glowRadius ||
            lastGlowColor != glowColor) {

            cachedGradient = RadialGradient(
                x, y,
                glowRadius,
                intArrayOf(glowColor, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            lastX = x
            lastY = y
            lastGlowRadius = glowRadius
            lastGlowColor = glowColor
        }
        return cachedGradient!!
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val totalWidth = viewWidth - (paddingLeft + paddingRight)
        val availableWidthPerLED = totalWidth / ledCount

        ledRadius = (availableWidthPerLED / 2) - ledSpacing
        val maxRadius = (viewHeight / 2) - 8f
        if (ledRadius > maxRadius) ledRadius = maxRadius
        if (ledRadius < 4f) ledRadius = 4f

        val y = viewHeight / 2
        val glowRadius = ledRadius * 1.8f

        for (i in 0 until ledCount) {
            val x = paddingLeft + ledRadius + (i * (ledRadius * 2 + ledSpacing))
            val color = ledColors[i]

            val isOff = color == 0xFF000000.toInt()

            if (!isOff) {
                // ===== VẼ GLOW (ánh sáng lan tỏa) =====
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF

                // Glow nhẹ, không làm mờ màu chính
                val glowColor = (0x40 shl 24) or (red shl 16) or (green shl 8) or blue

                // Sử dụng cached gradient hoặc tạo mới nếu cần
                glowPaint.shader = getOrCreateGradient(x, y, glowRadius, glowColor)
                canvas.drawCircle(x, y, glowRadius, glowPaint)
            }

            // ===== VẼ LED CHÍNH (màu SÁNG, KHÔNG MỜ) =====
            paint.color = color
            paint.shader = null  // Không dùng gradient - màu đồng nhất
            canvas.drawCircle(x, y, ledRadius, paint)

            // ===== VẼ VIỀN =====
            if (isOff) {
                // LED tắt: viền tối
                borderPaint.color = 0xFF222222.toInt()
                canvas.drawCircle(x, y, ledRadius, borderPaint)
            } else {
                // LED sáng: viền sáng nhẹ để tách biệt các LED
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF

                // Viền sáng hơn một chút
                val brighterRed = minOf(255, (red * 1.2).toInt())
                val brighterGreen = minOf(255, (green * 1.2).toInt())
                val brighterBlue = minOf(255, (blue * 1.2).toInt())

                borderPaint.color = (0xFF shl 24) or (brighterRed shl 16) or (brighterGreen shl 8) or brighterBlue
                borderPaint.strokeWidth = 1.5f
                canvas.drawCircle(x, y, ledRadius, borderPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val desiredHeight = 80
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(widthSize, height)
    }
}