package com.ehsan.spikecarremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onJoystick(throttle: Int, steering: Int)
        fun onRelease()
    }

    var listener: Listener? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 120, 220)
        style = Paint.Style.FILL
    }

    private var x = 0f
    private var y = 0f
    private var radius = 1f
    private var knobRadius = 1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        radius = min(width, height) * 0.40f
        knobRadius = radius * 0.25f

        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint)

        canvas.drawCircle(cx + x, cy + y, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = event.x - cx
                var dy = event.y - cy
                val max = radius - knobRadius
                val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len > max) {
                    dx = dx / len * max
                    dy = dy / len * max
                }

                x = dx
                y = dy
                invalidate()

                // Right = positive steering.
                // Up = positive throttle.
                val steering = ((x / max) * 100f).toInt().coerceIn(-100, 100)
                val throttle = ((-y / max) * 100f).toInt().coerceIn(-100, 100)

                listener?.onJoystick(throttle, steering)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                x = 0f
                y = 0f
                invalidate()
                listener?.onRelease()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
