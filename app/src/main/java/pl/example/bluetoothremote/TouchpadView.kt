package pl.example.bluetoothremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    interface Listener { fun move(key: Int, repeats: Int); fun stop(key: Int); fun click(); fun pointer(dx:Float,dy:Float):Boolean=false }
    var listener: Listener? = null
    private var startX = 0f; private var startY = 0f; private var lastX = 0f; private var lastY = 0f
    private val density = resources.displayMetrics.density
    private var activeKey: Int? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff777777.toInt(); textAlign = Paint.Align.CENTER; textSize = 12f * density }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText("TOUCHPAD", width/2f, height/2f-5*density, textPaint)
        canvas.drawText("dotknij = OK", width/2f, height/2f+18*density, textPaint)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=e.x;startY=e.y;lastX=e.x;lastY=e.y;parent.requestDisallowInterceptTouchEvent(true);return true }
            MotionEvent.ACTION_MOVE -> {
                val dx=e.x-lastX; val dy=e.y-lastY; val step=18f*density
                if(listener?.pointer(dx,dy)==true){lastX=e.x;lastY=e.y;return true}
                if (hypot(dx.toDouble(),dy.toDouble()) >= step) {
                    val key=if(abs(dx)>abs(dy)) if(dx>0) TvKey.RIGHT else TvKey.LEFT else if(dy>0) TvKey.DOWN else TvKey.UP
                    if (activeKey != key) {
                        activeKey?.let { listener?.stop(it) }
                        activeKey = key
                        listener?.move(key, 1)
                    }
                    lastX=e.x;lastY=e.y
                };return true
            }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false);val moved=activeKey!=null;activeKey?.let { listener?.stop(it) };activeKey=null;if(!moved && hypot((e.x-startX).toDouble(),(e.y-startY).toDouble())<36*density)listener?.click();performClick();return true }
            MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false);activeKey?.let { listener?.stop(it) };activeKey=null }
        };return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
