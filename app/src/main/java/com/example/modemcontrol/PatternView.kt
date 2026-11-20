package com.example.modemcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** شبكة 3×3 لرسم النمط. تُرجع قائمة أرقام النقاط (0..8) عند رفع الإصبع. */
class PatternView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var onComplete: ((List<Int>) -> Unit)? = null
    var inputEnabled = true

    private val path = mutableListOf<Int>()
    private var fx = 0f
    private var fy = 0f
    private var drawing = false
    private var error = false
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * resources.displayMetrics.density
    }

    private fun cx(i: Int) = (i % 3 + 0.5f) * width / 3f
    private fun cy(i: Int) = (i / 3 + 0.5f) * height / 3f
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val color = if (error) 0xFFB3261E.toInt() else 0xFF6750A4.toInt()
        line.color = color
        for (k in 1 until path.size) canvas.drawLine(cx(path[k - 1]), cy(path[k - 1]), cx(path[k]), cy(path[k]), line)
        if (drawing && path.isNotEmpty()) canvas.drawLine(cx(path.last()), cy(path.last()), fx, fy, line)
        for (i in 0..8) {
            val sel = i in path
            dot.color = if (sel) color else 0xFF888888.toInt()
            canvas.drawCircle(cx(i), cy(i), if (sel) dp(14f) else dp(9f), dot)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!inputEnabled) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { reset(); drawing = true; hit(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> hit(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                invalidate()
                if (path.isNotEmpty()) onComplete?.invoke(path.toList())
                return true
            }
        }
        fx = e.x; fy = e.y
        invalidate()
        return true
    }

    private fun hit(x: Float, y: Float) {
        val r = width / 3f * 0.35f
        for (i in 0..8) if (i !in path && hypot(x - cx(i), y - cy(i)) < r) {
            path.add(i)
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            break
        }
    }

    fun reset() { path.clear(); error = false; drawing = false; invalidate() }
    fun showError() { error = true; invalidate() }
}
