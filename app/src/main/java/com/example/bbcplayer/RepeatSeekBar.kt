package com.example.bbcplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

class RepeatSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 184, 77); alpha = 190 }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * resources.displayMetrics.density }
    private var startMs = -1L
    private var endMs = -1L
    private var durationMs = 0L

    fun setRepeatRange(start: Long, end: Long, duration: Long) {
        if (startMs == start && endMs == end && durationMs == duration) return
        startMs = start; endMs = end; durationMs = duration; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startMs < 0 || endMs <= startMs || durationMs <= 0) return
        val usable = width - paddingLeft - paddingRight
        val startX = paddingLeft + usable * (startMs.toFloat() / durationMs)
        val endX = paddingLeft + usable * (endMs.toFloat() / durationMs)
        val centerY = height / 2f
        val half = 3f * resources.displayMetrics.density
        canvas.drawRoundRect(startX, centerY - half, endX, centerY + half, half, half, rangePaint)
        canvas.drawLine(startX, centerY - half * 2, startX, centerY + half * 2, markerPaint)
        canvas.drawLine(endX, centerY - half * 2, endX, centerY + half * 2, markerPaint)
    }
}

