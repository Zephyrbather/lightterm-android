package com.lightterm.ui.theme

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

@ColorInt
fun Context.resolveThemeColor(@AttrRes attributeId: Int): Int {
    val typedValue = TypedValue()
    check(theme.resolveAttribute(attributeId, typedValue, true)) {
        "Missing theme attribute: $attributeId"
    }
    return typedValue.data
}
