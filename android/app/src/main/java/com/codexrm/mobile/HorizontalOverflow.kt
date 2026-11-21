package com.codexrm.mobile

import android.graphics.RectF
import io.noties.markwon.core.spans.TextViewSpan

internal interface HorizontalOverflow {
    val viewport: RectF
    val scrollRange: Float
    fun panBy(distance: Float)
}

internal fun contentWidth(text: CharSequence): Int {
    val view = TextViewSpan.textViewOf(text) ?: return 0
    return if (view is MathTextView && view.formulaWidth > 0) view.formulaWidth
    else (view.width - view.totalPaddingLeft - view.totalPaddingRight).coerceAtLeast(0)
}
