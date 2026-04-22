package com.lightterm.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
        textSize = spToPx(12f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#102131")
        strokeWidth = dpToPx(1f)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#163E5A")
    }
    private val focusFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3FA5D8")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }

    private var snapshot: TerminalSnapshot = TerminalSnapshot.EMPTY
    private var lineHeight = 0f
    private var baselineOffset = 0f
    private var characterWidth = max(textPaint.measureText("W"), 1f)
    private var columns = 80
    private var visibleRows = 24
    private var viewportHeightPx = 0
    private var scaleAccumulator = 1f
    private var highlightedLineIndex = -1
    private var inputEnabled = false
    private val imeBuffer: Editable = Editable.Factory.getInstance().newEditable("")
    private val clearHighlightRunnable = Runnable {
        highlightedLineIndex = -1
        postInvalidateOnAnimation()
    }
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
    var onInputSequence: ((String) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
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
        val requiresLayout = newSnapshot.lines.size != snapshot.lines.size
        snapshot = newSnapshot
        if (highlightedLineIndex > snapshot.lines.lastIndex) {
            removeCallbacks(clearHighlightRunnable)
            highlightedLineIndex = -1
        }
        if (requiresLayout) {
            requestLayout()
        }
        postInvalidateOnAnimation()
    }

    fun highlightLine(lineIndex: Int) {
        removeCallbacks(clearHighlightRunnable)
        highlightedLineIndex = lineIndex.coerceIn(0, snapshot.lines.lastIndex.coerceAtLeast(0))
        postInvalidateOnAnimation()
        postDelayed(clearHighlightRunnable, HIGHLIGHT_DURATION_MS)
    }

    fun setInputEnabled(enabled: Boolean) {
        if (inputEnabled == enabled) {
            return
        }
        inputEnabled = enabled
        if (!enabled) {
            resetImeBuffer()
            clearFocus()
            hideKeyboard()
        }
        postInvalidateOnAnimation()
    }

    fun requestTerminalInput() {
        if (!inputEnabled) {
            return
        }
        requestFocus()
        showKeyboard()
    }

    fun scrollYForLine(lineIndex: Int): Int {
        val safeIndex = lineIndex.coerceIn(0, snapshot.lines.lastIndex.coerceAtLeast(0))
        return (paddingTop + dpToPx(8f) + safeIndex * lineHeight).toInt().coerceAtLeast(0)
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
        ).toInt().coerceAtLeast(suggestedMinimumHeight)

        // This view lives inside a NestedScrollView and must be allowed to grow taller
        // than the viewport, otherwise anything past the first screen gets clipped.
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            desiredHeight,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#08131C"))

        val left = paddingLeft + dpToPx(12f)
        val top = paddingTop + dpToPx(12f)
        val visibleLines = snapshot.lines.ifEmpty { listOf("") }

        visibleLines.forEachIndexed { index, line ->
            if (index == highlightedLineIndex) {
                val topY = top + index * lineHeight - dpToPx(2f)
                canvas.drawRoundRect(
                    dpToPx(8f),
                    topY,
                    width - dpToPx(8f),
                    topY + lineHeight,
                    dpToPx(10f),
                    dpToPx(10f),
                    highlightPaint,
                )
            }
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

        if (hasFocus() && inputEnabled) {
            canvas.drawRoundRect(
                dpToPx(6f),
                dpToPx(6f),
                width - dpToPx(6f),
                height - dpToPx(6f),
                dpToPx(16f),
                dpToPx(16f),
                focusFramePaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        val scaleHandled = scaleGestureDetector.onTouchEvent(event)
        val touchHandled = super.onTouchEvent(event)
        return scaleHandled || touchHandled
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        requestTerminalInput()
        return handled || inputEnabled
    }

    override fun onCheckIsTextEditor(): Boolean = inputEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputEnabled) {
            return null
        }
        resetImeBuffer()
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = imeBuffer.length
        outAttrs.initialSelEnd = imeBuffer.length
        return TerminalInputConnection()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (!inputEnabled) {
            return super.onKeyDown(keyCode, event)
        }
        return if (dispatchTerminalKeyEvent(event)) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(
        keyCode: Int,
        repeatCount: Int,
        event: KeyEvent,
    ): Boolean {
        if (!inputEnabled) {
            return super.onKeyMultiple(keyCode, repeatCount, event)
        }
        val characters = event.characters
        if (!characters.isNullOrEmpty()) {
            sendInputSequence(characters)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            resetImeBuffer()
            hideKeyboard()
        }
        postInvalidateOnAnimation()
    }

    private fun recalculateTextMetrics() {
        fontMetrics = textPaint.fontMetrics
        lineHeight = (fontMetrics.descent - fontMetrics.ascent) + dpToPx(4f)
        baselineOffset = -fontMetrics.ascent + dpToPx(2f)
        characterWidth = max(textPaint.measureText("W"), 1f)
    }

    private fun dispatchTerminalKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val sequence = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> CARRIAGE_RETURN

            KeyEvent.KEYCODE_DEL -> BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> FORWARD_DELETE
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> ESCAPE
            KeyEvent.KEYCODE_DPAD_UP -> ARROW_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ARROW_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ARROW_RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> HOME
            KeyEvent.KEYCODE_MOVE_END -> END
            else -> null
        }
        if (sequence != null) {
            sendInputSequence(sequence)
            return true
        }

        val unicodeChar = event.getUnicodeChar(event.metaState)
        if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
            sendInputSequence(String(Character.toChars(unicodeChar)))
            return true
        }
        return false
    }

    private fun sendInputSequence(sequence: String) {
        if (!inputEnabled || sequence.isEmpty()) {
            return
        }
        onInputSequence?.invoke(sequence)
        resetImeBuffer()
    }

    private fun updateImeBuffer(text: CharSequence?) {
        imeBuffer.replace(0, imeBuffer.length, text ?: "")
        Selection.setSelection(imeBuffer, imeBuffer.length)
    }

    private fun resetImeBuffer() {
        imeBuffer.clear()
        Selection.setSelection(imeBuffer, 0)
    }

    private fun showKeyboard() {
        post {
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
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

    private inner class TerminalInputConnection : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = imeBuffer

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            if (!text.isNullOrEmpty()) {
                sendInputSequence(text.toString())
            }
            return true
        }

        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            updateImeBuffer(text)
            return true
        }

        override fun finishComposingText(): Boolean {
            resetImeBuffer()
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            repeat(beforeLength.coerceAtLeast(0)) {
                sendInputSequence(BACKSPACE)
            }
            repeat(afterLength.coerceAtLeast(0)) {
                sendInputSequence(FORWARD_DELETE)
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendInputSequence(CARRIAGE_RETURN)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            return dispatchTerminalKeyEvent(event) || super.sendKeyEvent(event)
        }
    }

    private companion object {
        private const val ESCAPE = "\u001b"
        private const val CARRIAGE_RETURN = "\r"
        private const val BACKSPACE = "\u007f"
        private const val FORWARD_DELETE = "\u001b[3~"
        private const val ARROW_UP = "\u001b[A"
        private const val ARROW_DOWN = "\u001b[B"
        private const val ARROW_RIGHT = "\u001b[C"
        private const val ARROW_LEFT = "\u001b[D"
        private const val HOME = "\u001b[H"
        private const val END = "\u001b[F"
        private const val MIN_COLUMNS = 12
        private const val MIN_ROWS = 6
        private const val HIGHLIGHT_DURATION_MS = 3_200L
    }
}
