package com.lightterm.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.lightterm.domain.model.TerminalSnapshot
import kotlin.math.ceil
import kotlin.math.max

class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var fontMetrics = Paint.FontMetrics()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EEF5")
        typeface = Typeface.MONOSPACE
        textSize = spToPx(13f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#102131")
        strokeWidth = dpToPx(1f)
    }

    private var snapshot: TerminalSnapshot = TerminalSnapshot.EMPTY
    private var lineHeight = 0f
    private var baselineOffset = 0f
    private var characterWidth = max(textPaint.measureText("W"), 1f)
    private var columns = 80
    private var visibleRows = 24
    private var viewportHeightPx = 0
    private var scaleAccumulator = 1f
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleAccumulator = 1f
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleAccumulator *= detector.scaleFactor
                when {
                    scaleAccumulator >= 1.08f -> {
                        onFontScaleStepRequested?.invoke(1)
                        scaleAccumulator = 1f
                    }

                    scaleAccumulator <= 0.92f -> {
                        onFontScaleStepRequested?.invoke(-1)
                        scaleAccumulator = 1f
                    }
                }
                return true
            }
        },
    )

    var onTerminalSizeChanged: ((Int, Int) -> Unit)? = null
    var onFontScaleStepRequested: ((Int) -> Unit)? = null

    init {
        recalculateTextMetrics()
    }

    fun setTerminalFontSize(fontSizeSp: Float) {
        textPaint.textSize = spToPx(fontSizeSp)
        recalculateTextMetrics()
        post {
            updateViewport(width, viewportHeightPx.takeIf { it > 0 } ?: height)
            invalidate()
        }
    }

    fun renderSnapshot(newSnapshot: TerminalSnapshot) {
        snapshot = newSnapshot
        requestLayout()
        postInvalidateOnAnimation()
    }

    fun updateViewport(viewportWidth: Int, viewportHeight: Int) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return
        }

        viewportHeightPx = viewportHeight
        val newColumns = ((viewportWidth - paddingLeft - paddingRight - dpToPx(24f)) / characterWidth)
            .toInt()
            .coerceAtLeast(MIN_COLUMNS)
        val newRows = ((viewportHeight - paddingTop - paddingBottom - dpToPx(24f)) / lineHeight)
            .toInt()
            .coerceAtLeast(MIN_ROWS)

        if (newColumns != columns || newRows != visibleRows) {
            columns = newColumns
            visibleRows = newRows
            onTerminalSizeChanged?.invoke(columns, visibleRows)
            requestLayout()
            postInvalidateOnAnimation()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (measuredWidth > 0 && viewportHeightPx > 0) {
            updateViewport(measuredWidth, viewportHeightPx)
        }

        val contentRows = max(visibleRows, snapshot.lines.size.coerceAtLeast(1))
        val desiredHeight = ceil(
            paddingTop + paddingBottom + dpToPx(24f) + contentRows * lineHeight,
        ).toInt()

        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#08131C"))

        val left = paddingLeft + dpToPx(12f)
        val top = paddingTop + dpToPx(12f)
        val visibleLines = snapshot.lines.ifEmpty { listOf("") }

        visibleLines.forEachIndexed { index, line ->
            val baseline = top + index * lineHeight + baselineOffset
            canvas.drawText(line, left, baseline, textPaint)
            val guideY = baseline + fontMetrics.descent + dpToPx(2f)
            canvas.drawLine(
                dpToPx(10f),
                guideY,
                width - dpToPx(10f),
                guideY,
                gridPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        val handled = scaleGestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    private fun recalculateTextMetrics() {
        fontMetrics = textPaint.fontMetrics
        lineHeight = (fontMetrics.descent - fontMetrics.ascent) + dpToPx(4f)
        baselineOffset = -fontMetrics.ascent + dpToPx(2f)
        characterWidth = max(textPaint.measureText("W"), 1f)
    }

    private fun spToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private fun dpToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        private const val MIN_COLUMNS = 12
        private const val MIN_ROWS = 6
    }
}
