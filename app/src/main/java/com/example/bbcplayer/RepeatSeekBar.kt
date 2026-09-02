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
    private val bookmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 140, 114) }
    private var startMs = -1L
    private var endMs = -1L
    private var durationMs = 0L
    private var bookmarkPositions = LongArray(0)

    fun setRepeatRange(start: Long, end: Long, duration: Long) {
        if (startMs == start && endMs == end && durationMs == duration) return
        startMs = start; endMs = end; durationMs = duration; invalidate()
    }

    fun setBookmarks(bookmarks: LongArray, duration: Long) {
        if (durationMs == duration && bookmarkPositions.contentEquals(bookmarks)) return
        durationMs = duration
        bookmarkPositions = bookmarks.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0) return
        val usable = width - paddingLeft - paddingRight
        val centerY = height / 2f
        val half = 3f * resources.displayMetrics.density
        if (startMs >= 0 && endMs > startMs) {
            val startX = paddingLeft + usable * (startMs.toFloat() / durationMs)
            val endX = paddingLeft + usable * (endMs.toFloat() / durationMs)
            canvas.drawRoundRect(startX, centerY - half, endX, centerY + half, half, half, rangePaint)
            canvas.drawLine(startX, centerY - half * 2, startX, centerY + half * 2, markerPaint)
            canvas.drawLine(endX, centerY - half * 2, endX, centerY + half * 2, markerPaint)
        }
        bookmarkPositions.filter { it >= 0 }.forEach {
            val x = paddingLeft + usable * (it.toFloat() / durationMs)
            canvas.drawCircle(x, centerY, 4.5f * resources.displayMetrics.density, bookmarkPaint)
        }
    }
}

