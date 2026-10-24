package com.example.wallpaperchanger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Shows the whole image with the wallpaper crop window drawn on it.
 * Drag horizontally to move the window. [position] is 0 (left edge) to 1 (right edge).
 * Named "position" rather than "x" so it doesn't clash with View.getX()/setX().
 */
class CropPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onCropChanged: ((Float) -> Unit)? = null

    private var bmp: Bitmap? = null
    private var message: String? = null
    private var ratio = 0.5f
    var position = 0.5f
        private set

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }
    private val imgRect = RectF()
    private val winRect = RectF()

    fun setImage(b: Bitmap?, targetRatio: Float, startPosition: Float) {
        bmp = b
        message = null
        ratio = targetRatio
        position = startPosition.coerceIn(0f, 1f)
        invalidate()
    }

    /** Shows [text] in place of an image, e.g. when a preview fails to load. */
    fun showMessage(text: String) {
        bmp = null
        message = text
        invalidate()
    }

    fun setPosition(v: Float) {
        position = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp
        if (b == null) {
            message?.let { canvas.drawText(it, width / 2f, height / 2f, textPaint) }
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val s = min(vw / b.width, vh / b.height)
        val iw = b.width * s
        val ih = b.height * s
        val ix = (vw - iw) / 2f
        val iy = (vh - ih) / 2f
        imgRect.set(ix, iy, ix + iw, iy + ih)
        canvas.drawBitmap(b, null, imgRect, imgPaint)

        if (b.width.toFloat() / b.height > ratio) {
            // Image is wider than the screen shape: the window cuts off the sides.
            val cw = ih * ratio
            val left = ix + (iw - cw) * position
            winRect.set(left, iy, left + cw, iy + ih)
            canvas.drawRect(imgRect.left, imgRect.top, winRect.left, imgRect.bottom, dimPaint)
            canvas.drawRect(winRect.right, imgRect.top, imgRect.right, imgRect.bottom, dimPaint)
        } else {
            // Image is already narrow enough: the whole image is used, nothing to move.
            winRect.set(imgRect)
        }
        canvas.drawRect(winRect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bmp == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val range = imgRect.width() - winRect.width()
                if (range > 0f) {
                    val p = ((event.x - imgRect.left) - winRect.width() / 2f) / range
                    position = p.coerceIn(0f, 1f)
                    invalidate()
                    onCropChanged?.invoke(position)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
