package pl.example.bluetoothremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    interface Listener { fun move(key: Int, repeats: Int); fun click() }
    var listener: Listener? = null
    private var startX = 0f; private var startY = 0f; private var lastX = 0f; private var lastY = 0f
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffe8edff.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff42526e.toInt(); textAlign = Paint.Align.CENTER; textSize = 15f * density }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 24f*density, 24f*density, paint)
        canvas.drawText("Przesuwaj palcem, aby sterować wskaźnikiem", width/2f, height/2f-5*density, textPaint)
        canvas.drawText("Dotknij, aby kliknąć", width/2f, height/2f+20*density, textPaint)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=e.x;startY=e.y;lastX=e.x;lastY=e.y;parent.requestDisallowInterceptTouchEvent(true);return true }
            MotionEvent.ACTION_MOVE -> {
                val dx=e.x-lastX; val dy=e.y-lastY; val step=18f*density
                if (hypot(dx.toDouble(),dy.toDouble()) >= step) {
                    val key=if(abs(dx)>abs(dy)) if(dx>0) TvKey.RIGHT else TvKey.LEFT else if(dy>0) TvKey.DOWN else TvKey.UP
                    listener?.move(key,(hypot(dx.toDouble(),dy.toDouble())/step).toInt().coerceAtLeast(1));lastX=e.x;lastY=e.y
                };return true
            }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false);if(hypot((e.x-startX).toDouble(),(e.y-startY).toDouble())<12*density)listener?.click();performClick();return true }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        };return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
