package com.lightterm.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.lightterm.domain.model.VirtualKey

class VirtualKeyBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private var currentKeys: List<VirtualKey> = emptyList()
    private var compactMode: Boolean = false
    private var onKeyTap: ((VirtualKey) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(
            container,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun bindKeys(
        keys: List<VirtualKey>,
        compactMode: Boolean,
        onKeyTap: (VirtualKey) -> Unit,
    ) {
        currentKeys = keys
        this.compactMode = compactMode
        this.onKeyTap = onKeyTap
        rebuildButtons()
    }

    fun setCompactMode(compactMode: Boolean) {
        if (this.compactMode == compactMode) {
            return
        }
        this.compactMode = compactMode
        rebuildButtons()
    }

    private fun rebuildButtons() {
        container.removeAllViews()
        currentKeys.forEach { key ->
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = key.label
                minWidth = 0
                minimumWidth = if (compactMode) dp(52) else dp(64)
                minimumHeight = if (compactMode) dp(36) else dp(42)
                layoutParams = LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(8)
                }
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(if (compactMode) 14 else 18)
                setOnClickListener { onKeyTap?.invoke(key) }
            }
            container.addView(button)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
